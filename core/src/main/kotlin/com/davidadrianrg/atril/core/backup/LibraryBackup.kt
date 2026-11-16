package com.davidadrianrg.atril.core.backup

import com.davidadrianrg.atril.core.localgames.LocalGame
import com.davidadrianrg.atril.core.playstats.PlayStats

/**
 * Parte de la biblioteca local en la copia de seguridad (esquema 3), común a
 * Android y escritorio: favoritos/tiempo jugado (`playStats`) y juegos
 * locales (`localGames`).
 *
 * - La lectura es defensiva: el JSON llega sin tipos (Moshi entrega los
 *   números como `Double`) y una entrada inválida solo se descarta a sí misma.
 * - Restaurar **fusiona**, nunca borra: importar una copia antigua no hace
 *   perder lo jugado después.
 * - Las rutas de los juegos locales solo valen en la plataforma que las
 *   escribió; [restoresLocalGames] decide si se aplican. Las carátulas de los
 *   juegos locales no viajan (son ficheros de este equipo).
 */
object LibraryBackup {

    const val PLATFORM_ANDROID = "android"
    const val PLATFORM_DESKTOP = "desktop"

    fun encodePlayStats(stats: Collection<PlayStats>): List<Map<String, Any?>> =
        stats.sortedBy { it.romId }.map { st ->
            mapOf(
                "romId" to st.romId,
                "favorite" to st.favorite,
                "lastPlayedAt" to st.lastPlayedAt,
                "totalPlayedMs" to st.totalPlayedMs,
            )
        }

    fun decodePlayStats(raw: Any?): List<PlayStats> =
        (raw as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val romId = (m["romId"] as? Number)?.toInt()?.takeIf { it != 0 } ?: return@mapNotNull null
            PlayStats(
                romId = romId,
                favorite = m["favorite"] as? Boolean ?: false,
                lastPlayedAt = (m["lastPlayedAt"] as? Number)?.toLong()?.takeIf { it > 0 },
                totalPlayedMs = ((m["totalPlayedMs"] as? Number)?.toLong() ?: 0L).coerceAtLeast(0L),
            )
        }.distinctBy { it.romId }

    /**
     * Une [current] con [restored] por romId: favorito si lo es en cualquiera,
     * última partida y tiempo jugado los mayores. Los ids solo presentes en
     * uno de los lados se conservan.
     */
    fun mergePlayStats(current: Collection<PlayStats>, restored: Collection<PlayStats>): List<PlayStats> {
        val byId = current.associateBy { it.romId }.toMutableMap()
        for (r in restored) {
            val c = byId[r.romId]
            byId[r.romId] = if (c == null) r else PlayStats(
                romId = r.romId,
                favorite = c.favorite || r.favorite,
                lastPlayedAt = listOfNotNull(c.lastPlayedAt, r.lastPlayedAt).maxOrNull(),
                totalPlayedMs = maxOf(c.totalPlayedMs, r.totalPlayedMs),
            )
        }
        return byId.values.sortedBy { it.romId }
    }

    fun encodeLocalGames(games: Collection<LocalGame>): List<Map<String, Any?>> =
        games.map { g ->
            mapOf(
                "id" to g.id,
                // Sin él, al restaurar se reasignaría y el juego perdería sus estadísticas.
                "romId" to g.romId,
                "title" to g.title,
                "platformSlug" to g.platformSlug,
                "path" to g.path,
                "summary" to g.summary,
                "addedAt" to g.addedAt,
            )
        }

    fun decodeLocalGames(raw: Any?): List<LocalGame> =
        (raw as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = (m["id"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = (m["path"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val platform = (m["platformSlug"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LocalGame(
                id = id,
                title = (m["title"] as? String)?.takeIf { it.isNotBlank() } ?: id,
                platformSlug = platform,
                path = path,
                summary = m["summary"] as? String,
                addedAt = (m["addedAt"] as? Number)?.toLong() ?: 0L,
                romId = (m["romId"] as? Number)?.toInt()?.takeIf { it < 0 },
            )
        }.distinctBy { it.id }

    /** Añade a [current] los juegos de [restored] que aún no existen (por id). */
    fun mergeLocalGames(current: List<LocalGame>, restored: List<LocalGame>): List<LocalGame> {
        val known = current.mapTo(HashSet()) { it.id }
        return current + restored.filter { it.id !in known }
    }

    /**
     * Los juegos locales (y sus estadísticas, con id negativo) solo se
     * restauran en la misma plataforma: una ruta de Android no vale en
     * escritorio ni al revés. Una copia sin `platform` es de Android.
     */
    fun restoresLocalGames(backupPlatform: String?, here: String): Boolean =
        (backupPlatform ?: PLATFORM_ANDROID) == here

    /** Estadísticas restaurables aquí: las de juegos locales solo si [withLocal]. */
    fun restorablePlayStats(stats: List<PlayStats>, withLocal: Boolean): List<PlayStats> =
        if (withLocal) stats else stats.filter { it.romId > 0 }
}
