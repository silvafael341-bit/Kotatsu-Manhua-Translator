package org.koitharu.translator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.translator.ocr.OcrEngine
import org.koitharu.translator.ocr.TextRegionGrouper
import org.koitharu.translator.translate.TranslatorEngine
import org.koitharu.translator.ui.renderer.TranslatedPageRenderer
import kotlin.math.max

class TranslationCoordinator(
    private val context: Context,
    private val pageLoader: PageLoader,
) {
    private val grouper = TextRegionGrouper()
    private val renderer = TranslatedPageRenderer()

    suspend fun translatePage(page: MangaPage, targetLanguage: String = "pt-BR"): Result<Bitmap> =
        withContext(Dispatchers.Default) {
            runCatching {
                val downloaded = pageLoader.loadPage(page, force = false)
                val converted = pageLoader.convertBimap(downloaded)
                val original = decodeBitmap(converted)
                val blocks = OcrEngine().use { it.scan(original) }
                if (blocks.isEmpty()) throw IllegalStateException("Nenhum texto foi reconhecido nesta página.")
                val regions = grouper.group(blocks)
                if (regions.isEmpty()) throw IllegalStateException("Nenhuma região de texto foi encontrada.")
                val translations = TranslatorEngine().translateBatch(
                    regions.map { it.text }, targetLanguage, "auto",
                )
                renderer.render(original, regions, translations)
            }
        }

    private fun decodeBitmap(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInput(uri).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("Imagem inválida: ${uri.scheme ?: "sem esquema"}")
        }
        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, 3000, 6000)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openInput(uri).use { input ->
            BitmapFactory.decodeStream(input, null, options)
                ?: throw IllegalStateException("Falha ao decodificar a imagem.")
        }
    }

    private fun openInput(uri: Uri) = when (uri.scheme?.lowercase()) {
        "file" -> uri.toFile().inputStream()
        else -> context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Não foi possível abrir a imagem.")
    }

    private fun calculateSampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        var sample = 1
        while (width / sample > maxWidth || height / sample > maxHeight) sample *= 2
        return max(1, sample)
    }
}
