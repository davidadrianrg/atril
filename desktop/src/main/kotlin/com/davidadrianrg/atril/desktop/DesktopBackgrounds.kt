package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.media.ImageFileDownloader
import com.davidadrianrg.atril.core.media.LauncherBackdrop
import com.davidadrianrg.atril.core.media.MediaCatalog
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Fondo del launcher de escritorio, con las mismas reglas que Android
 * ([LauncherBackdrop]): modo global, fondo elegido por juego (copiado a
 * `~/.config/atril/game_backgrounds/<romId>.<ext>`) y fanart de SteamGridDB
 * cacheado en `~/.cache/atril/fanart/<romId>.jpg`.
 */
object DesktopBackgrounds {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val catalog = MediaCatalog()

    private val _mode = MutableStateFlow(DesktopConfig.launcherBackground)
    val mode: StateFlow<String> = _mode.asStateFlow()

    private val _games = MutableStateFlow(LauncherBackdrop.decodeGameBackgrounds(DesktopConfig.gameBackgrounds))
    val games: StateFlow<Map<Int, String>> = _games.asStateFlow()

    /** Juegos sin fanart: no volver a preguntar a SteamGridDB en esta sesión. */
    private val fanartMisses = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    private val backgroundsDir get() = File(DesktopConfig.configDir, "game_backgrounds").apply { mkdirs() }
    private val fanartDir get() = File(DesktopConfig.cacheDir, "fanart").apply { mkdirs() }

    fun setMode(mode: String) {
        DesktopConfig.launcherBackground = mode
        _mode.value = DesktopConfig.launcherBackground
    }

    /** Relee las preferencias (tras importar una copia de seguridad). */
    fun reload() {
        _mode.value = DesktopConfig.launcherBackground
        _games.value = LauncherBackdrop.decodeGameBackgrounds(DesktopConfig.gameBackgrounds)
    }

    /**
     * URL que entiende el cargador de imágenes (`file://` para rutas locales).
     * Una ruta que ya no existe es null: así se cae a fanart/captura en vez de
     * dejar el fondo vacío.
     */
    fun asImageUrl(path: String?): String? = when {
        path == null -> null
        path.startsWith("http://") || path.startsWith("https://") -> path
        else -> File(path.removePrefix("file://")).takeIf { it.isFile }?.let { "file://${it.absolutePath}" }
    }

    /** Descarga [url] como fondo del juego. false si no es una imagen válida. */
    suspend fun setFromUrl(romId: Int, url: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(backgroundsDir, "$romId.jpg")
        val saved = ImageFileDownloader.download(client, url, target).getOrNull() ?: return@withContext false
        store(romId, saved.absolutePath)
        true
    }

    /** Copia un fichero local elegido por el usuario como fondo del juego. */
    suspend fun setFromFile(romId: Int, source: File): Boolean = withContext(Dispatchers.IO) {
        if (!source.isFile || !ImageFileDownloader.looksLikeImage(source)) return@withContext false
        val ext = source.extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp") } ?: "jpg"
        // Se borra el anterior: puede tener otra extensión.
        _games.value[romId]?.let { File(it).takeIf { f -> f.parentFile == backgroundsDir }?.delete() }
        val target = File(backgroundsDir, "$romId.$ext")
        val ok = runCatching { source.copyTo(target, overwrite = true) }.isSuccess
        if (ok) store(romId, target.absolutePath)
        ok
    }

    fun clear(romId: Int) {
        _games.value[romId]?.let { path ->
            File(path).takeIf { it.parentFile == backgroundsDir }?.delete()
        }
        store(romId, null)
    }

    private fun store(romId: Int, path: String?) {
        DesktopConfig.gameBackgrounds = LauncherBackdrop.withGameBackground(DesktopConfig.gameBackgrounds, romId, path)
        _games.value = LauncherBackdrop.decodeGameBackgrounds(DesktopConfig.gameBackgrounds)
        // El cargador memoiza por URL: el mismo fichero con otra imagen no se
        // vería hasta reiniciar.
        BackdropCache.clear()
    }

    /** Ruta del fanart del juego, descargándolo la primera vez; null si no hay. */
    suspend fun fanart(romId: Int, title: String, apiKey: String): String? = withContext(Dispatchers.IO) {
        val file = File(fanartDir, "$romId.jpg")
        if (file.isFile && file.length() > 0) return@withContext file.absolutePath
        if (apiKey.isBlank() || romId in fanartMisses) return@withContext null
        val saved = catalog.fanartUrl(title, apiKey)
            ?.let { ImageFileDownloader.download(client, it, file).getOrNull() }
        if (saved == null) fanartMisses += romId
        saved?.absolutePath
    }
}
