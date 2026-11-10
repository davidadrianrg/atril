package com.davidadrianrg.atril.data.metadata

import com.davidadrianrg.atril.domain.model.Rom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-006: el slug con el que se compone la ruta del ROM y la carpeta de media
 * del gamelist.xml se normaliza a un componente seguro antes de construir
 * ninguna ruta. Un valor hostil cae en `unknown`; un slug válido no cambia.
 */
class GamelistWriterPlatformSlugTest {

    private fun rom(slug: String) = Rom(
        id = 1,
        name = "Gex",
        fileName = "x",
        fileSizeBytes = 0,
        platformId = 7,
        platformSlug = slug,
        coverUrlSmall = null,
        coverUrlLarge = "/assets/romm/resources/covers/gex.png",
        files = emptyList(),
        isMulti = false,
        revision = null,
        regions = emptyList(),
    )

    @Test
    fun `a hostile platform slug falls back to a safe unknown component`() {
        val entry = GamelistWriter.romToEntry(rom("../../evil"), "/roms", "../../evil", "x")

        assertEquals("./unknown/x", entry.path)
        assertTrue("la media debe ir bajo unknown", entry.image!!.startsWith("./downloaded_media/unknown/"))
        assertFalse("la ruta no puede contener '..'", entry.path.contains(".."))
        assertFalse("la media no puede contener '..'", entry.image!!.contains(".."))
    }

    @Test
    fun `a blank platform slug also falls back to unknown`() {
        val entry = GamelistWriter.romToEntry(rom(""), "/roms", "", "x")

        assertEquals("./unknown/x", entry.path)
    }

    @Test
    fun `a legitimate platform slug is preserved`() {
        val entry = GamelistWriter.romToEntry(rom("snes"), "/roms", "snes", "x")

        assertEquals("./snes/x", entry.path)
        assertEquals("./downloaded_media/snes/covers/x.jpg", entry.image)
    }
}
