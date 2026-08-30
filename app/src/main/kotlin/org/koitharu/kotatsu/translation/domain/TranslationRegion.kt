package org.koitharu.kotatsu.translation.domain

import android.graphics.Rect

data class TranslationRegion(
    val bounds: Rect,
    val original: String,
    val translated: String,
)
