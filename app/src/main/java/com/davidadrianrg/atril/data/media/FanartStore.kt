package com.davidadrianrg.atril.data.media

import android.content.Context
import com.davidadrianrg.atril.core.media.ImageFileDownloader
import com.davidadrianrg.atril.core.media.MediaCatalog
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Fanart de SteamGridDB para el fondo del launcher: busca el primer plano de
 * 1920x1080 del título ([MediaCatalog.fanartUrl], común con escritorio) y lo
 * cachea por juego en `cache/fanart/<romId>.jpg`. Requiere la API key de
 * SteamGridDB configurada en Ajustes.
 */
class FanartStore(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val catalog: MediaCatalog = MediaCatalog(),
) {
    private val dir = File(context.cacheDir, "fanart").apply { mkdirs() }
    /** Juegos sin resultado: no repetir dos llamadas a SteamGridDB en cada pasada. */
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    suspend fun get(romId: Int, title: String, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            val file = File(dir, "$romId.jpg")
            if (file.isFile && file.length() > 0) return@withContext file.absolutePath
            if (apiKey.isBlank() || romId in failed) return@withContext null
            val url = catalog.fanartUrl(title, apiKey)
            // Descarga atómica y validada: nunca una imagen truncada o HTML.
            val saved = url?.let { ImageFileDownloader.download(client, it, file).getOrNull() }
            if (saved == null) {
                failed.add(romId)
                return@withContext null
            }
            saved.absolutePath
        }
}
