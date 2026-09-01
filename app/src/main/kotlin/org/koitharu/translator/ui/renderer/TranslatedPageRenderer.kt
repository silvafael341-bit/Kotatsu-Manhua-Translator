package org.koitharu.translator.ui.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import org.koitharu.translator.ocr.TextRegion
import kotlin.math.max
import kotlin.math.min

class TranslatedPageRenderer {
    fun render(original: Bitmap, regions: List<TextRegion>, translations: List<String>): Bitmap {
        val output = original.copy(Bitmap.Config.ARGB_8888, true)
        val count = min(regions.size, translations.size)
        for (index in 0 until count) {
            val translation = translations[index].trim()
            if (translation.isBlank()) continue
            val region = regions[index]
            val textBounds = Rect(region.boundingBox).apply { intersect(Rect(0, 0, output.width, output.height)) }
            if (textBounds.width() < 3 || textBounds.height() < 3) continue
            val margin = (min(textBounds.width(), textBounds.height()) * 0.16f).toInt().coerceIn(4, 42)
            val cleanRect = Rect(
                max(0, textBounds.left - margin), max(0, textBounds.top - margin),
                min(output.width, textBounds.right + margin), min(output.height, textBounds.bottom + margin),
            )
            val background = estimateBackground(output, textBounds, cleanRect)
            eraseTextArea(output, cleanRect, background)
            drawText(output, translation, cleanRect, background)
        }
        return output
    }

    private fun eraseTextArea(bitmap: Bitmap, rect: Rect, background: Int) {
        val edge = max(2, min(rect.width(), rect.height()) / 24)
        val inset = Rect(rect.left + edge, rect.top + edge, rect.right - edge, rect.bottom - edge)
        if (inset.width() <= 2 || inset.height() <= 2) return
        Canvas(bitmap).drawRect(inset, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
    }

    private fun estimateBackground(bitmap: Bitmap, textRect: Rect, cleanRect: Rect): Int {
        val samples = ArrayList<Int>(128)
        val ring = max(2, min(cleanRect.width(), cleanRect.height()) / 14)
        fun add(x: Int, y: Int) { if (x in 0 until bitmap.width && y in 0 until bitmap.height) samples += bitmap.getPixel(x, y) }
        val xs = max(1, textRect.width() / 8); val ys = max(1, textRect.height() / 8)
        for (x in textRect.left..textRect.right step xs) { add(x, textRect.top - ring); add(x, textRect.bottom + ring) }
        for (y in textRect.top..textRect.bottom step ys) { add(textRect.left - ring, y); add(textRect.right + ring, y) }
        if (samples.isEmpty()) {
            add(cleanRect.left, cleanRect.top); add(cleanRect.right - 1, cleanRect.top)
            add(cleanRect.left, cleanRect.bottom - 1); add(cleanRect.right - 1, cleanRect.bottom - 1)
        }
        if (samples.isEmpty()) return Color.WHITE
        val rs = samples.map(Color::red).sorted(); val gs = samples.map(Color::green).sorted(); val bs = samples.map(Color::blue).sorted(); val mid = samples.size / 2
        return Color.rgb(rs[mid], gs[mid], bs[mid])
    }

    private fun drawText(bitmap: Bitmap, text: String, rect: Rect, background: Int) {
        val inset = Rect(rect.left + 6, rect.top + 6, rect.right - 6, rect.bottom - 6)
        if (inset.width() <= 6 || inset.height() <= 6) return
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
        var size = (inset.height() * 0.42f).coerceIn(11f, 72f)
        var lines: List<String>
        do {
            paint.textSize = size
            lines = breakLines(text, paint, inset.width())
            val lineHeight = size * 1.16f
            if (lines.size * lineHeight <= inset.height() || size <= 8.5f) break
            size *= 0.88f
        } while (true)
        val lineHeight = size * 1.16f
        val totalHeight = lines.size * lineHeight
        var baseline = inset.top + (inset.height() - totalHeight) / 2f - paint.fontMetrics.ascent
        val canvas = Canvas(bitmap)
        for (line in lines) { canvas.drawText(line, inset.centerX().toFloat(), baseline, paint); baseline += lineHeight }
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
        flush(); return lines.ifEmpty { listOf("") }
    }
}
