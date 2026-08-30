package org.koitharu.kotatsu.translation.domain

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.translation.data.GoogleTranslateClient

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

class ManhuarmTranslator(
    private val googleTranslate: GoogleTranslateClient = GoogleTranslateClient(),
) {
    suspend fun translatePage(bitmap: Bitmap, targetLanguage: String = "pt"): List<TranslationRegion> = withContext(Dispatchers.Default) {
        val recognitions = listOf(
            runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bitmap, 0)).awaitTask() }.getOrNull(),
            runCatching { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).process(InputImage.fromBitmap(bitmap, 0)).awaitTask() }.getOrNull(),
            runCatching { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()).process(InputImage.fromBitmap(bitmap, 0)).awaitTask() }.getOrNull(),
            runCatching { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()).process(InputImage.fromBitmap(bitmap, 0)).awaitTask() }.getOrNull(),
        ).filterNotNull()

        val best = recognitions.maxByOrNull { score(it) } ?: return@withContext emptyList()
        val lines = best.textBlocks.flatMap { it.lines }
            .mapNotNull { line ->
                val text = line.text.trim()
                if (text.length < 2) null else line.boundingBox?.let { it to text }
            }
            .take(80)

        lines.mapNotNull { (rect, text) ->
            runCatching {
                val translated = googleTranslate.translate(text, "auto", targetLanguage).trim()
                if (translated.isBlank()) null else TranslationRegion(Rect(rect), text, translated)
            }.getOrNull()
        }
    }

    private fun score(text: Text): Int {
        val chars = text.text.count { !it.isWhitespace() }
        val blocks = text.textBlocks.size
        return chars * 2 + blocks
    }
}
