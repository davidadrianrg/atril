package com.davidadrianrg.atril.core.localgames

import com.davidadrianrg.atril.core.launch.PlaylistFiles
import com.davidadrianrg.atril.core.library.DiscMarks
import java.io.File

/**
 * Vista mínima de un juego local para agrupar sus discos.
 *
 * La construyen la app Android y el escritorio a partir de [LocalGame].
 *
 * @property id id del juego local (el que persiste el almacén).
 * @property title título visible; [LocalGameNames] ya le quitó las marcas.
 * @property path ruta del fichero; es la fuente autoritativa de la marca de
 *   disco y de la identidad del grupo.
 * @property platformSlug plataforma del juego.
 */
data class LocalDiscEntry(
    val id: String,
    val title: String,
    val path: String,
    val platformSlug: String,
)

/**
 * Un disco concreto dentro de un grupo local.
 *
 * @property discNumber número de disco (>= 1), siempre leído de [path].
 */
data class LocalDiscMember(
    val id: String,
    val title: String,
    val path: String,
    val discNumber: Int,
)

/**
 * Juego local repartido en varios ficheros de disco.
 *
 * @property key clave estable del grupo ([LocalDiscGrouper.groupKey]): se
 *   deriva de la plataforma y del nombre de fichero normalizado, nunca del
 *   título editable, así que renombrar el juego conserva el disco elegido.
 * @property representativeId miembro del disco 1 (el que pinta la carta y
 *   conserva renombrar/carátula).
 * @property members discos ordenados por número ascendente (desempate por ruta).
 * @property discCount número de discos del grupo (`members.size`).
 * @property playlistPath `.m3u` hermano del primer disco, solo en grupos con
 *   más de un disco; `null` si no hay playlist.
 */
data class LocalDiscGroup(
    val key: String,
    val representativeId: String,
    val members: List<LocalDiscMember>,
    val discCount: Int,
    val playlistPath: String?,
) {
    companion object {
        /** Id persistido de la opción "lanzar la playlist .m3u". */
        const val PLAYLIST_CHOICE_ID = "@playlist"
    }
}

/** Disco o playlist que se entrega al emulador. */
sealed interface LocalDiscChoice {

    /** Ruta que se lanza. */
    val launchPath: String

    /** Un disco concreto del grupo. */
    data class Disc(val member: LocalDiscMember) : LocalDiscChoice {
        override val launchPath: String get() = member.path
    }

    /** La playlist `.m3u` del grupo. */
    data class Playlist(val path: String) : LocalDiscChoice {
        override val launchPath: String get() = path
    }
}

/** Costura de E/S: dónde está el `.m3u` hermano de un fichero, si lo hay. */
fun interface PlaylistLocator {
    fun playlistFor(filePath: String): String?
}

/**
 * Agrupa juegos locales que son el mismo juego repartido en discos.
 *
 * Reglas (deliberadamente conservadoras):
 * - Solo se comparan entradas de la MISMA plataforma y el MISMO nombre de
 *   fichero normalizado (sin marca de disco, sin extensión).
 * - Un cubo solo se convierte en grupo multidisco si al menos DOS entradas
 *   distintas llevan marca de disco. `Game.iso` junto a `Game (Disc 1).iso`
 *   NO se fusionan, y un `(Disc 1)` solitario se queda como un juego.
 * - Dos ficheros con el mismo número de disco se colapsan en un miembro: gana
 *   el descriptor lanzable (`.cue`, luego `.gdi`, luego `.m3u`) y, entre
 *   iguales, el primero por ruta. Así un `.cue` y su `.bin` no duplican el
 *   disco y el superviviente es el índice que los emuladores pueden arrancar.
 * - Las entradas sin marca de un cubo multidisco se quedan como juegos sueltos.
 *
 * No toca RomM: opera solo sobre [LocalDiscEntry].
 */
object LocalDiscGrouper {

    private val EXTENSION = Regex("""\.[A-Za-z0-9]{1,5}$""")

    /**
     * Extensiones de descriptor lanzable, en orden de preferencia cuando dos
     * entradas comparten número de disco: el `.cue` manda sobre el `.bin` que
     * describe, y lo mismo con `.gdi`/`.m3u`.
     */
    private val PREFERRED_EXTENSIONS = listOf(".cue", ".gdi", ".m3u")

    /** Localizador de producción: el `.m3u` hermano real en disco. */
    val DEFAULT_PLAYLIST_LOCATOR: PlaylistLocator =
        PlaylistLocator { PlaylistFiles.siblingPlaylist(File(it))?.path }

