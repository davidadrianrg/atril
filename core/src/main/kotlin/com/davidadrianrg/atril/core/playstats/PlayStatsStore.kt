package com.davidadrianrg.atril.core.playstats

/**
 * Persistencia de las estadísticas por juego. Es la costura que sustituyen las
 * pruebas y el único punto de escritura que comparten Android y escritorio: un
 * mismo formato para ambas plataformas.
 *
 * Los problemas operativos (fichero ilegible, escritura fallida) los resuelve la
 * implementación y nunca se propagan; solo un uso incorrecto del API
 * ([recordSession] con duración negativa) lanza excepción.
 */
interface PlayStatsStore {
    /** Todos los registros guardados. */
    fun all(): List<PlayStats>

    /** El registro de [romId], o null si ese juego no tiene datos. */
    fun get(romId: Int): PlayStats?

    /** Marca [romId] como favorito (o deja de estarlo) y devuelve el registro. */
    fun setFavorite(romId: Int, favorite: Boolean): PlayStats

    /**
     * Suma [durationMs] al tiempo jugado y actualiza la última partida a
     * [endedAt] (nunca retrocede). Crea el registro si no existía.
     *
     * @throws IllegalArgumentException si [durationMs] es negativo (error de programación).
     */
    fun recordSession(romId: Int, endedAt: Long, durationMs: Long): PlayStats
}
