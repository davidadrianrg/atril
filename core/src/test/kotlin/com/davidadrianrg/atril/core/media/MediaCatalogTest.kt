package com.davidadrianrg.atril.core.media

import com.davidadrianrg.atril.core.localgames.HttpFetcher
import com.davidadrianrg.atril.core.localgames.HttpReply
import com.davidadrianrg.atril.core.localgames.ScraperCredentials
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCatalogTest {

    private val sgdbSearch = """{"success":true,"data":[{"id":42,"name":"Zelda"}]}"""
    private val ssBody = """
        {"response":{"jeux":[{"medias":[
          {"type":"box-2D","url":"https://ss/box.png"},
          {"type":"screenshot","url":"https://ss/shot.png"},
          {"type":"wheel","url":"https://ss/wheel.png"}]}]}}
    """.trimIndent()

    private fun fetcher(routes: (String) -> HttpReply?) = HttpFetcher { url, _ ->
        routes(url) ?: HttpReply(404, "")
    }

    @Test
    fun `search merges both providers and classifies covers and backgrounds`() = runBlocking {
        val catalog = MediaCatalog(
            fetcher { url ->
                when {
                    "search/autocomplete" in url -> HttpReply(200, sgdbSearch)
                    "grids/game/42" in url -> HttpReply(200, """{"data":[{"url":"https://sgdb/grid.png"}]}""")
                    "backgrounds/game/42" in url -> HttpReply(200, """{"data":[{"url":"https://sgdb/bg.png"}]}""")
                    "screenscraper" in url -> HttpReply(200, ssBody)
                    else -> null
                }
            },
        )

        val found = catalog.search("Zelda", "n64", ScraperCredentials("key", "user", "pw"))

        assertEquals(
            listOf(
                MediaCandidate("https://sgdb/grid.png", "steamgriddb", "cover"),
                MediaCandidate("https://sgdb/bg.png", "steamgriddb", "background"),
                MediaCandidate("https://ss/box.png", "screenscraper", "cover"),
                MediaCandidate("https://ss/shot.png", "screenscraper", "background"),
            ),
            found,
        )
    }

    @Test
    fun `one provider failing does not hide the other`() = runBlocking {
        val catalog = MediaCatalog(
            HttpFetcher { url, _ ->
                if ("steamgriddb" in url) throw IOException("offline")
                HttpReply(200, ssBody)
            },
        )

        val found = catalog.search("Zelda", "n64", ScraperCredentials("key", "user", "pw"))

        assertEquals(listOf("screenscraper", "screenscraper"), found.map { it.provider })
    }

    @Test
    fun `without credentials nothing is requested`() = runBlocking {
        var calls = 0
        val catalog = MediaCatalog(HttpFetcher { _, _ -> calls++; HttpReply(200, "{}") })

        assertTrue(catalog.search("Zelda", "n64", ScraperCredentials()).isEmpty())
        assertNull(catalog.fanartUrl("Zelda", ""))
        assertEquals(0, calls)
    }

    @Test
    fun `fanart asks for 1080p backgrounds and tolerates bad json`() {
        val requested = mutableListOf<String>()
        val catalog = MediaCatalog(
            fetcher { url ->
                requested += url
                when {
                    "search/autocomplete" in url -> HttpReply(200, sgdbSearch)
                    "backgrounds" in url -> HttpReply(200, """{"data":[{"url":"https://sgdb/hd.jpg"}]}""")
                    else -> null
                }
            },
        )
        assertEquals("https://sgdb/hd.jpg", catalog.fanartUrl("Zelda", "key"))
        assertTrue(requested.last().contains("dimensions=1920x1080"))

        val broken = MediaCatalog(fetcher { HttpReply(200, "<html>") })
        assertNull(broken.fanartUrl("Zelda", "key"))
    }
}
