package org.koitharu.kotatsu.translation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import org.koitharu.kotatsu.translation.domain.TranslationRegion
import kotlin.math.max

class TranslationOverlayView(context: Context) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
    private var regions: List<TranslationRegion> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1

    fun setRegions(regions: List<TranslationRegion>, imageWidth: Int, imageHeight: Int) {
        this.regions = regions
        this.imageWidth = max(1, imageWidth)
        this.imageHeight = max(1, imageHeight)
        invalidate()
    }

    fun clear() {
        regions = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (regions.isEmpty()) return
        val sx = width.toFloat() / imageWidth
        val sy = height.toFloat() / imageHeight
        for (region in regions) {
            val r = region.bounds
            val rect = RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
            val pad = max(4f, rect.width() * 0.035f)
            bgPaint.color = android.graphics.Color.argb(235, 255, 255, 255)
            canvas.drawRoundRect(RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad), pad, pad, bgPaint)
            val available = max(10f, rect.width() - pad * 2)
            textPaint.textSize = fitTextSize(region.translated, available, max(12f, rect.height() * 0.85f))
            drawWrapped(canvas, region.translated, rect.left, rect.top + textPaint.textSize, available, textPaint)
        }
    }

    private fun fitTextSize(text: String, maxWidth: Float, maxHeight: Float): Float {
        var size = max(10f, maxHeight)
        while (size > 10f) {
            textPaint.textSize = size
            if (textPaint.measureText(text) <= maxWidth || text.length > 18) return size
            size -= 1f
        }
        return 10f
    }

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, baseline: Float, width: Float, paint: Paint) {
        val words = text.split(Regex("\\s+"))
        var line = StringBuilder()
        var y = baseline
        val lineHeight = paint.fontSpacing
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= width) {
                line = StringBuilder(candidate)
            } else {
                if (line.isNotEmpty()) {
                    canvas.drawText(line.toString(), x, y, paint)
                    y += lineHeight
                }
                line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) canvas.drawText(line.toString(), x, y, paint)
    }
}
