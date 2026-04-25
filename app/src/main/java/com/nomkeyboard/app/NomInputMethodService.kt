package com.nomkeyboard.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.preference.PreferenceManager
import com.nomkeyboard.app.dict.NomDictionary
import com.nomkeyboard.app.telex.TelexEngine
import com.nomkeyboard.app.ui.CandidateBar
import com.nomkeyboard.app.ui.KeyboardTheme
import com.nomkeyboard.app.ui.KeyboardView

/**
 * Main input-method service for the Nom Keyboard.
 *
 * Workflow:
 *   1. The user types a letter on the KeyboardView -> onChar() feeds it to the Telex engine
 *      and updates the composing buffer.
 *   2. While composing is non-empty, candidates are looked up from [NomDictionary] and
 *      displayed on the [CandidateBar].
 *   3. Tapping a Nom candidate commits it to the text field and clears the composing buffer;
 *      the chosen character's usage counter is bumped for future ordering.
 *   4. Pressing space / enter / punctuation commits the current Vietnamese text first,
 *      then sends the control character.
 *   5. Backspace removes the last character of the composing buffer if non-empty, otherwise
 *      it deletes from the committed text field.
 */
class NomInputMethodService : InputMethodService(), KeyboardView.KeyActionListener,
    CandidateBar.OnCandidatePickListener {

    private lateinit var rootView: LinearLayout
    private lateinit var candidateBar: CandidateBar
    private lateinit var keyboardView: KeyboardView

    private var composing: String = ""
    private var nomTypeface: Typeface? = null

    private val recentCounts = HashMap<String, Int>()
    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        NomDictionary.ensureLoaded(applicationContext)
        // Load the bundled Han-Nom font; fail-safe to system font if missing
        nomTypeface = try {
            Typeface.createFromAsset(assets, "fonts/HanNomGothic.ttf")
        } catch (e: Exception) {
            null
        }
        loadRecent()
    }

    override fun onCreateInputView(): View {
        val ctx: Context = this
        rootView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        candidateBar = CandidateBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.kb_candidate_height)
            )
            setTypeface(nomTypeface)
            listener = this@NomInputMethodService
        }
        keyboardView = KeyboardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            listener = this@NomInputMethodService
        }

        applyTheme()
        val showCandidate = prefs.getBoolean("pref_show_candidates", true)
        if (showCandidate) rootView.addView(candidateBar)
        rootView.addView(keyboardView)
        return rootView
    }

    private fun applyTheme() {
        val theme = KeyboardTheme.from(applicationContext)
        rootView.setBackgroundColor(theme.bg)
        keyboardView.applyTheme(theme)
        candidateBar.applyTheme(theme)

        keyboardView.setHapticsEnabled(prefs.getBoolean("pref_vibrate", true))
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetComposing(commit = false)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resetComposing(commit = false)
        applyTheme()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        resetComposing(commit = false)
    }

    // ============================ Key callbacks ============================

    override fun onChar(ch: Char) {
        playKeyClickSound()
        if (!ch.isLetter() && ch != '\'' && ch != '-') {
            // Non-letter: commit current composing first, then insert the raw symbol
            commitComposing()
            currentInputConnection?.commitText(ch.toString(), 1)
            return
        }
        // Apply Telex transformation
        composing = TelexEngine.apply(composing, ch)
        updateComposing()
    }

    override fun onBackspace() {
        playKeyClickSound()
        if (composing.isNotEmpty()) {
            composing = composing.dropLast(1)
            updateComposing()
        } else {
            // Delegate to the text field: send a DEL key event
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onEnter() {
        playKeyClickSound()
        commitComposing()
        val ei = currentInputEditorInfo
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
        when (action) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT -> currentInputConnection?.performEditorAction(action)
            else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onSpace() {
        playKeyClickSound()
        // Behaviour:
        //   - With composing: commit the plain Vietnamese text first, then insert a space.
        //     To get a Nom character, the user must tap a candidate before pressing space.
        //   - Without composing: just insert a space.
        //   (Compound-word entries are triggered by typing consecutive syllables without
        //   spaces, e.g. "chunom" -> 𡦂喃.)
        commitComposing()
        currentInputConnection?.commitText(" ", 1)
    }

    override fun onSymbol(text: String) {
        commitComposing()
        currentInputConnection?.commitText(text, 1)
    }

    override fun onSwitchLanguage() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    // ============================ Candidate selection ============================

    override fun onPickCandidate(index: Int, text: String) {
        currentInputConnection?.commitText(text, 1)
        bumpRecent(text)
        composing = ""
        updateComposing()
    }

    // ============================ Composing & candidate refresh ============================

    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) {
            ic.setComposingText("", 1)
            ic.finishComposingText()
            candidateBar.clear()
            return
        }
        ic.setComposingText(composing, 1)
        candidateBar.setComposing(composing)
        val list = NomDictionary.lookup(composing.trim())
        // Sort by recent-usage frequency, stable ordering preserves dictionary order otherwise
        val sorted = list.sortedByDescending { recentCounts.getOrDefault(it, 0) }
        candidateBar.setCandidates(sorted)
    }

    private fun commitComposing() {
        if (composing.isEmpty()) return
        currentInputConnection?.commitText(composing, 1)
        composing = ""
        candidateBar.clear()
        currentInputConnection?.finishComposingText()
    }

    private fun resetComposing(commit: Boolean) {
        if (commit) commitComposing()
        composing = ""
        if (::candidateBar.isInitialized) candidateBar.clear()
        currentInputConnection?.finishComposingText()
    }

    // ============================ Misc helpers ============================

    private fun playKeyClickSound() {
        if (!prefs.getBoolean("pref_sound", false)) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    private fun bumpRecent(text: String) {
        val n = recentCounts.getOrDefault(text, 0) + 1
        recentCounts[text] = n
        if (recentCounts.size > 500) {
            // Keep the top-400 most frequent entries to bound memory/prefs size
            val kept = recentCounts.entries.sortedByDescending { it.value }.take(400)
            recentCounts.clear()
            for ((k, v) in kept) recentCounts[k] = v
        }
        // Persist asynchronously
        prefs.edit().apply {
            putString(PREF_RECENT, recentCounts.entries.joinToString("|") { "${it.key}:${it.value}" })
            apply()
        }
    }

    private fun loadRecent() {
        val s = prefs.getString(PREF_RECENT, null) ?: return
        recentCounts.clear()
        for (pair in s.split("|")) {
            val idx = pair.lastIndexOf(":")
            if (idx > 0) {
                val k = pair.substring(0, idx)
                val v = pair.substring(idx + 1).toIntOrNull() ?: continue
                if (k.isNotEmpty()) recentCounts[k] = v
            }
        }
    }

    companion object {
        const val PREF_RECENT = "pref_recent_map"
    }
}
