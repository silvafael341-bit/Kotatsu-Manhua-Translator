package org.koitharu.translator.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

class OcrEngine : AutoCloseable {
    private data class Candidate(val block: TextBlock, val score: Float)
    private val recognizers: List<Pair<String, TextRecognizer>> = listOf(
        "zh" to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
        "ja" to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
        "ko" to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
        "en" to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
    )

    suspend fun scan(bitmap: Bitmap): List<TextBlock> {
        if (bitmap.width < 2 || bitmap.height < 2) return emptyList()
        val candidates = mutableListOf<Candidate>()
        for (rotation in intArrayOf(0, 90, 270)) {
            val rotated = if (rotation == 0) bitmap else rotate(bitmap, rotation)
            try {
                for ((language, recognizer) in recognizers) {
                    runCatching { recognizer.process(InputImage.fromBitmap(rotated, 0)).await() }
                        .onSuccess { result ->
                            result.textBlocks.forEach { block ->
                                block.lines.forEach { line ->
                                    val text = line.text.trim()
                                    val box = line.boundingBox ?: return@forEach
                                    if (text.isEmpty()) return@forEach
                                    val mapped = mapRect(box, rotation, bitmap.width, bitmap.height)
                                    val vertical = rotation != 0 || mapped.height() > mapped.width() * 1.25f
                                    val confidence = runCatching { line.confidence ?: 0.5f }.getOrDefault(0.5f)
                                    val tb = TextBlock(text, mapped, confidence, language, vertical)
                                    candidates += Candidate(tb, tb.score() + scriptBonus(text, language))
                                }
                            }
                        }
                }
            } finally {
                if (rotation != 0 && rotated !== bitmap) rotated.recycle()
            }
        }
        val result = mutableListOf<TextBlock>()
        for (candidate in candidates.filter { it.block.boundingBox.width() > 2 && it.block.boundingBox.height() > 2 }
            .sortedByDescending { it.score }) {
            if (result.none { iou(it.boundingBox, candidate.block.boundingBox) >= 0.62f }) result += candidate.block
        }
        return result.sortedWith(compareBy<TextBlock> { it.boundingBox.top }.thenBy { it.boundingBox.left }).take(250)
    }

    private fun scriptBonus(text: String, language: String): Float {
        var cjk = 0; var kana = 0; var hangul = 0; var latin = 0
        for (ch in text) when {
            ch in '\u3040'..'\u30ff' -> kana++
            ch in '\uac00'..'\ud7af' -> hangul++
            ch in '\u4e00'..'\u9fff' -> cjk++
            ch.isLetter() && ch.code < 0x0250 -> latin++
        }
        val total = max(1, cjk + kana + hangul + latin)
        return when (language) {
            "ja" -> ((kana * 2 + cjk) / total.toFloat()).coerceAtMost(1f) * 2f
            "ko" -> (hangul / total.toFloat()).coerceAtMost(1f) * 2f
            "zh" -> (cjk / total.toFloat()).coerceAtMost(1f) * 2f
            else -> (latin / total.toFloat()).coerceAtMost(1f) * 2f
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap = Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height,
        Matrix().apply { postRotate(degrees.toFloat()) }, true,
    )

    private fun mapRect(rect: Rect, rotation: Int, w: Int, h: Int): Rect {
        val mapped = when (rotation) {
            90 -> Rect(rect.top, h - rect.right, rect.bottom, h - rect.left)
            270 -> Rect(w - rect.bottom, rect.left, w - rect.top, rect.right)
            else -> Rect(rect)
        }
        mapped.left = mapped.left.coerceIn(0, w); mapped.right = mapped.right.coerceIn(0, w)
        mapped.top = mapped.top.coerceIn(0, h); mapped.bottom = mapped.bottom.coerceIn(0, h)
        if (mapped.right <= mapped.left) mapped.right = min(w, mapped.left + 1)
        if (mapped.bottom <= mapped.top) mapped.bottom = min(h, mapped.top + 1)
        return mapped
    }

    private fun iou(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bot = min(a.bottom, b.bottom)
        val inter = max(0, r - l) * max(0, bot - t)
        if (inter == 0) return 0f
        val aa = max(1, a.width() * a.height()); val ab = max(1, b.width() * b.height())
        return inter.toFloat() / (aa + ab - inter).toFloat()
    }

    override fun close() = recognizers.forEach { it.second.close() }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }
