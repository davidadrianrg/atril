package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.i18n.I18n
import com.davidadrianrg.atril.core.remote.RomMApiService
import com.davidadrianrg.atril.core.remote.dto.RomDto
import java.io.File
import java.lang.reflect.Proxy
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

/**
 * SEC-OOS-4 en el motor desktop: la carpeta de descarga siempre queda bajo
 * `root/` y un slug ausente conserva el centinela histórico `unknown`.
 *
 * Nota de contrato: `RomDto.platformSlug` ya valida en la frontera del DTO
 * (misma regla `PlatformSlug` que prohíbe mayúsculas), de modo que el motor
 * recibe un slug válido o `null`; un valor en mayúsculas es hostil y cae a
 * `unknown`, nunca se reescribe a una carpeta nueva. La normalización
 * lowercase/trim de `PathMapper` (p. ej. `GBA` -> `gba`) se cubre directamente
 * en `PathMapperTest`, porque por este motor no puede llegar cruda.
 */
class DownloadEngineSlugTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val previousLocale = I18n.locale

    @Before
    fun useEnglishMessages() {
        I18n.locale = Locale.ENGLISH
    }

    @After
    fun restoreLocale() {
        I18n.locale = previousLocale
    }

    private fun fakeApi(content: ByteArray): RomMApiService =
        Proxy.newProxyInstance(
            RomMApiService::class.java.classLoader,
            arrayOf(RomMApiService::class.java),
        ) { _, method, _ ->
            check(method.name == "downloadRom") { "inesperado: ${method.name}" }
            Response.success(content.toResponseBody("application/octet-stream".toMediaType()))
        } as RomMApiService

    private fun rom(slug: String?): RomDto = RomDto(
        id = 1,
        name = "Test",
        fileName = "test.bin",
        platformId = 1,
        platformSlugRaw = slug,
    )

    private fun download(root: File, slug: String?): DownloadEngine.Result {
        val engine = DownloadEngine(fakeApi("rom-data".toByteArray()), root, tmp.newFolder("cache"))
        return runBlocking { engine.download(rom(slug)) }
    }

    @Test
    fun `a valid slug downloads under its own folder`() {
        val root = tmp.newFolder("roms")

        val result = download(root, "testplat")

        assertFalse(result.failed)
        assertEquals("rom-data", File(root, "testplat/test.bin").readText())
    }

    @Test
    fun `a missing slug uses the unknown sentinel`() {
        val root = tmp.newFolder("roms-missing")

        val result = download(root, null)

        assertFalse(result.failed)
        assertEquals("rom-data", File(root, "unknown/test.bin").readText())
    }

    @Test
    fun `an uppercase slug is rejected at the DTO boundary and never creates a folder`() {
        val root = tmp.newFolder("roms-uppercase")

        val result = download(root, "GBA")

        assertFalse(result.failed)
        assertEquals("rom-data", File(root, "unknown/test.bin").readText())
        assertFalse("no debe crearse 'GBA'", File(root, "GBA").exists())
        assertFalse("un valor hostil no se reescribe a 'gba'", File(root, "gba").exists())
    }
}
