package com.davidadrianrg.atril.data.emulators

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmulatorRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var repository: EmulatorRepository

    private val pack = """
        {"apps":[{"id":"org.ppsspp.ppsspp","url":"https://github.com/hrydgard/ppsspp",
          "author":"hrydgard","name":"PPSSPP","additionalSettings":"{}",
          "categories":["Emulator"]}]}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val context = mockk<Context> { every { filesDir } returns tmp.root }
        repository = EmulatorRepository(context, OkHttpClient(), server.url("/pack.json").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    // ── Paquete esperado del APK ───────────────────────────────────────

    @Test
    fun `learned or known packages are always enforced`() {
        assertEquals(
            "org.real.pkg",
            EmulatorRepository.resolveExpectedPackage("org.real.pkg", null, "org.pack.id") { false },
        )
        assertEquals(
            "com.retroarch.aarch64",
            EmulatorRepository.resolveExpectedPackage(null, "com.retroarch.aarch64", "x") { false },
        )
    }

    @Test
    fun `a pack id is only enforced when that package is already installed`() {
        assertEquals(
            "org.ppsspp.ppsspp",
            EmulatorRepository.resolveExpectedPackage(null, null, "org.ppsspp.ppsspp") { it == "org.ppsspp.ppsspp" },
        )
        // Instalación nueva: el id podría no ser el paquete real del APK.
        assertNull(EmulatorRepository.resolveExpectedPackage(null, null, "org.ppsspp.ppsspp") { false })
    }

    @Test
    fun `custom ids with dots are never mistaken for packages`() {
        assertNull(EmulatorRepository.resolveExpectedPackage(null, null, "custom:acme/emu.android") { true })
        assertNull(EmulatorRepository.resolveExpectedPackage("", null, "Some Name") { true })
    }

    // ── ETag del pack ──────────────────────────────────────────────────

    @Test
    fun `a 304 reuses the cached pack and sends the stored etag`() = runBlocking {
        server.enqueue(MockResponse().setBody(pack).setHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(304))

        repository.loadPack(force = true)
        val second = repository.loadPack(force = true)

        assertEquals(listOf("org.ppsspp.ppsspp"), second.map { it.id })
        server.takeRequest()
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `a response without etag forgets the stale one`() = runBlocking {
        server.enqueue(MockResponse().setBody(pack).setHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setBody(pack))
        server.enqueue(MockResponse().setBody(pack))

        repository.loadPack(force = true)
        repository.loadPack(force = true)
        repository.loadPack(force = true)

        server.takeRequest()
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-None-Match"))
        assertFalse(File(tmp.root, "emulator_pack.etag").exists())
    }

    @Test
    fun `a corrupt cache does not send the etag`() = runBlocking {
        File(tmp.root, "emulator_pack.json").writeText("{broken")
        File(tmp.root, "emulator_pack.etag").writeText("\"v1\"")
        server.enqueue(MockResponse().setBody(pack))

        val entries = repository.loadPack(force = false)

        assertEquals(1, entries.size)
        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }
}
