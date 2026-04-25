package com.nomkeyboard.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.nomkeyboard.app.R

/**
 * Self-drawn Gboard-style QWERTY keyboard (Vietnamese layout, which is identical to the
 * English QWERTY layout in terms of key placement).
 *
 * Features:
 *   - Four rows of QWERTY letters plus a symbol page (?123 and =\<).
 *     The candidate bar is rendered by a separate [CandidateBar] view above this one.
 *   - Shift: single tap for temporary uppercase, double tap for caps-lock.
 *   - Long-press on backspace repeats the delete action.
 *   - All events are delivered to the owner via [KeyActionListener].
 *
 * No XML layout is used; keys are fully drawn for a smoother Gboard-like feel.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // ----------- Public callback -----------
    interface KeyActionListener {
        fun onChar(ch: Char)
        fun onBackspace()
        fun onEnter()
        fun onSpace()
        fun onSymbol(text: String)          // any other symbol
        fun onSwitchLanguage()              // globe key
    }

    var listener: KeyActionListener? = null

    // ----------- Theme -----------
    private var theme: KeyboardTheme = KeyboardTheme.light(context)
    fun applyTheme(t: KeyboardTheme) {
        theme = t
        keyBgPaint.color = t.key
        keyFuncBgPaint.color = t.keyFunc
        keyPressPaint.color = t.press
        textPaint.color = t.text
        textFuncPaint.color = t.textFunc
        setBackgroundColor(t.bg)
        invalidate()
    }

    // ----------- Paints -----------
    private val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyFuncBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyPressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val textFuncPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Stroke paint used to highlight the Shift key when it is in the "temporary uppercase" or
    // "caps-lock" state. This gives immediate visual feedback so the user knows whether the
    // next letter will be capitalised.
    private val shiftActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // ----------- Dimensions -----------
    private val keyHeight = resources.getDimension(R.dimen.kb_key_height)
    private val keyGap = resources.getDimension(R.dimen.kb_key_gap)
    private val rowGap = resources.getDimension(R.dimen.kb_row_gap)
    private val keyRadius = resources.getDimension(R.dimen.kb_key_radius)
    private val keyTextSize = resources.getDimension(R.dimen.kb_key_text_size)
    private val keyLabelTextSize = resources.getDimension(R.dimen.kb_key_label_text_size)
    private val sidePad = resources.getDimension(R.dimen.kb_side_pad)
    private val topPad = resources.getDimension(R.dimen.kb_top_pad)
    private val bottomPad = resources.getDimension(R.dimen.kb_bottom_pad)

    // ----------- State -----------
    // 0: letters page ; 1: symbols page ?123 ; 2: symbols page =\<
    private var page = 0

    // shift: 0 off, 1 temporary (next letter only), 2 caps-lock
    private var shift = 0

    private var hapticsEnabled: Boolean = true
    fun setHapticsEnabled(v: Boolean) { hapticsEnabled = v }

    // ----------- Key model -----------
    /** Metadata for a single key. */
    private data class Key(
        val label: String,
        val code: KeyCode,
        val weight: Float = 1f,     // width weight within the row
        val secondary: String? = null,  // label for long-press popup (not drawn in MVP)
        val func: Boolean = false,      // function key (different background)
    )

    private enum class KeyCode {
        CHAR, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOL, SYMBOL2, LETTER,
        COMMA, PERIOD, LANGUAGE, QUESTION, EMOJI
    }

    // Letters page
    private val lettersRows: List<List<Key>> = listOf(
        "qwertyuiop".map { Key(it.toString(), KeyCode.CHAR) },
        "asdfghjkl".map { Key(it.toString(), KeyCode.CHAR) },
        listOf(Key("⇧", KeyCode.SHIFT, weight = 1.4f, func = true)) +
                "zxcvbnm".map { Key(it.toString(), KeyCode.CHAR) } +
                listOf(Key("⌫", KeyCode.BACKSPACE, weight = 1.4f, func = true)),
        listOf(
            Key("?123", KeyCode.SYMBOL, weight = 1.6f, func = true),
            Key(",", KeyCode.COMMA, func = false),
            Key("🌐", KeyCode.LANGUAGE, func = true),
            Key("space", KeyCode.SPACE, weight = 4.4f),
            Key(".", KeyCode.PERIOD, func = false),
            Key("⏎", KeyCode.ENTER, weight = 1.6f, func = true),
        ),
    )

    // Symbols page 1
    private val symbolsRows: List<List<Key>> = listOf(
        "1234567890".map { Key(it.toString(), KeyCode.CHAR) },
        "@#\$_&-+()/*".map { Key(it.toString(), KeyCode.CHAR) },
        listOf(Key("=\\<", KeyCode.SYMBOL2, weight = 1.4f, func = true)) +
                "\"':;!?".map { Key(it.toString(), KeyCode.CHAR) } +
                listOf(Key("⌫", KeyCode.BACKSPACE, weight = 1.4f, func = true)),
        listOf(
            Key("ABC", KeyCode.LETTER, weight = 1.6f, func = true),
            Key(",", KeyCode.COMMA, func = false),
            Key("🌐", KeyCode.LANGUAGE, func = true),
            Key("space", KeyCode.SPACE, weight = 4.4f),
            Key(".", KeyCode.PERIOD, func = false),
            Key("⏎", KeyCode.ENTER, weight = 1.6f, func = true),
        ),
    )

    // Symbols page 2
    private val symbols2Rows: List<List<Key>> = listOf(
        "~`|•√π÷×¶∆".map { Key(it.toString(), KeyCode.CHAR) },
        "£¥€¢^°=¤".map { Key(it.toString(), KeyCode.CHAR) },
        listOf(Key("?123", KeyCode.SYMBOL, weight = 1.4f, func = true)) +
                "\\/<>{}[]".map { Key(it.toString(), KeyCode.CHAR) } +
                listOf(Key("⌫", KeyCode.BACKSPACE, weight = 1.4f, func = true)),
        listOf(
            Key("ABC", KeyCode.LETTER, weight = 1.6f, func = true),
            Key(",", KeyCode.COMMA, func = false),
            Key("🌐", KeyCode.LANGUAGE, func = true),
            Key("space", KeyCode.SPACE, weight = 4.4f),
            Key(".", KeyCode.PERIOD, func = false),
            Key("⏎", KeyCode.ENTER, weight = 1.6f, func = true),
        ),
    )

    private fun currentRows(): List<List<Key>> = when (page) {
        1 -> symbolsRows
        2 -> symbols2Rows
        else -> lettersRows
    }

    // ----------- Layout cache -----------
    private data class KeyRect(val key: Key, val rect: RectF)
    private val keyRects: MutableList<KeyRect> = ArrayList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = currentRows().size
        val h = (topPad + bottomPad + keyHeight * rows + rowGap * (rows - 1)).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private fun layoutKeys() {
        keyRects.clear()
        val rows = currentRows()
        val w = width.toFloat()
        if (w <= 0) return
        val usableWidth = w - sidePad * 2
        var y = topPad
        for (row in rows) {
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            val totalGap = keyGap * (row.size - 1)
            val unit = (usableWidth - totalGap) / totalWeight
            var x = sidePad
            for (key in row) {
                val keyW = unit * key.weight
                val rect = RectF(x, y, x + keyW, y + keyHeight)
                keyRects.add(KeyRect(key, rect))
                x += keyW + keyGap
            }
            y += keyHeight + rowGap
        }
    }

    // ----------- Drawing -----------
    override fun onDraw(canvas: Canvas) {
        for (kr in keyRects) {
            drawKey(canvas, kr)
        }
    }

    private fun drawKey(canvas: Canvas, kr: KeyRect) {
        val k = kr.key
        val pressed = pressedKey === kr
        val bgPaint = when {
            pressed -> keyPressPaint
            k.func -> keyFuncBgPaint
            k.code == KeyCode.SPACE -> keyFuncBgPaint
            else -> keyBgPaint
        }
        canvas.drawRoundRect(kr.rect, keyRadius, keyRadius, bgPaint)

        // Text
        val paint = if (k.func) textFuncPaint else textPaint
        paint.textSize = when (k.code) {
            KeyCode.SHIFT, KeyCode.BACKSPACE, KeyCode.ENTER, KeyCode.LANGUAGE -> keyTextSize * 1.05f
            KeyCode.SPACE, KeyCode.SYMBOL, KeyCode.SYMBOL2, KeyCode.LETTER -> keyLabelTextSize * 1.1f
            else -> keyTextSize
        }

        // Adjust label for state-dependent keys.
        // Shift uses a single arrow in all states; its state is conveyed by filled/hollow styling
        // (see the stroke pass below), which matches Gboard and avoids the confusing "double arrow".
        val displayLabel = when (k.code) {
            KeyCode.SHIFT -> "⇧"
            KeyCode.CHAR -> if (page == 0 && shift > 0) k.label.uppercase() else k.label
            KeyCode.SPACE -> " "   // drawn as a thin indicator bar instead of text
            else -> k.label
        }

        val cx = (kr.rect.left + kr.rect.right) / 2f
        val textY = (kr.rect.top + kr.rect.bottom) / 2f - (paint.descent() + paint.ascent()) / 2f

        // When Shift is active (temporary) or locked, overlay an accent-coloured ring around the
        // Shift key and tint its glyph so the user gets an unambiguous visual cue.
        if (k.code == KeyCode.SHIFT && shift > 0) {
            shiftActivePaint.color = theme.accent
            shiftActivePaint.strokeWidth = if (shift == 2) 6f else 3f
            val inset = shiftActivePaint.strokeWidth / 2f
            val ringRect = RectF(
                kr.rect.left + inset, kr.rect.top + inset,
                kr.rect.right - inset, kr.rect.bottom - inset,
            )
            canvas.drawRoundRect(ringRect, keyRadius, keyRadius, shiftActivePaint)
            val originalColor = paint.color
            paint.color = theme.accent
            canvas.drawText(displayLabel, cx, textY, paint)
            paint.color = originalColor
        } else {
            canvas.drawText(displayLabel, cx, textY, paint)
        }

        // Draw a thin centred bar on the SPACE key (signature Gboard detail)
        if (k.code == KeyCode.SPACE) {
            dividerPaint.color = theme.divider
            val barW = kr.rect.width() * 0.3f
            val bar = RectF(
                cx - barW / 2, kr.rect.centerY() + 8f,
                cx + barW / 2, kr.rect.centerY() + 11f
            )
            canvas.drawRoundRect(bar, 2f, 2f, dividerPaint)
        }

        // Draw a small dot on top of the Caps-Lock-style Shift when shift == 2 so it reads as
        // "always on" in the same language as most soft keyboards (a filled dot below the arrow).
        if (k.code == KeyCode.SHIFT && shift == 2) {
            val dotR = 3f
            canvas.drawCircle(cx, kr.rect.bottom - dotR * 3f, dotR, paint)
        }
    }

    // ----------- Touch handling -----------
    private var pressedKey: KeyRect? = null
    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = findKey(x, y) ?: return true
                pressedKey = hit
                invalidate()
                if (hapticsEnabled) {
                    // Respect the user's global haptic-feedback preference; do not force-override it.
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                // Long-press repeat for backspace
                if (hit.key.code == KeyCode.BACKSPACE) {
                    repeatRunnable = object : Runnable {
                        override fun run() {
                            listener?.onBackspace()
                            handler.postDelayed(this, 60)
                        }
                    }
                    handler.postDelayed(repeatRunnable!!, 400)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Allow sliding to an adjacent key without lifting the finger.
                val cur = findKey(x, y)
                if (cur != null && cur !== pressedKey) {
                    pressedKey = cur
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeatRunnable?.let { handler.removeCallbacks(it) }
                repeatRunnable = null
                val hit = pressedKey
                pressedKey = null
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP && hit != null) {
                    handleKey(hit.key)
                }
            }
        }
        return true
    }

    /** Find the key under (x, y); if none, return the closest key on the same row within tolerance. */
    private fun findKey(x: Float, y: Float): KeyRect? {
        var best: KeyRect? = null
        var bestDx = Float.MAX_VALUE
        for (kr in keyRects) {
            if (kr.rect.contains(x, y)) return kr
            if (y >= kr.rect.top && y <= kr.rect.bottom) {
                val dx = when {
                    x < kr.rect.left -> kr.rect.left - x
                    x > kr.rect.right -> x - kr.rect.right
                    else -> 0f
                }
                if (dx < bestDx) {
                    best = kr
                    bestDx = dx
                }
            }
        }
        // Only accept the approximate match when it is within ~3 gaps
        return if (best != null && bestDx < keyGap * 3) best else null
    }

    // ----------- Key action dispatch -----------
    private fun handleKey(k: Key) {
        when (k.code) {
            KeyCode.CHAR -> {
                val raw = k.label[0]
                val ch = if (page == 0) {
                    if (shift > 0) raw.uppercaseChar() else raw
                } else raw
                listener?.onChar(ch)
                if (shift == 1) {
                    shift = 0
                    invalidate()
                }
            }
            KeyCode.SHIFT -> {
                shift = when (shift) {
                    0 -> 1
                    1 -> 2
                    else -> 0
                }
                invalidate()
            }
            KeyCode.BACKSPACE -> listener?.onBackspace()
            KeyCode.ENTER -> listener?.onEnter()
            KeyCode.SPACE -> {
                listener?.onSpace()
                if (shift == 1) {
                    shift = 0
                    invalidate()
                }
            }
            KeyCode.SYMBOL -> switchPage(1)
            KeyCode.SYMBOL2 -> switchPage(2)
            KeyCode.LETTER -> switchPage(0)
            KeyCode.COMMA -> listener?.onChar(',')
            KeyCode.PERIOD -> listener?.onChar('.')
            KeyCode.LANGUAGE -> listener?.onSwitchLanguage()
            else -> { /* ignore */ }
        }
    }

    fun resetShift() {
        if (shift != 0) {
            shift = 0
            invalidate()
        }
    }

    /**
     * Switch between the letters / ?123 / =\< pages.
     *
     * We explicitly rebuild the key layout and request a re-measure, because the new page may
     * contain a different number of rows (all three pages happen to have four rows here, but we
     * must still refresh `keyRects` so that the newly drawn keys are actually hit-testable).
     * Just calling `requestLayout()` is not enough: when the measured size does not change,
     * `onSizeChanged()` is never invoked and the old `keyRects` linger from the previous page,
     * causing tapped keys on the new page to do nothing.
     */
    private fun switchPage(newPage: Int) {
        if (page == newPage) return
        page = newPage
        pressedKey = null
        layoutKeys()
        requestLayout()
        invalidate()
    }
}
