package org.koitharu.translator.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
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

/**
 * OCR used by the ManhuaRM-style translator integration.
 *
 * We run the four script recognizers on the original image and on an enhanced
 * pass. Candidates are scored by confidence and script consistency so a CJK
 * recognizer cannot win merely because it returned more garbage characters.
 */
class OcrEngine : AutoCloseable {
    private data class Candidate(val block: TextBlock, val score: Float)
    private data class PreparedBitmap(val bitmap: Bitmap, val scale: Float)

    private val recognizers: List<Pair<String, TextRecognizer>> = listOf(
        "zh" to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
        "ja" to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
        "ko" to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
        "en" to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
    )

    suspend fun scan(bitmap: Bitmap): List<TextBlock> {
        if (bitmap.width < 2 || bitmap.height < 2) return emptyList()

        val first = scanBitmap(bitmap, 1f)
        val prepared = createEnhanced(bitmap)
        val second = try { scanBitmap(prepared.bitmap, prepared.scale) }
        finally { if (prepared.bitmap !== bitmap) prepared.bitmap.recycle() }

        return selectBestCandidates(first + second, bitmap.width, bitmap.height)
    }

    private suspend fun scanBitmap(bitmap: Bitmap, scale: Float): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        for (rotation in ROTATIONS) {
            val rotated = if (rotation == 0) bitmap else rotate(bitmap, rotation)
            try {
                for ((language, recognizer) in recognizers) {
                    runCatching {
                        recognizer.process(InputImage.fromBitmap(rotated, 0)).await()
                    }.onSuccess { result ->
                        collectLines(result, language, rotation, bitmap, scale, candidates)
                    }
                }
            } finally {
                if (rotation != 0 && rotated !== bitmap) rotated.recycle()
            }
        }
        return candidates
    }

    private fun collectLines(
        result: Text,
        language: String,
        rotation: Int,
        original: Bitmap,
        scale: Float,
        out: MutableList<Candidate>,
    ) {
        result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val text = normalize(line.text)
                val box = line.boundingBox ?: return@forEach
                if (!isUsefulText(text)) return@forEach
                val mapped = mapRect(box, rotation, original.width, original.height, scale)
                if (mapped.width() < 3 || mapped.height() < 3) return@forEach

                // Rotation is not a synonym for vertical text. The mapped box
                // tells us the orientation in original page coordinates.
                val vertical = mapped.height() > mapped.width() * 1.45f
                val confidence = runCatching { line.confidence ?: 0.5f }
                    .getOrDefault(0.5f).coerceIn(0f, 1f)
                val tb = TextBlock(text, mapped, confidence, language, vertical)
                out += Candidate(tb, qualityScore(tb))
            }
        }
    }

    private fun selectBestCandidates(candidates: List<Candidate>, width: Int, height: Int): List<TextBlock> {
        val result = mutableListOf<TextBlock>()
        for (candidate in candidates
            .filter { it.block.boundingBox.width() > 2 && it.block.boundingBox.height() > 2 }
            .sortedByDescending { it.score }) {
            val box = candidate.block.boundingBox
            if (box.left >= width || box.top >= height || box.right <= 0 || box.bottom <= 0) continue
            if (result.any { iou(it.boundingBox, box) >= IOU_DUPLICATE_THRESHOLD }) continue
            result += candidate.block
        }
        return result.sortedWith(compareBy<TextBlock> { it.boundingBox.top }.thenBy { it.boundingBox.left })
            .take(MAX_BLOCKS_PER_PAGE)
    }

    private fun qualityScore(block: TextBlock): Float {
        val text = block.text
        val nonSpace = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val letters = text.count { it.isLetter() || it.isDigit() }
        val cjk = text.count { it in '\u4e00'..'\u9fff' }
        val kana = text.count { it in '\u3040'..'\u30ff' }
        val hangul = text.count { it in '\uac00'..'\ud7af' }
        val latin = text.count { it.code < 0x0250 && (it.isLetter() || it.isDigit()) }
        val replacement = text.count { it == '\uFFFD' }
        val expected = when (block.language) {
            "zh" -> cjk + kana * 0.15f
            "ja" -> kana + cjk * 0.45f
            "ko" -> hangul
            else -> latin
        }
        val expectedRatio = expected / nonSpace.toFloat()
        val readableRatio = letters / nonSpace.toFloat()
        val garbagePenalty = replacement * 3f + (1f - readableRatio).coerceAtLeast(0f) * 2f
        val lengthBonus = min(2.5f, letters / 8f)
        return block.confidence * 4f + expectedRatio * 7f + lengthBonus - garbagePenalty
    }

    private fun normalize(value: String): String = value
        .replace('\u0000', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isUsefulText(text: String): Boolean {
        if (text.isBlank()) return false
        val letters = text.count { it.isLetter() || it.isDigit() }
        if (letters >= 2) return true
        return text.any { it in '\u4e00'..'\u9fff' || it in '\u3040'..'\u30ff' || it in '\uac00'..'\ud7af' }
    }

    private fun createEnhanced(source: Bitmap): PreparedBitmap {
        val scale = if (max(source.width, source.height) < 3000) 1.5f else 1f
        val width = (source.width * scale).toInt().coerceAtLeast(2)
        val height = (source.height * scale).toInt().coerceAtLeast(2)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix().apply {
            setSaturation(0.15f)
            val contrast = 1.55f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
            isFilterBitmap = true
        }
        Canvas(output).drawBitmap(source, null, Rect(0, 0, width, height), paint)
        return PreparedBitmap(output, scale)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap = Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height,
        Matrix().apply { postRotate(degrees.toFloat()) }, true,
    )

    private fun mapRect(rect: Rect, rotation: Int, originalWidth: Int, originalHeight: Int, scale: Float): Rect {
        val mapped = when (rotation) {
            90 -> Rect(rect.top, originalHeight - rect.right, rect.bottom, originalHeight - rect.left)
            270 -> Rect(originalWidth - rect.bottom, rect.left, originalWidth - rect.top, rect.right)
            else -> Rect(rect)
        }
        mapped.left = (mapped.left / scale).toInt().coerceIn(0, originalWidth)
        mapped.right = (mapped.right / scale).toInt().coerceIn(0, originalWidth)
        mapped.top = (mapped.top / scale).toInt().coerceIn(0, originalHeight)
        mapped.bottom = (mapped.bottom / scale).toInt().coerceIn(0, originalHeight)
        if (mapped.right <= mapped.left) mapped.right = min(originalWidth, mapped.left + 1)
        if (mapped.bottom <= mapped.top) mapped.bottom = min(originalHeight, mapped.top + 1)
        return mapped
    }

    private fun iou(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0, right - left) * max(0, bottom - top)
        if (intersection == 0) return 0f
        val areaA = max(1, a.width() * a.height())
        val areaB = max(1, b.width() * b.height())
        return intersection.toFloat() / (areaA + areaB - intersection).toFloat()
    }

    override fun close() { recognizers.forEach { it.second.close() } }

    companion object {
        private val ROTATIONS = intArrayOf(0, 90, 270)
        private const val IOU_DUPLICATE_THRESHOLD = 0.62f
        private const val MAX_BLOCKS_PER_PAGE = 250
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }
