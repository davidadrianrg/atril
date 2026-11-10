package com.davidadrianrg.atril.core.playstats

/**
 * Estadísticas locales de un juego concreto: si es favorito, cuándo se jugó por
 * última vez (epoch ms) y cuánto tiempo acumulado lleva jugado.
 *
 * Hay un registro por `romId` (el id de RomM o el id sintético negativo de un
 * juego local). En un juego multidisco cada disco lanzado tiene su propio
 * registro; la UI muestra el agregado del grupo ([GamePlayStats]).
 */
data class PlayStats(
    val romId: Int,
    val favorite: Boolean = false,
    /** Fin de la última sesión conservada; null = nunca se ha jugado. */
    val lastPlayedAt: Long? = null,
    /** Suma de las sesiones conservadas (las de menos de 5 s se descartan). */
    val totalPlayedMs: Long = 0L,
)

/**
 * Agregado de las estadísticas de un grupo de ids (discos o variantes del mismo
 * juego): favorito si lo es cualquiera, última partida la más reciente y tiempo
 * jugado la suma. Es el valor que pintan las tarjetas y los detalles.
 */
data class GamePlayStats(
    val groupIds: List<Int>,
    val favorite: Boolean,
    val lastPlayedAt: Long?,
    val totalPlayedMs: Long,
) {
    /** true si el grupo se ha jugado alguna vez. */
    val hasPlayData: Boolean get() = lastPlayedAt != null
}

/** Reglas puras de agregación y selección de las estadísticas por juego. */
object PlayStatsLogic {
    /** Cuántos juegos muestra la fila «Seguir jugando». */
    const val CONTINUE_LIMIT: Int = 5

    /**
     * Agrega [groupIds] (se deduplican) sobre las estadísticas [byId]:
     * favorito = cualquiera, última partida = la mayor, tiempo = la suma.
     */
    fun aggregate(groupIds: List<Int>, byId: Map<Int, PlayStats>): GamePlayStats {
        val ids = groupIds.distinct()
        var favorite = false
        var lastPlayedAt: Long? = null
        var totalPlayedMs = 0L
        for (id in ids) {
            val stats = byId[id] ?: continue
            if (stats.favorite) favorite = true
            val played = stats.lastPlayedAt
            if (played != null && (lastPlayedAt == null || played > lastPlayedAt)) lastPlayedAt = played
            totalPlayedMs += stats.totalPlayedMs
        }
        return GamePlayStats(
            groupIds = ids,
            favorite = favorite,
            lastPlayedAt = lastPlayedAt,
            totalPlayedMs = totalPlayedMs,
        )
    }

    /**
     * Los [limit] grupos jugados más recientemente, de más nuevo a más antiguo.
     * Los grupos sin datos de partida se excluyen; a igual fecha manda el tiempo
     * jugado y, si también empata, el orden de entrada.
     */
    fun recent(
        groups: List<List<Int>>,
        byId: Map<Int, PlayStats>,
        limit: Int = CONTINUE_LIMIT,
    ): List<GamePlayStats> {
        if (limit <= 0) return emptyList()
        return groups
            .mapIndexed { index, group -> index to aggregate(group, byId) }
            .filter { (_, stats) -> stats.hasPlayData }
            .sortedWith(
                compareByDescending<Pair<Int, GamePlayStats>> { it.second.lastPlayedAt }
                    .thenByDescending { it.second.totalPlayedMs }
                    .thenBy { it.first },
            )
            .take(limit)
            .map { (_, stats) -> stats }
    }
}
