package com.nomkeyboard.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.nomkeyboard.app.R
import kotlin.math.max

/**
 * Horizontal scrollable candidate bar. Displays the currently composing Vietnamese text
 * on the left and the matching Nom character suggestions on the right. Designed after
 * the Gboard candidate strip. The internal Nom font is used to guarantee correct glyphs.
 */
class CandidateBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    interface OnCandidatePickListener {
        fun onPickCandidate(index: Int, text: String)
    }

    var listener: OnCandidatePickListener? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.kb_candidate_text_size)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = resources.getDimension(R.dimen.kb_candidate_label_text_size)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var theme: KeyboardTheme = KeyboardTheme.light(context)
    fun applyTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.candidateBg)
        textPaint.color = t.candidateText
        labelPaint.color = t.text
        dividerPaint.color = t.divider
        highlightPaint.color = t.press
        invalidate()
    }

    fun setTypeface(tf: Typeface?) {
        if (tf != null) textPaint.typeface = tf
        invalidate()
    }

    // The current composing string (rendered on the left in the default font)
    private var composing: String = ""

    // Candidate list
    private var candidates: List<String> = emptyList()
    private val candidateRects = ArrayList<RectF>()

    private var scrollX = 0f
    private var maxScrollX = 0f

    private var pressedIndex = -1

    fun setComposing(text: String) {
        if (composing != text) {
            composing = text
            scrollX = 0f
            invalidate()
            requestLayout()
        }
    }

    fun setCandidates(list: List<String>) {
        candidates = list
        scrollX = 0f
        layoutCandidates()
        invalidate()
    }

    fun clear() {
        composing = ""
        candidates = emptyList()
        scrollX = 0f
        layoutCandidates()
        invalidate()
    }

    private val padH = 24f    // horizontal padding inside each candidate cell
    private val gap = 8f      // gap between cells

    private fun layoutCandidates() {
        candidateRects.clear()
        val h = height.toFloat()
        if (h <= 0) return
        var x = 0f
        // Left-side area reserved for the composing label
        if (composing.isNotEmpty()) {
            val w = labelPaint.measureText(composing) + padH * 2
            x += w
        }
        for (c in candidates) {
            val tw = max(textPaint.measureText(c), 28f) + padH * 2
            candidateRects.add(RectF(x, 0f, x + tw, h))
            x += tw + gap
        }
        maxScrollX = max(0f, x - width)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutCandidates()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        canvas.save()
        canvas.translate(-scrollX, 0f)

        // Composing area (left)
        if (composing.isNotEmpty()) {
            val w = labelPaint.measureText(composing) + padH * 2
            val rect = RectF(0f, 0f, w, h)
            val y = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(composing, padH, y, labelPaint)
            // Divider on the right edge of the composing area
            canvas.drawRect(w - 1f, h * 0.25f, w, h * 0.75f, dividerPaint)
        }

        // Candidates
        for ((i, rect) in candidateRects.withIndex()) {
            if (i == pressedIndex) {
                canvas.drawRoundRect(rect, 8f, 8f, highlightPaint)
            }
            val txt = candidates[i]
            val cx = rect.centerX()
            val cy = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(txt, cx, cy, textPaint)
            if (i < candidateRects.size - 1) {
                val x = rect.right + gap / 2
                canvas.drawRect(x - 0.5f, h * 0.25f, x + 0.5f, h * 0.75f, dividerPaint)
            }
        }
        canvas.restore()
    }

    private var downX = 0f
    private var downIndex = -1
    private var dragged = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x + scrollX
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                dragged = false
                downIndex = findCandidate(x)
                pressedIndex = downIndex
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (!dragged && kotlin.math.abs(dx) > 16f) dragged = true
                if (dragged) {
                    scrollX = (scrollX - dx).coerceIn(0f, maxScrollX)
                    downX = event.x
                    pressedIndex = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged && downIndex >= 0 && downIndex < candidates.size) {
                    listener?.onPickCandidate(downIndex, candidates[downIndex])
                }
                pressedIndex = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
            }
        }
        return true
    }

    private fun findCandidate(xInContent: Float): Int {
        for ((i, rect) in candidateRects.withIndex()) {
            if (xInContent >= rect.left && xInContent <= rect.right) return i
        }
        return -1
    }
}
