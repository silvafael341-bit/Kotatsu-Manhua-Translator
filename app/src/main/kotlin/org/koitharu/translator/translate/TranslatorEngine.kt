package org.koitharu.translator.translate

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TranslatorEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val cache = LruCache<String, String>(512)

    suspend fun translate(text: String, targetLang: String, srcLang: String = "auto"): String {
        val clean = text.trim()
        if (clean.isBlank()) return ""
        val key = "$srcLang|$targetLang|$clean"
        cache.get(key)?.let { return it }
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val result = withTimeout(20_000L) {
                    withContext(Dispatchers.IO) { request(clean, targetLang, srcLang) }
                }.trim()
                if (result.isNotBlank()) {
                    cache.put(key, result)
                    return result
                }
            } catch (error: Throwable) {
                lastError = error
                if (attempt < 2) delay((800L shl attempt).coerceAtMost(3_200L))
            }
        }
        throw IOException("Não foi possível traduzir o texto.", lastError)
    }

    suspend fun translateBatch(texts: List<String>, targetLang: String, srcLang: String = "auto"): List<String> {
        if (texts.isEmpty()) return emptyList()
        val results = ArrayList<String>(texts.size)
        for (text in texts) results += translate(text, targetLang, srcLang)
        return results
    }

    private fun request(text: String, targetLang: String, srcLang: String): String {
        val encoded = URLEncoder.encode(text, Charsets.UTF_8.name())
        val url = buildString {
            append("https://translate.googleapis.com/translate_a/single")
            append("?client=gtx")
            append("&sl=").append(URLEncoder.encode(srcLang, Charsets.UTF_8.name()))
            append("&tl=").append(URLEncoder.encode(targetLang, Charsets.UTF_8.name()))
            append("&dt=t&q=").append(encoded)
        }
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("Empty translation response")
            val segments = JSONArray(body).optJSONArray(0) ?: return text
            val out = StringBuilder()
            for (i in 0 until segments.length()) {
                val segment = segments.optJSONArray(i) ?: continue
                out.append(segment.optString(0))
            }
            return out.toString().trim().ifBlank { text }
        }
    }
}
