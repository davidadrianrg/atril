package com.davidadrianrg.atril.core.library

import com.davidadrianrg.atril.core.media.ImageFileDownloader
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * Carátulas elegidas a mano por el usuario, por romId (de RomM o el negativo
 * de un juego local). Común a Android y escritorio.
 *
 * - La imagen se copia a [dir] con un nombre **nuevo en cada cambio**: los
 *   cachés de imagen (Coil, el de escritorio) van por URL y con el mismo
 *   nombre seguirían pintando la anterior. La anterior se borra.
 * - Se valida que sea una imagen (firma) y que no pase de [maxBytes]; una
 *   copia a medias nunca sustituye a la buena (fichero `.part` + rename).
 * - [covers] solo lista ficheros que existen: si se borra a mano, el juego
 *   vuelve a la carátula de RomM.
 * - Las operaciones hacen E/S bloqueante: llamar desde un hilo de fondo.
 */
class CoverOverrides(
    private val file: File,
    private val dir: File,
    private val client: OkHttpClient = OkHttpClient(),
    private val maxBytes: Long = ImageFileDownloader.DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val adapter = Moshi.Builder().build().adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java),
    )

    private val _covers = MutableStateFlow(existing(load()))

    /** romId → ruta absoluta de la carátula personalizada. */
    val covers: StateFlow<Map<Int, String>> = _covers.asStateFlow()

    fun get(romId: Int): String? = _covers.value[romId]

    /** Copia [input] (lo cierra) como carátula de [romId]. false si no es una imagen válida. */
    @Synchronized
    fun setFromStream(romId: Int, input: InputStream): Boolean {
        val target = newTarget(romId)
        val part = File(dir, "${target.name}.part")
        val ok = runCatching {
            input.use { src ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = src.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw IOException("image too large")
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (part.length() == 0L || !ImageFileDownloader.looksLikeImage(part)) throw IOException("not an image")
            if (!part.renameTo(target)) throw IOException("rename failed")
        }.isSuccess
        part.delete()
        if (ok) store(romId, target)
        return ok
    }

    /** Descarga [url] como carátula de [romId]. false si falla o no es una imagen. */
    @Synchronized
    fun setFromUrl(romId: Int, url: String): Boolean {
        val target = newTarget(romId)
        val saved = ImageFileDownloader.download(client, url, target, maxBytes).getOrNull() ?: return false
        store(romId, saved)
        return true
    }

    /** Copia el fichero local [source] como carátula de [romId]. */
    fun setFromFile(romId: Int, source: File): Boolean =
        source.isFile && runCatching { setFromStream(romId, source.inputStream()) }.getOrDefault(false)

    /** Quita la carátula personalizada y borra su fichero. */
    @Synchronized
    fun clear(romId: Int) {
        val map = load().toMutableMap()
        val old = map.remove(romId.toString()) ?: return
        deleteOwned(old)
        save(map)
    }

    private fun newTarget(romId: Int): File {
        dir.mkdirs()
        return File(dir, "${romId}_${clock()}.img")
    }

    private fun store(romId: Int, target: File) {
        val map = load().toMutableMap()
        val old = map.put(romId.toString(), target.absolutePath)
        if (old != null && old != target.absolutePath) deleteOwned(old)
        save(map)
    }

    /** Solo se borran ficheros dentro de [dir], nunca uno ajeno. */
    private fun deleteOwned(path: String) {
        val f = File(path)
        if (f.absoluteFile.parentFile == dir.absoluteFile) f.delete()
    }

    private fun save(map: Map<String, String>) {
        runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(adapter.toJson(map))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
        _covers.value = existing(map)
    }

    private fun load(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return runCatching { adapter.fromJson(file.readText()) }.getOrNull().orEmpty()
    }

    private fun existing(map: Map<String, String>): Map<Int, String> =
        map.mapNotNull { (k, v) -> k.toIntOrNull()?.takeIf { File(v).isFile }?.let { it to v } }.toMap()
}