    /**
     * Agrupa [entries]; devuelve un grupo por cada juego a mostrar, en orden de
     * primera aparición. [playlistLocator] es la costura de E/S (en tests se
     * sustituye por una lambda para no tocar el sistema de ficheros).
     */
    fun group(
        entries: List<LocalDiscEntry>,
        playlistLocator: PlaylistLocator = DEFAULT_PLAYLIST_LOCATOR,
    ): List<LocalDiscGroup> {
        if (entries.isEmpty()) return emptyList()

        // Índices por cubo, conservando el orden de aparición.
        val buckets = LinkedHashMap<String, MutableList<Int>>()
        entries.forEachIndexed { index, entry ->
            val key = groupKey(entry.platformSlug, entry.path)
            buckets.getOrPut(key) { mutableListOf() }.add(index)
        }

        val ordered = mutableListOf<Pair<Int, LocalDiscGroup>>()
        buckets.forEach { (key, indices) ->
            val marked = indices.mapNotNull { index ->
                DiscMarks.discNumber(fileNameOf(entries[index].path))?.let { disc -> index to disc }
            }
            if (marked.map { it.second }.distinct().size < 2) {
                // Sin dos discos distintos: cada entrada es su propio juego.
                indices.forEach { index -> ordered.add(index to singleton(entries[index], key)) }
                return@forEach
            }

            // Un miembro por número de disco: gana el descriptor lanzable
            // (.cue > .gdi > .m3u); entre iguales, el primero por ruta.
            val memberIndexByDisc = LinkedHashMap<Int, Int>()
            marked.sortedWith(
                compareBy(
                    { it.second },
                    { descriptorRank(entries[it.first].path) },
                    { entries[it.first].path },
                ),
            ).forEach { (index, disc) -> memberIndexByDisc.getOrPut(disc) { index } }
            val members = memberIndexByDisc.values
                .map { index -> entries[index].toMember() }
                .sortedWith(compareBy({ it.discNumber }, { it.path }))
            val playlistPath = playlistLocator.playlistFor(members.first().path)
                ?.takeIf { it.isNotBlank() }
            val group = LocalDiscGroup(
                key = key,
                representativeId = (members.firstOrNull { it.discNumber == 1 } ?: members.first()).id,
                members = members,
                discCount = members.size,
                playlistPath = playlistPath,
            )
            ordered.add(memberIndexByDisc.values.min() to group)

            // Las entradas sin marca del mismo cubo no se pierden.
            indices.filter { index -> DiscMarks.discNumber(fileNameOf(entries[index].path)) == null }
                .forEach { index -> ordered.add(index to singleton(entries[index], key)) }
        }

        return ordered.sortedBy { it.first }.map { it.second }
    }

    /**
     * Clave estable del grupo: plataforma + nombre de fichero sin extensión y
     * sin marca de disco. No depende del título, así que renombrar el juego o
     * volver a añadir el mismo fichero resuelve a la misma clave.
     */
    fun groupKey(platformSlug: String, path: String): String {
        val base = fileNameOf(path).replace(EXTENSION, "")
        return platformSlug.trim().lowercase() + "|" + DiscMarks.normalizeName(base)
    }

    /** Disco/playlist por defecto: la playlist si existe; si no, el disco 1. */
    fun defaultChoice(group: LocalDiscGroup): LocalDiscChoice =
        group.playlistPath?.let { LocalDiscChoice.Playlist(it) }
            ?: LocalDiscChoice.Disc(group.members.first())

    /**
     * Resuelve la elección persistida [storedId]: un id de miembro existente o
     * [LocalDiscGroup.PLAYLIST_CHOICE_ID] cuando hay playlist. Cualquier otro
     * valor (nulo, desconocido o playlist inexistente) cae en [defaultChoice].
     */
    fun choiceFor(group: LocalDiscGroup, storedId: String?): LocalDiscChoice = when {
        storedId == LocalDiscGroup.PLAYLIST_CHOICE_ID && group.playlistPath != null ->
            LocalDiscChoice.Playlist(group.playlistPath)
        else -> group.members.firstOrNull { it.id == storedId }?.let { LocalDiscChoice.Disc(it) }
            ?: defaultChoice(group)
    }

    private fun singleton(entry: LocalDiscEntry, key: String): LocalDiscGroup {
        val member = entry.toMember()
        return LocalDiscGroup(
            key = key,
            representativeId = entry.id,
            members = listOf(member),
            discCount = 1,
            playlistPath = null,
        )
    }

    private fun LocalDiscEntry.toMember(): LocalDiscMember = LocalDiscMember(
        id = id,
        title = title,
        path = path,
        discNumber = DiscMarks.discNumber(fileNameOf(path)) ?: 1,
    )

    private fun fileNameOf(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    /**
     * Posición de [path] en [PREFERRED_EXTENSIONS]; las extensiones de datos
     * (`.bin`, `.iso`, ...) quedan al final.
     */
    private fun descriptorRank(path: String): Int {
        val extension = fileNameOf(path).substringAfterLast('.', "").lowercase()
        val rank = PREFERRED_EXTENSIONS.indexOf(".$extension")
        return if (rank >= 0) rank else PREFERRED_EXTENSIONS.size
    }
}
