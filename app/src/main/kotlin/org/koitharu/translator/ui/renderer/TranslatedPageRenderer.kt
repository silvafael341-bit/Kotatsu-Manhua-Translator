package org.koitharu.translator.ui.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import org.koitharu.translator.ocr.TextRegion
import kotlin.math.max
import kotlin.math.min

/** Adaptive ManhuaRM-style cleanup and translation rendering. */
class TranslatedPageRenderer {
    private data class BackgroundInfo(val color: Int, val variance: Float)

    fun render(original: Bitmap, regions: List<TextRegion>, translations: List<String>): Bitmap {
        val output = original.copy(Bitmap.Config.ARGB_8888, true)
        val count = min(regions.size, translations.size)
        for (index in 0 until count) {
            val translation = translations[index].trim()
            if (translation.isBlank()) continue
            val region = regions[index]
            val textBounds = Rect(region.boundingBox).apply {
                intersect(Rect(0, 0, output.width, output.height))
            }
            if (textBounds.width() < 3 || textBounds.height() < 3) continue

            val background = estimateBackground(output, textBounds)
            val margin = computeMargin(textBounds, background.variance)
            val cleanRect = expandSafely(textBounds, margin, output.width, output.height)
            eraseTextArea(output, cleanRect, background)
            drawText(output, translation, cleanRect, background.color)
        }
        return output
    }

    private fun computeMargin(rect: Rect, variance: Float): Int {
        val base = min(rect.width(), rect.height()).coerceAtLeast(4)
        val factor = if (variance < UNIFORM_VARIANCE) 0.42f else 0.22f
        return (base * factor).toInt().coerceIn(6, 96)
    }

    private fun expandSafely(rect: Rect, margin: Int, width: Int, height: Int) = Rect(
        max(0, rect.left - margin), max(0, rect.top - margin),
        min(width, rect.right + margin), min(height, rect.bottom + margin),
    )

    private fun eraseTextArea(bitmap: Bitmap, rect: Rect, background: BackgroundInfo) {
        val edge = if (background.variance < UNIFORM_VARIANCE) 1 else max(2, min(rect.width(), rect.height()) / 24)
        val inset = RectF(
            (rect.left + edge).toFloat(), (rect.top + edge).toFloat(),
            (rect.right - edge).toFloat(), (rect.bottom - edge).toFloat(),
        )
        if (inset.width() <= 2f || inset.height() <= 2f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background.color; style = Paint.Style.FILL }
        val radius = min(inset.width(), inset.height()) * if (background.variance < UNIFORM_VARIANCE) 0.28f else 0.12f
        Canvas(bitmap).drawRoundRect(inset, radius, radius, paint)
    }

    private fun estimateBackground(bitmap: Bitmap, textRect: Rect): BackgroundInfo {
        val samples = ArrayList<Int>(192)
        val ring = max(3, min(textRect.width(), textRect.height()) / 10)
        fun add(x: Int, y: Int) {
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) samples += bitmap.getPixel(x, y)
        }
        val xs = max(1, textRect.width() / 10)
        val ys = max(1, textRect.height() / 10)
        for (x in textRect.left..textRect.right step xs) {
            add(x, textRect.top - ring); add(x, textRect.bottom + ring)
        }
        for (y in textRect.top..textRect.bottom step ys) {
            add(textRect.left - ring, y); add(textRect.right + ring, y)
        }
        if (samples.isEmpty()) return BackgroundInfo(Color.WHITE, 999f)

        val rs = samples.map(Color::red).sorted(); val gs = samples.map(Color::green).sorted(); val bs = samples.map(Color::blue).sorted()
        val mid = samples.size / 2
        val color = Color.rgb(rs[mid], gs[mid], bs[mid])
        val mean = samples.map { 0.299f * Color.red(it) + 0.587f * Color.green(it) + 0.114f * Color.blue(it) }.average().toFloat()
        val variance = samples.map {
            val lum = 0.299f * Color.red(it) + 0.587f * Color.green(it) + 0.114f * Color.blue(it)
            (lum - mean) * (lum - mean)
        }.average().toFloat()
        return BackgroundInfo(color, variance)
    }

    private fun drawText(bitmap: Bitmap, text: String, rect: Rect, background: Int) {
        val inset = Rect(rect.left + 8, rect.top + 8, rect.right - 8, rect.bottom - 8)
        if (inset.width() <= 8 || inset.height() <= 8) return
        val luminance = 0.299 * Color.red(background) + 0.587 * Color.green(background) + 0.114 * Color.blue(background)
        val light = luminance >= 150.0
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (light) Color.rgb(25, 25, 25) else Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
            isDither = true
            setShadowLayer(1.5f, 0f, 0f, if (light) Color.WHITE else Color.BLACK)
        }
        var size = (inset.height() * 0.36f).coerceIn(11f, 72f)
        var lines: List<String>
        do {
            paint.textSize = size
            lines = breakLines(text, paint, inset.width())
            val lineHeight = size * 1.14f
            if (lines.size * lineHeight <= inset.height() || size <= 9f) break
            size *= 0.88f
        } while (true)
        val lineHeight = size * 1.14f
        val totalHeight = lines.size * lineHeight
        var baseline = inset.top + (inset.height() - totalHeight) / 2f - paint.fontMetrics.ascent
        val canvas = Canvas(bitmap)
        for (line in lines) {
            canvas.drawText(line, inset.centerX().toFloat(), baseline, paint)
            baseline += lineHeight
        }
    }

    private fun breakLines(text: String, paint: Paint, width: Int): List<String> {
        val lines = mutableListOf<String>(); var current = StringBuilder()
        fun flush() { if (current.isNotEmpty()) { lines += current.toString(); current = StringBuilder() } }
        for (ch in text.replace('\r', ' ')) {
            if (ch == '\n') { flush(); continue }
            val candidate = current.toString() + ch
            if (current.isNotEmpty() && paint.measureText(candidate) > width) flush()
            current.append(ch)
        }
        flush()
        return lines.ifEmpty { listOf("") }
    }

    companion object { private const val UNIFORM_VARIANCE = 350f }
}
