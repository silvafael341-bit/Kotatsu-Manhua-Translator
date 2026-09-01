package org.koitharu.kotatsu.translation.data

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

/** Offline-capable translation client. Source language is selected without the optional ML Kit language-id module. */
class MlKitTranslateClient(
    private val targetLanguage: String = TranslateLanguage.PORTUGUESE,
) : AutoCloseable {

    private val translators = mutableMapOf<String, Translator>()

    suspend fun translate(text: String, sourceLanguage: String = "auto"): String {
        val clean = text.trim()
        if (clean.isBlank()) return ""

        val source = if (sourceLanguage == "auto") heuristicLanguage(clean)
        else TranslateLanguage.fromLanguageTag(sourceLanguage) ?: heuristicLanguage(clean)

        if (source == targetLanguage) return clean

        val key = "$source->$targetLanguage"
        val translator = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(targetLanguage)
                    .build(),
            )
        }

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
        return translator.translate(clean).awaitTask().trim()
    }

    private fun heuristicLanguage(text: String): String = when {
        text.any { it in '\u3040'..'\u30ff' } -> TranslateLanguage.JAPANESE
        text.any { it in '\uac00'..'\ud7af' } -> TranslateLanguage.KOREAN
        text.any { it in '\u3400'..'\u4dbf' || it in '\u4e00'..'\u9fff' } -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.ENGLISH
    }

    override fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }
}
