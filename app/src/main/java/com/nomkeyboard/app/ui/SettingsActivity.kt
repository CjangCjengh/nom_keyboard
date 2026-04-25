package com.nomkeyboard.app.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.nomkeyboard.app.NomInputMethodService
import com.nomkeyboard.app.R

class SettingsActivity : AppCompatActivity() {

    // Cache the loaded Han-Nom typeface once so that toggling the preference back and forth
    // doesn't hit disk every time.
    private var nomTypeface: Typeface? = null
    private lateinit var etTest: EditText

    // Kept as a field so we can unregister in onDestroy and avoid leaking the activity.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_USE_NOM_FONT) applyTestInputTypeface()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_choose).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .remove(NomInputMethodService.PREF_RECENT)
                .apply()
            Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_user_dict).setOnClickListener {
            startActivity(Intent(this, UserDictionaryActivity::class.java))
        }

        // Preload the bundled Han-Nom font; if the asset is missing we silently degrade to
        // the system typeface.
        nomTypeface = try {
            Typeface.createFromAsset(assets, "fonts/HanNomGothic.ttf")
        } catch (_: Throwable) {
            null
        }

        // Focus the test EditText so the keyboard pops up for quick trial.
        etTest = findViewById(R.id.et_test)
        applyTestInputTypeface()
        etTest.requestFocus()

        // React to the "use bundled Nom font" preference being toggled on the same screen.
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefListener)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    /**
     * Apply the current preference: bundled Han-Nom font (default) or system default.
     */
    private fun applyTestInputTypeface() {
        if (!::etTest.isInitialized) return
        val useNom = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PREF_USE_NOM_FONT, true)
        etTest.typeface = if (useNom && nomTypeface != null) nomTypeface else Typeface.DEFAULT
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
        }
    }

    companion object {
        const val PREF_USE_NOM_FONT = "pref_use_nom_font"
    }
}
