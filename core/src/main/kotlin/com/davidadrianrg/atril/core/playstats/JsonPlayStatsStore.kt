package com.davidadrianrg.atril.core.playstats

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * Forma en disco de un registro: define el JSON que se escribe. Todos los campos
 * son opcionales para que el documento tolere entradas incompletas; la lectura no
 * usa este tipo, sino un recorrido sin tipos que corrige o descarta cada entrada.
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
 * La lectura es defensiva y entrada a entrada: las claves desconocidas se
 * ignoran, las entradas sin `romId` válido se descartan (si se repite un id gana
 * la primera), un valor con el tipo equivocado solo invalida su entrada, una
 * última partida fuera de un rango razonable se descarta y un tiempo jugado
 * negativo se corrige a cero. Solo un documento que no es un array JSON (o que
 * no se puede leer) se considera corrupto. Un fallo de escritura se registra en
 * el log y se mantiene el estado en memoria: la UI nunca se rompe por no poder
 * guardar.
 */
class JsonPlayStatsStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { System.err.println(it) },
) : PlayStatsStore {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    /** Escritura tipada: el formato en disco lo define [PlayStatsWire]. */
    private val writer = moshi.adapter<List<PlayStatsWire>>(
        Types.newParameterizedType(List::class.java, PlayStatsWire::class.java),
    )

    /**
     * Lectura sin tipos: cada entrada se inspecciona y se corrige por separado,
     * así un valor con el tipo equivocado descarta solo esa entrada en lugar de
     * tirar el fichero entero.
     */
    private val reader = moshi.adapter(Any::class.java)

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

    /**
     * Convierte el JSON en registros válidos, corrigiendo lo que se pueda corregir
     * entrada a entrada: un objeto mal formado o un campo con el tipo equivocado
     * se descarta sin arrastrar al resto del fichero. Solo un documento que no es
     * un array JSON (o que no se puede ni leer) acaba en la ruta `.corrupt`.
     */
    private fun parse(json: String): Map<Int, PlayStats> {
        val entries = reader.fromJson(json) as? List<*>
            ?: throw IllegalArgumentException("play stats document is not a JSON array")
        val latestSane = clock() + SANE_FUTURE_MARGIN_MS
        val parsed = LinkedHashMap<Int, PlayStats>()
        var dropped = 0
        for (entry in entries) {
            val fields = entry as? Map<*, *>
            val romId = fields?.get("romId")?.let(::intOrNull)
            if (fields == null || romId == null) {
                dropped++
                continue
            }
            // Un romId repetido se ignora: gana la primera aparición.
            if (parsed.containsKey(romId)) continue
            parsed[romId] = PlayStats(
                romId = romId,
                favorite = fields["favorite"] as? Boolean ?: false,
                lastPlayedAt = fields["lastPlayedAt"]
                    ?.let(::longOrNull)
                    ?.takeIf { it > 0L && it <= latestSane },
                totalPlayedMs = (fields["totalPlayedMs"]?.let(::longOrNull) ?: 0L).coerceAtLeast(0L),
            )
        }
        if (dropped > 0) {
            log("WARN playstats: dropped $dropped invalid entries from ${file.name}")
        }
        return parsed
    }

    /** Entero del JSON sin tipos (Moshi entrega los números como Double): solo vale si es exacto. */
    private fun intOrNull(value: Any?): Int? {
        val number = value as? Number ?: return null
        val asDouble = number.toDouble()
        if (asDouble % 1.0 != 0.0) return null
        if (asDouble < Int.MIN_VALUE.toDouble() || asDouble > Int.MAX_VALUE.toDouble()) return null
        return asDouble.toInt()
    }

    /** Igual que [intOrNull] para los campos de tiempo (epoch ms y duración). */
    private fun longOrNull(value: Any?): Long? {
        val number = value as? Number ?: return null
        val asDouble = number.toDouble()
        if (asDouble % 1.0 != 0.0) return null
        if (asDouble < Long.MIN_VALUE.toDouble() || asDouble > Long.MAX_VALUE.toDouble()) return null
        return asDouble.toLong()
    }

    /** Conserva una copia del fichero ilegible y arranca con la lista vacía. */
    private fun preserveCorrupt(error: Throwable) {
        val backup = File(file.path + ".corrupt")
        runCatching { file.copyTo(backup, overwrite = true) }
        log(
            "WARN playstats: fichero ilegible ${file.name} (${error.javaClass.simpleName}); copia en ${backup.name}, se arranca vacío",
        )
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
            tmp.writeText(writer.toJson(wire))
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
