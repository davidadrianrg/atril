package com.davidadrianrg.atril.data.backup

import android.content.Context
import com.davidadrianrg.atril.core.localgames.ScraperCredentials
import com.davidadrianrg.atril.data.emulators.CustomEmulatorStore
import com.davidadrianrg.atril.data.local.ServerConfig
import com.davidadrianrg.atril.data.local.SettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Copia de seguridad: lo que exporta, que la importación aplica cada campo
 * (incluidas credenciales y el aviso al cliente HTTP) y que rechaza JSON
 * ajeno o de una versión de esquema más nueva. DataStore es un doble.
 */
class BackupManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val settings = mockk<SettingsDataStore>(relaxed = true)
    private val server = MutableStateFlow(ServerConfig("", "", "", 2))
    private val apiKey = MutableStateFlow("")
    private val serverChanges = mutableListOf<Pair<String, String>>()
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk { every { filesDir } returns tmp.root }
        every { settings.theme } returns flowOf("crimson")
        every { settings.launcherBackground } returns flowOf("fanart")
        every { settings.launcherBlur } returns flowOf(12f)
        every { settings.language } returns flowOf("es")
        every { settings.settings } returns server
        every { settings.getApiKeyBlocking() } answers { apiKey.value }
        every { settings.scraperCredentials } returns flowOf(ScraperCredentials("sgdb", "user", "pw"))
        coEvery { settings.setServerUrl(any()) } answers { server.value = server.value.copy(serverUrl = firstArg()) }
        coEvery { settings.setApiKey(any()) } answers {
            apiKey.value = firstArg()
            server.value = server.value.copy(apiKey = firstArg())
        }
    }

    private fun manager() = BackupManager(context, settings) { url, key -> serverChanges += url to key }

    /** org.json es un stub en tests JVM: se lee con Moshi, igual que la app. */
    private fun parse(json: String): Map<String, Any?> =
        com.squareup.moshi.Moshi.Builder().build()
            .adapter<Map<String, Any?>>(
                com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
            )
            .fromJson(json)!!

    private fun customStore() = CustomEmulatorStore(File(tmp.root, "custom_emulators.json"))

    @Test
    fun `export without secrets carries preferences and custom emulators only`() = runBlocking {
        customStore().add("https://github.com/acme/emu", "Emu", "arm64", prereleases = true)

        val json = parse(manager().export(includeSecrets = false))

        assertEquals(BackupManager.SCHEMA_VERSION, (json["version"] as Number).toInt())
        assertEquals("crimson", json["theme"])
        assertEquals("fanart", json["launcherBackground"])
        assertEquals(12.0, (json["launcherBlur"] as Number).toDouble(), 0.0)
        assertEquals("es", json["language"])
        @Suppress("UNCHECKED_CAST")
        val emu = (json["customEmulators"] as List<Map<String, Any?>>).single()
        assertEquals("https://github.com/acme/emu", emu["repoUrl"])
        assertEquals("arm64", emu["apkFilterRegEx"])
        assertEquals(true, emu["includePrereleases"])
        assertFalse(json.containsKey("apiKey"))
        assertFalse(json.containsKey("serverUrl"))
        assertFalse(json.containsKey("scraper"))
    }

    @Test
    fun `export with secrets includes server, key and scraper credentials`() = runBlocking {
        server.value = ServerConfig("http://romm.local", "secret", "", 2)
        apiKey.value = "secret"

        val json = parse(manager().export(includeSecrets = true))

        assertEquals("http://romm.local", json["serverUrl"])
        assertEquals("secret", json["apiKey"])
        @Suppress("UNCHECKED_CAST")
        val scraper = json["scraper"] as Map<String, Any?>
        assertEquals("sgdb", scraper["steamGridDbKey"])
        assertEquals("pw", scraper["ssPassword"])
    }

    @Test
    fun `import applies every field and reconfigures the server client`() = runBlocking {
        val json = """
            {"version":1,"theme":"amoled","launcherBackground":"solid","launcherBlur":4,
             "language":"en","serverUrl":"http://new.host","apiKey":"k2",
             "scraper":{"steamGridDbKey":"g","ssUser":"u","ssPassword":"p"},
             "customEmulators":[{"repoUrl":"https://github.com/acme/emu","name":"Emu",
               "apkFilterRegEx":"","includePrereleases":false,"packageName":"org.acme.emu"}]}
        """.trimIndent()

        assertEquals(BackupImportResult.OK, manager().import(json))

        coVerify { settings.setTheme("amoled") }
        coVerify { settings.setLauncherBackground("solid") }
        coVerify { settings.setLauncherBlur(4f) }
        coVerify { settings.setLanguage("en") }
        coVerify { settings.setServerUrl("http://new.host") }
        coVerify { settings.setApiKey("k2") }
        coVerify { settings.setLocalOnly(false) }
        coVerify { settings.setScraperCredentials(ScraperCredentials("g", "u", "p")) }
        assertEquals(listOf("http://new.host" to "k2"), serverChanges)

        val emu = customStore().all().single()
        assertEquals("custom:acme/emu", emu.id)
        assertEquals("org.acme.emu", emu.packageName)
    }

    @Test
    fun `importing only a key keeps local-only untouched but refreshes the client`() = runBlocking {
        server.value = ServerConfig("http://old.host", "old", "", 2)

        assertEquals(BackupImportResult.OK, manager().import("""{"version":1,"apiKey":"fresh"}"""))

        coVerify(exactly = 0) { settings.setLocalOnly(any()) }
        assertEquals(listOf("http://old.host" to "fresh"), serverChanges)
    }

    @Test
    fun `export then import round-trips the custom emulators`() = runBlocking {
        customStore().add("https://github.com/acme/emu", "Emu", "", prereleases = false)
        customStore().setPackageName("custom:acme/emu", "org.acme.emu")
        val json = manager().export(includeSecrets = false)
        File(tmp.root, "custom_emulators.json").delete()

        assertEquals(BackupImportResult.OK, manager().import(json))

        assertEquals("org.acme.emu", customStore().get("custom:acme/emu")?.packageName)
        assertTrue(serverChanges.isEmpty())
    }

    @Test
    fun `rejects foreign json, missing version and newer schemas without touching settings`() = runBlocking {
        val m = manager()
        assertEquals(BackupImportResult.INVALID, m.import("not json"))
        assertEquals(BackupImportResult.INVALID, m.import("""{"theme":"x"}"""))
        assertEquals(
            BackupImportResult.UNSUPPORTED_VERSION,
            m.import("""{"version":${BackupManager.SCHEMA_VERSION + 1},"theme":"x","apiKey":"k"}"""),
        )
        coVerify(exactly = 0) { settings.setTheme(any()) }
        coVerify(exactly = 0) { settings.setApiKey(any()) }
        assertTrue(serverChanges.isEmpty())
    }
}
