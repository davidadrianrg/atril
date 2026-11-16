package com.davidadrianrg.atril.core.sync

/**
 * Estado real de la vigilancia de saves en tiempo real (M2/F11).
 *
 * En Android 15 un servicio en primer plano `dataSync` tiene un cupo de 6 h
 * al día; al agotarlo el sistema lo para (`onTimeout`) y no deja volver a
 * arrancarlo en segundo plano hasta que el usuario abre la app. Antes eso
 * ocurría en silencio; ahora la pantalla Sync muestra este estado.
 */
sealed interface SaveWatchState {
    /** No está en marcha (desactivada o detenida por el usuario). */
    data object Off : SaveWatchState

    /** Arrancando, aún sin carpetas calculadas. */
    data object Starting : SaveWatchState

    /** Vigilando [folders] carpetas. */
    data class Watching(val folders: Int) : SaveWatchState

    /** Parada por el sistema; se reanuda al abrir la app. */
    data class Paused(val reason: PauseReason, val atMillis: Long) : SaveWatchState

    enum class PauseReason {
        /** Cupo diario de los servicios `dataSync` agotado (Android 15+). */
        SYSTEM_TIME_LIMIT,

        /** Android no dejó iniciarla en segundo plano (arranque, worker…). */
        START_NOT_ALLOWED,
    }

    companion object {
        /**
         * ¿Hay que (re)arrancarla al volver la app a primer plano? Solo si el
         * usuario la quiere y no está ya en marcha.
         */
        fun shouldResumeOnForeground(
            watchEnabled: Boolean,
            syncEnabled: Boolean,
            state: SaveWatchState,
        ): Boolean = watchEnabled && syncEnabled && state !is Watching && state !is Starting

        /**
         * Clave i18n (y argumento opcional) que describe [state] en la UI.
         * Con la vigilancia desactivada no se muestra nada.
         */
        fun statusKey(watchEnabled: Boolean, state: SaveWatchState): Pair<String, Int?>? {
            if (!watchEnabled) return null
            return when (state) {
                SaveWatchState.Off -> "android.sync.watch.status.off" to null
                SaveWatchState.Starting -> "android.sync.watch.status.starting" to null
                is Watching -> "android.sync.watch.status.watching" to state.folders
                is Paused -> when (state.reason) {
                    PauseReason.SYSTEM_TIME_LIMIT -> "android.sync.watch.status.paused_limit" to null
                    PauseReason.START_NOT_ALLOWED -> "android.sync.watch.status.start_blocked" to null
                }
            }
        }
    }
}
