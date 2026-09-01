package org.koitharu.kotatsu.translation.domain

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.koitharu.kotatsu.translation.data.MlKitTranslateClient

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

class ManhuarmTranslator(
    private val translator: MlKitTranslateClient = MlKitTranslateClient(),
) : AutoCloseable {

    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val chineseRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private val japaneseRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    private val koreanRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    suspend fun translatePage(
        bitmap: Bitmap,
        targetLanguage: String = "pt",
    ): List<TranslationRegion> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || bitmap.width <= 1 || bitmap.height <= 1) {
            return@withContext emptyList()
        }

        val image = InputImage.fromBitmap(bitmap, 0)
        val recognitions = listOf(
            runCatching { latinRecognizer.process(image).awaitTask() }.getOrNull(),
            runCatching { chineseRecognizer.process(image).awaitTask() }.getOrNull(),
            runCatching { japaneseRecognizer.process(image).awaitTask() }.getOrNull(),
            runCatching { koreanRecognizer.process(image).awaitTask() }.getOrNull(),
        ).filterNotNull()

        val best = recognitions.maxByOrNull(::score)
            ?: return@withContext emptyList()

        best.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val text = line.text.trim()
                if (text.length < 2) null else line.boundingBox?.let { Rect(it) to text }
            }
            .sortedWith(compareBy({ it.first.top }, { it.first.left }))
            .take(100)
            .mapNotNull { (rect, text) ->
                runCatching {
                    val translated = translator.translate(text, "auto").trim()
                    if (translated.isBlank()) null else TranslationRegion(rect, text, translated)
                }.getOrNull()
            }
    }

    private fun score(text: Text): Int {
        val chars = text.text.count { !it.isWhitespace() }
        val blocks = text.textBlocks.size
        val lines = text.textBlocks.sumOf { it.lines.size }
        return chars * 2 + blocks * 6 + lines * 2
    }

    override fun close() {
        runCatching { latinRecognizer.close() }
        runCatching { chineseRecognizer.close() }
        runCatching { japaneseRecognizer.close() }
        runCatching { koreanRecognizer.close() }
        translator.close()
    }
}
