package com.davidadrianrg.atril.data.emulators

import com.davidadrianrg.atril.core.emulators.EmulatorEntry
import com.davidadrianrg.atril.core.emulators.PackIntermediateLink
import com.davidadrianrg.atril.core.emulators.PackSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Fuentes no-GitHub del pack (HTML, JSON, APK directo) contra un servidor local. */
class HtmlReleaseResolverTest {

    private lateinit var server: MockWebServer
    private val pages = mutableMapOf<String, MockResponse>()
    private val resolver = HtmlReleaseResolver(OkHttpClient())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                pages[request.path] ?: MockResponse().setResponseCode(404)
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun page(path: String, body: String) {
        pages[path] = MockResponse().setBody(body)
    }

    private fun url(path: String) = server.url(path).toString()

    private fun entry(
        repoUrl: String,
        linkFilter: String = "",
        versionRegex: String = "",
        group: String = "",
        wholePage: Boolean = false,
        reverseSort: Boolean = false,
        skipSort: Boolean = false,
        outsideATags: Boolean = false,
        intermediate: List<PackIntermediateLink> = emptyList(),
    ) = EmulatorEntry(
        id = "test",
        name = "Test",
        author = "",
        repoUrl = repoUrl,
        categories = emptyList(),
        settings = PackSettings(
            includePrereleases = false,
            apkFilterRegEx = "",
            versionExtractionRegEx = versionRegex,
            releaseTitleAsVersion = false,
            preferredApkIndex = 0,
            autoApkFilterByArch = false,
            about = "",
            customLinkFilterRegex = linkFilter,
            intermediateLink = intermediate,
            versionExtractWholePage = wholePage,
            matchGroupToUse = group,
            skipSort = skipSort,
            reverseSort = reverseSort,
            matchLinksOutsideATags = outsideATags,
        ),
    )

    @Test
    fun `direct apk needs no request and reports an unknown version`() = runBlocking {
        val release = resolver.resolve(entry("https://example.invalid/dl/emu-arm64.apk?x=1"))!!
        assertEquals("latest", release.version)
        assertEquals("emu-arm64.apk", release.assetName)
        assertEquals("https://example.invalid/dl/emu-arm64.apk?x=1", release.downloadUrl)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `html source picks the highest version and resolves relative links`() = runBlocking {
        page(
            "/downloads",
            """
            <a href="files/emu-1.2.0.apk">old</a>
            <a href='/files/emu-1.10.0.apk'>new</a>
            <a href="files/readme.txt">readme</a>
            """.trimIndent(),
        )
        val release = resolver.resolve(entry(url("/downloads"), versionRegex = """\d+(\.\d+)+"""))!!
        assertEquals(url("/files/emu-1.10.0.apk"), release.downloadUrl)
        assertEquals("1.10.0", release.version)
        assertEquals("emu-1.10.0.apk", release.assetName)
    }

    @Test
    fun `link filter and reverse sort select the expected candidate`() = runBlocking {
        page(
            "/list",
            """
            <a href="/a/emu-2.0-x86.apk">x</a>
            <a href="/a/emu-2.0-arm64.apk">a</a>
            <a href="/a/emu-1.0-arm64.apk">b</a>
            """.trimIndent(),
        )
        val newest = resolver.resolve(entry(url("/list"), linkFilter = "arm64"))!!
        assertEquals(url("/a/emu-2.0-arm64.apk"), newest.downloadUrl)
        val oldest = resolver.resolve(entry(url("/list"), linkFilter = "arm64", reverseSort = true))!!
        assertEquals(url("/a/emu-1.0-arm64.apk"), oldest.downloadUrl)
    }

    @Test
    fun `intermediate link is followed before looking for the apk`() = runBlocking {
        page("/", """<a href="/release/3.1/">3.1</a><a href="/release/3.0/">3.0</a>""")
        page("/release/3.1/", """<a href="emu.apk">get</a>""")
        val release = resolver.resolve(
            entry(
                url("/"),
                versionRegex = """release/([\d.]+)""",
                group = "$1",
                wholePage = false,
                intermediate = listOf(PackIntermediateLink("release/", false, false, false)),
            ),
        )!!
        assertEquals(url("/release/3.1/emu.apk"), release.downloadUrl)
        assertEquals("3.1", release.version)
    }

    @Test
    fun `version can be read from the whole page with a match group`() = runBlocking {
        page("/p", """<h1>Emu v4.2.1</h1><a href="/emu-latest.apk">dl</a>""")
        val release = resolver.resolve(
            entry(url("/p"), versionRegex = """Emu v([\d.]+)""", group = "$1", wholePage = true),
        )!!
        assertEquals("4.2.1", release.version)
        assertEquals(release.version, release.tagName)
    }

    @Test
    fun `apk links outside anchors are used when nothing else matches`() = runBlocking {
        page("/raw", """download: https://cdn.example.invalid/emu-9.apk (mirror)""")
        val release = resolver.resolve(entry(url("/raw")))!!
        assertEquals("https://cdn.example.invalid/emu-9.apk", release.downloadUrl)
        assertEquals("latest", release.version)
    }

    @Test
    fun `json source prefers the standard build`() = runBlocking {
        page(
            "/releases.json",
            """
            {"assets":[
              {"url":"https://cdn.example.invalid/eden-legacy-0.5.apk"},
              {"url":"https://cdn.example.invalid/eden-standard-0.5.apk"},
              {"url":"https://cdn.example.invalid/notes.txt"}
            ]}
            """.trimIndent(),
        )
        val release = resolver.resolve(entry(url("/releases.json"), versionRegex = """\d+\.\d+"""))!!
        assertEquals("https://cdn.example.invalid/eden-standard-0.5.apk", release.downloadUrl)
        assertEquals("0.5", release.version)
    }

    @Test
    fun `http errors and pages without apks resolve to null`() = runBlocking {
        assertNull(resolver.resolve(entry(url("/missing"))))
        page("/empty", "<p>nothing here</p>")
        assertNull(resolver.resolve(entry(url("/empty"))))
    }

    @Test
    fun `requests identify as Obtainium`() = runBlocking {
        page("/ua", """<a href="/x.apk">x</a>""")
        resolver.resolve(entry(url("/ua")))
        assertEquals("Obtainium/1.0", server.takeRequest().getHeader("User-Agent"))
    }
}
