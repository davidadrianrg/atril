package com.davidadrianrg.atril.data.sync

import com.davidadrianrg.atril.core.sync.SaveWatchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado del proceso para la vigilancia de saves: lo escribe
 * [SaveSyncWatchService] (y su arranque) y lo lee la pantalla Sync.
 */
object SaveWatchStatus {
    private val _state = MutableStateFlow<SaveWatchState>(SaveWatchState.Off)
    val state: StateFlow<SaveWatchState> = _state.asStateFlow()

    private val _lastSystemPauseAt = MutableStateFlow<Long?>(null)

    /** Última vez que Android la paró por el cupo diario (en esta ejecución). */
    val lastSystemPauseAt: StateFlow<Long?> = _lastSystemPauseAt.asStateFlow()

    fun update(state: SaveWatchState) {
        if (state is SaveWatchState.Paused &&
            state.reason == SaveWatchState.PauseReason.SYSTEM_TIME_LIMIT
        ) {
            _lastSystemPauseAt.value = state.atMillis
        }
        _state.value = state
    }

    /** El servicio se destruye: una pausa del sistema se conserva para explicarla. */
    fun onServiceDestroyed() {
        if (_state.value !is SaveWatchState.Paused) _state.value = SaveWatchState.Off
    }
}
