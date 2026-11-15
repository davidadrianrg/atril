package com.davidadrianrg.atril.data.media

import com.davidadrianrg.atril.core.localgames.ScreenScraperScraper
import com.davidadrianrg.atril.core.localgames.ScraperCredentials
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Imagen candidata devuelta por un proveedor de media. */
data class MediaCandidate(
    val url: String,
    /** `screenscraper` o `steamgriddb`. */
    val provider: String,
    /** `cover` (carátula vertical) o `background` (fanart/screenshot). */
    val kind: String,
)

/**
 * Busca candidatas de media en ScreenScraper (box-2D y screenshots) y
 * SteamGridDB (grids y backgrounds) para el selector de previsualización.
 */
class MediaCatalog(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    /** Devuelve las dos fuentes en paralelo; un fallo no anula la otra. */
    suspend fun search(
        title: String,
        platformSlug: String,
        creds: ScraperCredentials,
    ): List<MediaCandidate> = coroutineScope {
        val ss = async(Dispatchers.IO) { screenScraper(title, platformSlug, creds) }
        val sgdb = async(Dispatchers.IO) { steamGridDb(title, creds.steamGridDbKey) }
        (sgdb.await() + ss.await()).distinctBy { it.url }
    }

    private fun screenScraper(title: String, platformSlug: String, creds: ScraperCredentials): List<MediaCandidate> {
        val c = creds.screenScraper() ?: return emptyList()
        val url = buildString {
            append("https://api.screenscraper.fr/api2/jeuRecherche.php?output=json&softname=atril")
            append("&ssid=").append(enc(c.userId)).append("&sspassword=").append(enc(c.userPassword))
            ScreenScraperScraper.systemId(platformSlug)?.let { append("&systemeid=").append(it) }
            append("&recherche=").append(enc(title))
        }
        return runCatching {
            val body = get(url, emptyMap()) ?: return emptyList()
            val game = JSONObject(body).optJSONObject("response")
                ?.optJSONArray("jeux")?.optJSONObject(0) ?: return emptyList()
            val medias = game.optJSONArray("medias") ?: return emptyList()
            (0 until medias.length()).mapNotNull { i ->
                val m = medias.optJSONObject(i) ?: return@mapNotNull null
                val mediaUrl = m.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                when (m.optString("type")) {
                    "box-2D", "box-3D" -> MediaCandidate(mediaUrl, "screenscraper", "cover")
                    "screenshot", "fanart" -> MediaCandidate(mediaUrl, "screenscraper", "background")
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun steamGridDb(title: String, apiKey: String): List<MediaCandidate> {
        if (apiKey.isBlank()) return emptyList()
        val auth = mapOf("Authorization" to "Bearer $apiKey")
        return runCatching {
            val search = get("$SGDB/search/autocomplete/${enc(title)}", auth) ?: return emptyList()
            val gameId = JSONObject(search).optJSONArray("data")?.optJSONObject(0)?.optLong("id")
                ?.takeIf { it > 0 } ?: return emptyList()
            val covers = get("$SGDB/grids/game/$gameId?types=static", auth)
                ?.let { urls(it, "steamgriddb", "cover") }.orEmpty()
            val backgrounds = get("$SGDB/backgrounds/game/$gameId?types=static", auth)
                ?.let { urls(it, "steamgriddb", "background") }.orEmpty()
            covers + backgrounds
        }.getOrDefault(emptyList())
    }

    private fun urls(body: String, provider: String, kind: String): List<MediaCandidate> =
        runCatching {
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            (0 until data.length()).mapNotNull { i ->
                data.optJSONObject(i)?.optString("url")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { MediaCandidate(it, provider, kind) }
            }
        }.getOrDefault(emptyList())

    private fun get(url: String, headers: Map<String, String>): String? = runCatching {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }.getOrNull()

    private fun enc(value: String): String = URLEncoder.encode(value.trim(), "UTF-8")

    companion object {
        private const val SGDB = "https://www.steamgriddb.com/api/v2"
    }
}
