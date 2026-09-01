package org.koitharu.kotatsu.translation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max

class TranslationOverlayView(context: Context) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        isSubpixelText = true
    }

    private data class DrawRegion(val rect: RectF, val text: String)
    private var regions: List<DrawRegion> = emptyList()

    fun setViewRegions(regions: List<Pair<RectF, String>>) {
        this.regions = regions.map { DrawRegion(RectF(it.first), it.second) }
        invalidate()
    }

    fun clear() {
        regions = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (region in regions) {
            val rect = region.rect
            if (rect.width() < 4f || rect.height() < 4f) continue

            val pad = max(5f, minOf(rect.width(), rect.height()) * 0.08f)
            bgPaint.color = android.graphics.Color.argb(242, 255, 255, 255)
            canvas.drawRoundRect(
                RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad),
                pad,
                pad,
                bgPaint,
            )

            val availableWidth = max(12f, rect.width() - pad * 2f)
            val availableHeight = max(14f, rect.height() - pad * 2f)
            textPaint.textSize = fitTextSize(region.text, availableWidth, availableHeight)
            drawCenteredWrapped(canvas, region.text, rect, availableWidth, textPaint)
        }
    }

    private fun fitTextSize(text: String, maxWidth: Float, maxHeight: Float): Float {
        var size = max(10f, minOf(maxHeight, 42f))
        while (size > 10f) {
            textPaint.textSize = size
            val longest = text.split(Regex("\\s+"))
                .maxOfOrNull { textPaint.measureText(it) } ?: 0f
            if (longest <= maxWidth) return size
            size -= 1f
        }
        return 10f
    }

    private fun drawCenteredWrapped(
        canvas: Canvas,
        text: String,
        rect: RectF,
        width: Float,
        paint: Paint,
    ) {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return

        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= width) {
                line = candidate
            } else {
                if (line.isNotEmpty()) lines += line
                line = word
            }
        }
        if (line.isNotEmpty()) lines += line

        val lineHeight = paint.fontSpacing
        val totalHeight = lines.size * lineHeight
        var y = rect.centerY() - totalHeight / 2f - paint.ascent()
        for (current in lines) {
            val x = rect.centerX() - paint.measureText(current) / 2f
            canvas.drawText(current, x, y, paint)
            y += lineHeight
        }
    }
}
