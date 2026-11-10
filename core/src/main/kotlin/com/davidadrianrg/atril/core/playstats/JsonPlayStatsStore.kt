package com.davidadrianrg.atril.core.playstats

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * Forma en disco de un registro. Todos los campos son opcionales a propósito:
 * así una entrada incompleta o manipulada se descarta o se corrige al leer en
 * lugar de tumbar la carga entera (mismo criterio que `LocalGame`).
 */
@JsonClass(generateAdapter = false)
internal data class PlayStatsWire(
    val romId: Int? = null,
    val favorite: Boolean? = null,
    val lastPlayedAt: Long? = null,
    val totalPlayedMs: Long? = null,
)

/**
 * Estadísticas por juego en un único fichero JSON, con el mismo patrón que
 * [com.davidadrianrg.atril.core.localgames.LocalGameStore]: escritura atómica
 * (temporal + rename) para que un cierre a mitad no corrompa el fichero, y un
 * fichero ilegible se conserva como `.corrupt` en lugar de perderse.
 *
 * La lectura es defensiva: las claves desconocidas se ignoran, las entradas sin
 * `romId` válido se descartan (si se repite un id gana la primera), una última
 * partida fuera de un rango razonable se descarta y un tiempo jugado negativo se
 * corrige a cero. Un fallo de escritura se registra en el log y se mantiene el
 * estado en memoria: la UI nunca se rompe por no poder guardar.
 */
class JsonPlayStatsStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { System.err.println(it) },
) : PlayStatsStore {

    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter<List<PlayStatsWire>>(
            Types.newParameterizedType(List::class.java, PlayStatsWire::class.java),
        )

    private var cache: Map<Int, PlayStats>? = null

    @Synchronized
    override fun all(): List<PlayStats> = load().values.sortedBy { it.romId }

    @Synchronized
    override fun get(romId: Int): PlayStats? = load()[romId]

    @Synchronized
    override fun setFavorite(romId: Int, favorite: Boolean): PlayStats {
        val current = load()
        val updated = (current[romId] ?: PlayStats(romId)).copy(favorite = favorite)
        save(current + (romId to updated))
        return updated
    }

    @Synchronized
    override fun recordSession(romId: Int, endedAt: Long, durationMs: Long): PlayStats {
        require(durationMs >= 0L) { "durationMs no puede ser negativo: $durationMs" }
        val current = load()
        val previous = current[romId] ?: PlayStats(romId)
        val updated = previous.copy(
            lastPlayedAt = maxOf(previous.lastPlayedAt ?: 0L, endedAt),
            totalPlayedMs = previous.totalPlayedMs + durationMs,
        )
        save(current + (romId to updated))
        return updated
    }

    private fun load(): Map<Int, PlayStats> {
        cache?.let { return it }
        val loaded = if (file.isFile) {
            runCatching { parse(file.readText()) }.getOrElse {
                preserveCorrupt(it)
                emptyMap()
            }
        } else {
            emptyMap()
        }
        cache = loaded
        return loaded
    }

    /** Convierte el JSON en registros válidos, corrigiendo lo que se pueda corregir. */
    private fun parse(json: String): Map<Int, PlayStats> {
        val latestSane = clock() + SANE_FUTURE_MARGIN_MS
        val parsed = LinkedHashMap<Int, PlayStats>()
        adapter.fromJson(json).orEmpty().forEach { wire ->
            val romId = wire.romId ?: return@forEach
            if (parsed.containsKey(romId)) return@forEach
            parsed[romId] = PlayStats(
                romId = romId,
                favorite = wire.favorite ?: false,
                lastPlayedAt = wire.lastPlayedAt?.takeIf { it > 0L && it <= latestSane },
                totalPlayedMs = (wire.totalPlayedMs ?: 0L).coerceAtLeast(0L),
            )
        }
        return parsed
    }

    /** Conserva una copia del fichero ilegible y arranca con la lista vacía. */
    private fun preserveCorrupt(error: Throwable) {
        val backup = File(file.path + ".corrupt")
        runCatching { file.copyTo(backup, overwrite = true) }
        log("WARN playstats: fichero ilegible ${file.name} (${error.javaClass.simpleName}); copia en ${backup.name}, se arranca vacío")
    }

    private fun save(stats: Map<Int, PlayStats>) {
        // El estado en memoria manda aunque el disco falle: la sesión ya cuenta.
        cache = stats
        val wire = stats.values.sortedBy { it.romId }.map {
            PlayStatsWire(
                romId = it.romId,
                favorite = it.favorite,
                lastPlayedAt = it.lastPlayedAt,
                totalPlayedMs = it.totalPlayedMs,
            )
        }
        try {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(adapter.toJson(wire))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            log("WARN playstats: no se pudo guardar ${file.name} (${e.javaClass.simpleName}); se mantiene en memoria")
        }
    }

    private companion object {
        /** Margen por delante del reloj para no descartar una partida recién guardada. */
        const val SANE_FUTURE_MARGIN_MS: Long = 86_400_000L
    }
}
