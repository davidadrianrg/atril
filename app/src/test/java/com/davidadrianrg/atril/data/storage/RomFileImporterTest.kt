package com.davidadrianrg.atril.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Frontera de entrada del selector SAF: el nombre elegido por el usuario acaba
 * como nombre de fichero dentro de `<romsRoot>/<plataforma>`, así que no puede
 * contener separadores ni componentes de navegación.
 */
class RomFileImporterTest {

    @Test
    fun `a normal rom name is kept as is`() {
        assertEquals("Juego (USA).gba", sanitizeFileName("Juego (USA).gba"))
        assertEquals("Juego.m3u", sanitizeFileName("Juego.m3u"))
        assertEquals("Juego.apk", sanitizeFileName("Juego.apk"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Juego.gba", sanitizeFileName("   Juego.gba \n"))
    }

    @Test
    fun `control characters are removed`() {
        assertEquals("Juego.gba", sanitizeFileName("Ju\u0000ego\t.gba"))
    }

    @Test
    fun `a blank name has no safe form`() {
        assertNull(sanitizeFileName(""))
        assertNull(sanitizeFileName("   "))
    }

    @Test
    fun `dot and double dot are rejected`() {
        assertNull(sanitizeFileName("."))
        assertNull(sanitizeFileName(".."))
    }

    @Test
    fun `path separators are rejected`() {
        assertNull(sanitizeFileName("Juego/Parte.gba"))
        assertNull(sanitizeFileName("Juego\\Parte.gba"))
        assertNull(sanitizeFileName("/etc/passwd"))
    }

    @Test
    fun `traversal components are rejected`() {
        assertNull(sanitizeFileName("../Juego.gba"))
        assertNull(sanitizeFileName("..\\Juego.gba"))
        assertNull(sanitizeFileName("sub/../../Juego.gba"))
    }

    @Test
    fun `a name made only of control characters has no safe form`() {
        assertNull(sanitizeFileName("\u0000\t\n"))
    }
}
