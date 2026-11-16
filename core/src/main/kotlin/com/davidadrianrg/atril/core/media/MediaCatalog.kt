package com.davidadrianrg.atril.core.media

import com.davidadrianrg.atril.core.localgames.HttpFetcher
import com.davidadrianrg.atril.core.localgames.OkHttpFetcher
import com.davidadrianrg.atril.core.localgames.ScraperCredentials
import com.davidadrianrg.atril.core.localgames.ScreenScraperScraper
import com.squareup.moshi.Moshi
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Imagen candidata devuelta por un proveedor de media. */
data class MediaCandidate(
    val url: String,
    /** `screenscraper` o `steamgriddb`. */
    val provider: String,
    /** `cover` (carátula vertical) o `background` (fanart/screenshot). */
    val kind: String,
)

/**
 * Busca candidatas de media en ScreenScraper (box-2D/3D, screenshots, fanart)
 * y SteamGridDB (grids y backgrounds) para el selector con previsualización y
 * el fondo «fanart» del launcher. Común a Android y escritorio.
 */
class MediaCatalog(private val fetcher: HttpFetcher = OkHttpFetcher()) {

    private val json = Moshi.Builder().build().adapter(Any::class.java)

    /** Las dos fuentes en paralelo; un fallo no anula la otra. */
    suspend fun search(
        title: String,
        platformSlug: String,
        creds: ScraperCredentials,
    ): List<MediaCandidate> = coroutineScope {
        val ss = async(Dispatchers.IO) { screenScraper(title, platformSlug, creds) }
        val sgdb = async(Dispatchers.IO) { steamGridDb(title, creds.steamGridDbKey) }
        (sgdb.await() + ss.await()).distinctBy { it.url }
    }

    /** Primer fondo 1920x1080 estático de SteamGridDB para [title], o null. */
    fun fanartUrl(title: String, apiKey: String): String? {
        if (apiKey.isBlank()) return null
        val auth = auth(apiKey)
        val gameId = sgdbGameId(title, auth) ?: return null
        return get("$SGDB/backgrounds/game/$gameId?dimensions=1920x1080&types=static", auth)
            ?.let { urls(it, "steamgriddb", "background") }
            ?.firstOrNull()?.url
    }

    private fun screenScraper(title: String, platformSlug: String, creds: ScraperCredentials): List<MediaCandidate> {
        val c = creds.screenScraper() ?: return emptyList()
        val url = buildString {
            append("https://api.screenscraper.fr/api2/jeuRecherche.php?output=json&softname=atril")
            append("&ssid=").append(enc(c.userId)).append("&sspassword=").append(enc(c.userPassword))
            ScreenScraperScraper.systemId(platformSlug)?.let { append("&systemeid=").append(it) }
            append("&recherche=").append(enc(title))
        }
        val body = get(url, emptyMap()) ?: return emptyList()
        val game = ((obj(body)?.get("response") as? Map<*, *>)?.get("jeux") as? List<*>)
            ?.firstOrNull() as? Map<*, *> ?: return emptyList()
        return (game["medias"] as? List<*>).orEmpty().mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val mediaUrl = (m["url"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            when (m["type"]) {
                "box-2D", "box-3D" -> MediaCandidate(mediaUrl, "screenscraper", "cover")
                "screenshot", "fanart" -> MediaCandidate(mediaUrl, "screenscraper", "background")
                else -> null
            }
        }
    }

    private fun steamGridDb(title: String, apiKey: String): List<MediaCandidate> {
        if (apiKey.isBlank()) return emptyList()
        val auth = auth(apiKey)
        val gameId = sgdbGameId(title, auth) ?: return emptyList()
        val covers = get("$SGDB/grids/game/$gameId?types=static", auth)
            ?.let { urls(it, "steamgriddb", "cover") }.orEmpty()
        val backgrounds = get("$SGDB/backgrounds/game/$gameId?types=static", auth)
            ?.let { urls(it, "steamgriddb", "background") }.orEmpty()
        return covers + backgrounds
    }

    private fun sgdbGameId(title: String, auth: Map<String, String>): Long? {
        val search = get("$SGDB/search/autocomplete/${enc(title)}", auth) ?: return null
        val first = (obj(search)?.get("data") as? List<*>)?.firstOrNull() as? Map<*, *>
        return (first?.get("id") as? Number)?.toLong()?.takeIf { it > 0 }
    }

    private fun urls(body: String, provider: String, kind: String): List<MediaCandidate> =
        (obj(body)?.get("data") as? List<*>).orEmpty().mapNotNull { raw ->
            ((raw as? Map<*, *>)?.get("url") as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { MediaCandidate(it, provider, kind) }
        }

    private fun obj(body: String): Map<*, *>? = runCatching { json.fromJson(body) as? Map<*, *> }.getOrNull()

    /** Cuerpo de una respuesta 2xx; red caída o error HTTP = null. */
    private fun get(url: String, headers: Map<String, String>): String? = runCatching {
        fetcher.get(url, headers).takeIf { it.code in 200..299 }?.body
    }.getOrNull()

    private fun auth(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    private fun enc(value: String): String = URLEncoder.encode(value.trim(), "UTF-8")

    private companion object {
        const val SGDB = "https://www.steamgriddb.com/api/v2"
    }
}
