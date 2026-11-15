package com.davidadrianrg.atril.data.media

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Fanart de SteamGridDB para el fondo del launcher: busca el primer plano de
 * 1920x1080 del título y lo cachea por juego en `cache/fanart/<romId>.jpg`.
 * Requiere la API key de SteamGridDB configurada en Ajustes.
 */
class FanartStore(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val dir = File(context.cacheDir, "fanart").apply { mkdirs() }

    suspend fun get(romId: Int, title: String, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            val file = File(dir, "$romId.jpg")
            if (file.isFile && file.length() > 0) return@withContext file.absolutePath
            if (apiKey.isBlank()) return@withContext null
            val url = backgroundUrl(title, apiKey) ?: return@withContext null
            download(url, file) ?: return@withContext null
            file.absolutePath
        }

    private fun backgroundUrl(title: String, apiKey: String): String? {
        val auth = mapOf("Authorization" to "Bearer $apiKey")
        val search = get("$BASE/search/autocomplete/${enc(title)}", auth) ?: return null
        val gameId = runCatching {
            (JSONObject(search).optJSONArray("data")?.optJSONObject(0))?.optLong("id")
        }.getOrNull()?.takeIf { it > 0 } ?: return null
        val backgrounds = get("$BASE/backgrounds/game/$gameId?dimensions=1920x1080&types=static", auth)
            ?: return null
        return runCatching {
            val data = JSONObject(backgrounds).optJSONArray("data")
            (0 until (data?.length() ?: 0)).firstNotNullOfOrNull { i -> data?.optJSONObject(i)?.optString("url") }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun download(url: String, target: File): Boolean = try {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val bytes = response.body?.bytes() ?: return false
            if (bytes.isEmpty()) return false
            target.outputStream().use { it.write(bytes) }
            true
        }
    } catch (e: IOException) {
        false
    }

    private fun get(url: String, headers: Map<String, String>): String? = runCatching {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }.getOrNull()

    companion object {
        private const val BASE = "https://www.steamgriddb.com/api/v2"

        private fun enc(value: String): String =
            URLEncoder.encode(value.trim(), "UTF-8")
    }
}
