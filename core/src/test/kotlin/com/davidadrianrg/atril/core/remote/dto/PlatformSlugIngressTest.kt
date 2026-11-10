package com.davidadrianrg.atril.core.remote.dto

import com.davidadrianrg.atril.core.remote.PlatformSlug
import com.davidadrianrg.atril.core.sync.platform.RetroArchSaveHandler
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SEC-006: `platform_slug` es dato del servidor y se usa como componente de
 * ruta. La regla de producto es un único validador (minúsculas, alfanumérico
 * inicial y hasta 64 caracteres de `[a-z0-9._-]`) aplicado en la frontera del
 * DTO, más una barrera defensiva en el destino de RetroArch.
 *
 * Los valores esperados salen de esa regla y del layout documentado
 * (`saves/<slug>/`), no de la implementación.
 */
class PlatformSlugIngressTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(RomDto::class.java)

    private val validSlugs = listOf("gba", "ps1", "3do", "n64", "android-games", "wii-u", "snes")

    private val hostileSlugs = listOf(
        "../../../../.config/autostart",
        "..",
        "/abs",
        "a/b",
        "",
        "a".repeat(65),
        "GBA",
        " gba",
    )

    private fun romJson(slug: String?): String {
        val encoded = if (slug == null) "null" else "\"$slug\""
        return """{"id":1,"name":"Gex","fs_name":"Gex.chd","platform_id":1,"platform_slug":$encoded}"""
    }

    @Test
    fun `accepts the documented valid slugs`() {
        validSlugs.forEach { slug ->
            assertTrue("debe aceptar '$slug'", PlatformSlug.isValid(slug))
            assertEquals("no debe reescribir '$slug'", slug, PlatformSlug.sanitize(slug))
        }
    }

    @Test
    fun `rejects hostile and malformed slugs`() {
        (hostileSlugs + listOf(".", "a b", "gba/", "-gba")).forEach { slug ->
            assertFalse("debe rechazar '$slug'", PlatformSlug.isValid(slug))
            assertNull("debe sanear a null '$slug'", PlatformSlug.sanitize(slug))
        }
        assertFalse(PlatformSlug.isValid(null))
        assertNull(PlatformSlug.sanitize(null))
    }

    @Test
    fun `moshi parses valid slugs verbatim`() {
        validSlugs.forEach { slug ->
            val dto = adapter.fromJson(romJson(slug))!!
            assertEquals(slug, dto.platformSlug)
        }
    }

    @Test
    fun `moshi neutralizes hostile slugs without throwing`() {
        hostileSlugs.forEach { slug ->
            val dto = adapter.fromJson(romJson(slug))!!
            assertNull("'$slug' no debe llegar a una ruta", dto.platformSlug)
        }
    }

    @Test
    fun `a missing platform slug stays null`() {
        val dto = adapter.fromJson("""{"id":1,"name":"Gex","fs_name":"Gex.chd","platform_id":1}""")!!

        assertNull(dto.platformSlug)
    }

    @Test
    fun `a directly constructed DTO with a hostile slug exposes null`() {
        val dto = RomDto(
            id = 1,
            name = "Gex",
            fileName = "Gex.chd",
            platformId = 1,
            platformSlugRaw = "../..",
        )

        assertNull(dto.platformSlug)
    }

    @Test
    fun `retroarch with a hostile slug writes only under the saves root`() = runTest {
        val base = tmp.newFolder("ra")
        val download = File(tmp.root, "download.srm").apply { writeText("save-data") }

        val ok = RetroArchSaveHandler().extractDownload(
            tempFile = download,
            romFileName = "g.chd",
            platformSlug = "../../../../.config/autostart",
            savesBasePath = base.absolutePath,
            targetFileName = "game.srm",
        )

        assertTrue(ok)
        assertEquals("save-data", File(base, "saves/game.srm").readText())
        assertFalse(
            "no debe crearse nada en el HOME",
            File(tmp.root, ".config/autostart").exists(),
        )
    }

    @Test
    fun `retroarch with a legitimate slug keeps the per-platform folder`() = runTest {
        val base = tmp.newFolder("ra-legit")
        val download = File(tmp.root, "download-legit.srm").apply { writeText("gba-save") }

        val ok = RetroArchSaveHandler().extractDownload(
            tempFile = download,
            romFileName = "g.chd",
            platformSlug = "gba",
            savesBasePath = base.absolutePath,
            targetFileName = "game.srm",
        )

        assertTrue(ok)
        assertEquals("gba-save", File(base, "saves/gba/game.srm").readText())
    }
}
