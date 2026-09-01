package org.koitharu.translator

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Bridge between the Reader overflow menu and visible page holders. */
object TranslationBus {
    private val _requests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val requests = _requests.asSharedFlow()

    fun request() {
        _requests.tryEmit(Unit)
    }
}
