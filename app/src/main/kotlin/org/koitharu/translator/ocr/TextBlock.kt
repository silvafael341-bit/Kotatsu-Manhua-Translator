package org.koitharu.translator.ocr

import android.graphics.Rect

data class TextBlock(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float = 0.5f,
    val language: String = "unknown",
    val vertical: Boolean = false,
) {
    fun width(): Int = boundingBox.width()
    fun height(): Int = boundingBox.height()

    fun score(): Float {
        val chars = text.count { !it.isWhitespace() }.coerceAtMost(40)
        val area = (width().coerceAtLeast(1) * height().coerceAtLeast(1)).toFloat()
        val density = chars / area.coerceAtLeast(1f)
        return confidence.coerceIn(0f, 1f) * 3f + chars / 40f + density * 20f
    }
}

data class TextRegion(
    val blocks: List<TextBlock>,
    val boundingBox: Rect,
    val text: String,
    val vertical: Boolean,
)
