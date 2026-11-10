package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.i18n.I18n
import com.davidadrianrg.atril.core.remote.RomMApiService
import com.davidadrianrg.atril.core.remote.dto.RomDto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

/**
 * SEC-OOS-1 en el motor desktop: un zip `mod_zip` (Content-Length -1) pasa por
 * [DownloadEngine.download] con el cableado real (ResponseBody anónimo y
 * RomMApiService falso por Proxy, mismo patrón que DownloadEngineCancelTest)
 * y las entradas hostiles no crean nada fuera de `root/<slug>`.
 *
 * Los valores esperados salen del requisito: solo los ficheros legítimos se
 * extraen, el hermano con prefijo común no existe y el mensaje cuenta los
 * ficheros realmente creados.
 */
class DownloadEngineZipStreamTest {

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

    private val rom = RomDto(
        id = 1,
        name = "Test",
        fileName = "test.bin",
        platformId = 1,
        platformSlugRaw = "testplat",
    )

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun text(value: String): ByteArray = value.toByteArray()

    /** Cuerpo mod_zip: longitud desconocida para que el motor tome la rama de extracción. */
    private fun zipStreamBody(bytes: ByteArray): ResponseBody = object : ResponseBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = ByteArrayInputStream(bytes).source().buffer()
    }

    private fun fakeApi(bytes: ByteArray): RomMApiService =
        Proxy.newProxyInstance(
            RomMApiService::class.java.classLoader,
            arrayOf(RomMApiService::class.java),
        ) { _, method, _ ->
            check(method.name == "downloadRom") { "inesperado: ${method.name}" }
            Response.success(zipStreamBody(bytes))
        } as RomMApiService

    private fun targetDir(root: File): File = File(root, "testplat")

    @Test
    fun `mod_zip extracts only legitimate entries and the message counts them`() {
        val root = tmp.newFolder("roms")
        val absoluteEscape = File(tmp.root, "abs-out/x.bin").absolutePath
        val bytes = zipBytes(
            "../evil.txt" to text("evil"),
            "../testplatX/escape.bin" to text("sibling"),
            absoluteEscape to text("absolute"),
            "sub/rom.bin" to text("nested"),
            "root.bin" to text("single"),
        )
        val downloadEngine = DownloadEngine(fakeApi(bytes), root, tmp.newFolder("cache-count"))

        val result = runBlocking { downloadEngine.download(rom) }

        assertFalse(result.failed)
        assertEquals("Downloaded Test (2 files extracted)", result.message)
        assertEquals("nested", File(targetDir(root), "sub/rom.bin").readText())
        assertEquals("single", File(targetDir(root), "root.bin").readText())
        assertFalse(File(root, "evil.txt").exists())
        assertFalse(File(root, "testplatX").exists())
        assertFalse(File(tmp.root, "abs-out").exists())
    }

    @Test
    fun `a sibling sharing the platform folder prefix is never created`() {
        val root = tmp.newFolder("roms-prefix")
        val bytes = zipBytes(
            "../testplatX/y.bin" to text("sibling"),
            "ok.bin" to text("ok"),
        )
        val downloadEngine = DownloadEngine(fakeApi(bytes), root, tmp.newFolder("cache-prefix"))

        val result = runBlocking { downloadEngine.download(rom) }

        assertFalse(result.failed)
        assertFalse("el hermano testplatX no debe existir", File(root, "testplatX").exists())
        assertEquals("ok", File(targetDir(root), "ok.bin").readText())
    }

    @Test
    fun `a zip with only hostile entries succeeds with zero extracted files`() {
        val root = tmp.newFolder("roms-hostile")
        val absoluteEscape = File(tmp.root, "abs-only/x.bin").absolutePath
        val bytes = zipBytes(
            "../evil.txt" to text("evil"),
            "../testplatX/y.bin" to text("sibling"),
            absoluteEscape to text("absolute"),
        )
        val downloadEngine = DownloadEngine(fakeApi(bytes), root, tmp.newFolder("cache-hostile"))

        val result = runBlocking { downloadEngine.download(rom) }

        assertFalse("saltar entradas no es un fallo de descarga", result.failed)
        assertEquals("Downloaded Test (0 files extracted)", result.message)
        assertTrue("la carpeta de plataforma existe", targetDir(root).isDirectory)
        assertEquals(0, targetDir(root).walkTopDown().count { it.isFile })
    }

    @Test
    fun `a missing library does not break the zip stream flow`() {
        val root = tmp.newFolder("roms-no-library")
        val bytes = zipBytes("rom.bin" to text("rom"))
        val downloadEngine = DownloadEngine(
            fakeApi(bytes),
            root,
            tmp.newFolder("cache-no-library"),
            library = null,
        )

        val result = runBlocking { downloadEngine.download(rom) }

        assertFalse(result.failed)
        assertEquals("rom", File(targetDir(root), "rom.bin").readText())
    }
}
