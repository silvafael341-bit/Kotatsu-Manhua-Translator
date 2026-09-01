package org.koitharu.kotatsu.reader.ui.pager.standard

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setMargins
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.ui.widgets.ZoomControl
import org.koitharu.kotatsu.databinding.ItemPageBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.BasePageHolder
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.vm.PageState
import org.koitharu.kotatsu.translation.domain.ManhuarmTranslator
import org.koitharu.kotatsu.translation.domain.TranslationRegion

open class PageHolder(
    owner: LifecycleOwner,
    binding: ItemPageBinding,
    loader: PageLoader,
    readerSettingsProducer: ReaderSettings.Producer,
    networkState: NetworkState,
    exceptionResolver: ExceptionResolver,
) : BasePageHolder<ItemPageBinding>(
    binding = binding,
    loader = loader,
    readerSettingsProducer = readerSettingsProducer,
    networkState = networkState,
    exceptionResolver = exceptionResolver,
    lifecycleOwner = owner,
), ZoomControl.ZoomControlListener, OnApplyWindowInsetsListener {

    override val ssiv = binding.ssiv
    private var translationRegions: List<TranslationRegion> = emptyList()
    private var translationSourceWidth = 1
    private var translationSourceHeight = 1

    init {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, this)
        ssiv.setOnStateChangedListener(object : SubsamplingScaleImageView.DefaultOnStateChangedListener() {
            override fun onScaleChanged(newScale: Float, origin: Int) {
                updateTranslationOverlay()
            }
            override fun onCenterChanged(newCenter: PointF, origin: Int) {
                updateTranslationOverlay()
            }
        })
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            insets.toWindowInsets()?.let { applyRoundedCorners(it) }
        }
        return insets
    }

    override fun onConfigChanged(settings: ReaderSettings) {
        super.onConfigChanged(settings)
        binding.textViewNumber.isVisible = settings.isPagesNumbersEnabled
    }

    @SuppressLint("SetTextI18n")
    override fun onBind(data: ReaderPage) {
        super.onBind(data)
        binding.textViewNumber.text = (data.index + 1).toString()
        clearTranslation()
    }

    override fun onReady() {
        binding.ssiv.maxScale = 2f * maxOf(
            binding.ssiv.width / binding.ssiv.sWidth.toFloat(),
            binding.ssiv.height / binding.ssiv.sHeight.toFloat(),
        )
        binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
        when (settings.zoomMode) {
            ZoomMode.FIT_CENTER -> {
                binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                binding.ssiv.resetScaleAndCenter()
            }
            ZoomMode.FIT_HEIGHT -> {
                binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
                binding.ssiv.minScale = binding.ssiv.height / binding.ssiv.sHeight.toFloat()
                binding.ssiv.setScaleAndCenter(binding.ssiv.minScale, PointF(0f, binding.ssiv.sHeight / 2f))
            }
            ZoomMode.FIT_WIDTH -> {
                binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
                binding.ssiv.minScale = binding.ssiv.width / binding.ssiv.sWidth.toFloat()
                binding.ssiv.setScaleAndCenter(binding.ssiv.minScale, PointF(binding.ssiv.sWidth / 2f, 0f))
            }
            ZoomMode.KEEP_START -> {
                binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                binding.ssiv.setScaleAndCenter(binding.ssiv.maxScale, PointF(0f, 0f))
            }
        }
        updateTranslationOverlay()
    }

    suspend fun translateCurrentPage(translator: ManhuarmTranslator): Boolean = withContext(Dispatchers.IO) {
        val state = viewModel.state.value as? PageState.Shown ?: return@withContext false
        val source = state.source
        val original = source.bitmap ?: source.uri?.let { uri ->
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                val sample = calculateSample(bounds.outWidth, bounds.outHeight)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
            }
        } ?: return@withContext false

        val sourceRegion = source.sRegion
        val decoded = if (sourceRegion != null) {
            val fullScale = original.width.toFloat() / source.sWidth.coerceAtLeast(1)
            val left = (sourceRegion.left * fullScale).toInt().coerceIn(0, original.width - 1)
            val top = (sourceRegion.top * fullScale).toInt().coerceIn(0, original.height - 1)
            val width = (sourceRegion.width() * fullScale).toInt().coerceIn(1, original.width - left)
            val height = (sourceRegion.height() * fullScale).toInt().coerceIn(1, original.height - top)
            Bitmap.createBitmap(original, left, top, width, height)
        } else original

        try {
            val regions = translator.translatePage(decoded, "pt")
            translationRegions = regions
            translationSourceWidth = source.sWidth.coerceAtLeast(1)
            translationSourceHeight = source.sHeight.coerceAtLeast(1)
            post { updateTranslationOverlay() }
            regions.isNotEmpty()
        } finally {
            if (decoded !== original && !decoded.isRecycled) decoded.recycle()
            if (original !== source.bitmap && !original.isRecycled) original.recycle()
        }
    }

    private fun updateTranslationOverlay() {
        if (!ssiv.isReady || translationRegions.isEmpty()) {
            binding.translationOverlay.clear()
            return
        }
        val sx = translationSourceWidth.toFloat() / ssiv.sWidth.coerceAtLeast(1)
        val sy = translationSourceHeight.toFloat() / ssiv.sHeight.coerceAtLeast(1)
        val drawRegions = translationRegions.map { region ->
            val r = region.bounds
            val tl = ssiv.sourceToViewCoord(r.left * sx, r.top * sy)
            val br = ssiv.sourceToViewCoord(r.right * sx, r.bottom * sy)
            RectF(tl.x, tl.y, br.x, br.y) to region.translated
        }
        binding.translationOverlay.setViewRegions(drawRegions)
    }

    fun clearTranslation() {
        translationRegions = emptyList()
        binding.translationOverlay.clear()
    }

    private fun calculateSample(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width / sample, height / sample) > 2400) sample *= 2
        return sample
    }

    override fun onRecycled() {
        clearTranslation()
        super.onRecycled()
    }

    override fun onZoomIn() = scaleBy(1.2f)
    override fun onZoomOut() = scaleBy(0.8f)

    @SuppressLint("RtlHardcoded")
    @RequiresApi(Build.VERSION_CODES.S)
    protected open fun applyRoundedCorners(insets: WindowInsets) {
        binding.textViewNumber.updateLayoutParams<FrameLayout.LayoutParams> {
            val baseMargin = context.resources.getDimensionPixelOffset(R.dimen.margin_small)
            val absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection)
            val corner = when {
                absoluteGravity and Gravity.LEFT == Gravity.LEFT -> insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                absoluteGravity and Gravity.RIGHT == Gravity.RIGHT -> insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
                else -> null
            }
            setMargins(baseMargin + (corner?.radius ?: 0))
        }
    }

    private fun scaleBy(factor: Float) {
        val center = ssiv.getCenter() ?: return
        val newScale = ssiv.scale * factor
        ssiv.animateScaleAndCenter(newScale, center)?.apply {
            withDuration(ssiv.resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
            withInterpolator(DecelerateInterpolator())
            start()
        }
    }
}
