package com.davidadrianrg.atril.core.playstats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fachada observable de las estadísticas por juego: envuelve el almacén y el
 * cronómetro de sesiones y expone el mapa de estadísticas como [StateFlow].
 *
 * Es la pieza que comparten Android (ShelfViewModel) y escritorio
 * (DesktopAppState); lo único que cambia entre plataformas es dónde vive el
 * fichero. Las escrituras refrescan el flujo, de modo que tarjetas, detalles y
 * la fila «Seguir jugando» se recomponen solos.
 */
class PlayStatsRepository(
    private val store: PlayStatsStore,
    private val tracker: PlaySessionTracker = PlaySessionTracker(),
    private val log: (String) -> Unit = { System.err.println(it) },
) {

    private val _stats = MutableStateFlow(store.all().associateBy { it.romId })

    /** Estadísticas por romId, ya cargadas del almacén al construir. */
    val stats: StateFlow<Map<Int, PlayStats>> = _stats.asStateFlow()

    /** Invierte el favorito del grupo completo y devuelve el valor nuevo. */
    fun toggleFavorite(groupIds: List<Int>): Boolean {
        val ids = groupIds.distinct()
        val favorite = !PlayStatsLogic.aggregate(ids, _stats.value).favorite
        setFavorite(ids, favorite)
        return favorite
    }

    /** Escribe el mismo favorito en todos los ids del grupo (agregado exacto). */
    fun setFavorite(groupIds: List<Int>, favorite: Boolean) {
        groupIds.distinct().forEach { store.setFavorite(it, favorite) }
        refresh()
    }

    /** Agregado del grupo con las estadísticas actuales. */
    fun aggregate(groupIds: List<Int>): GamePlayStats = PlayStatsLogic.aggregate(groupIds, _stats.value)

    /** Los grupos jugados más recientemente (máximo [limit]). */
    fun recent(groups: List<List<Int>>, limit: Int = PlayStatsLogic.CONTINUE_LIMIT): List<GamePlayStats> =
        PlayStatsLogic.recent(groups, _stats.value, limit)

    /** Empieza a contar la partida de [romId]. */
    fun beginSession(romId: Int) {
        tracker.begin(romId)
    }

    /**
     * Cierra la partida en curso. Sin sesión abierta no hace nada (doble
     * onResume, ráfagas de foco). Una sesión demasiado corta se descarta con un
     * aviso; una válida se guarda y refresca [stats].
     */
    fun endSession(): PlaySessionTracker.CompletedSession? {
        if (!tracker.isRunning()) return null
        val session = tracker.end()
        if (session == null) {
            log("WARN playstats: session discarded (shorter than ${PlaySessionTracker.MIN_SESSION_MS / 1000} s)")
            return null
        }
        store.recordSession(session.romId, session.endedAt, session.durationMs)
        refresh()
        return session
    }

    private fun refresh() {
        _stats.value = store.all().associateBy { it.romId }
    }
}
