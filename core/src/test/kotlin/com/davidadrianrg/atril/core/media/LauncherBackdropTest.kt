package com.davidadrianrg.atril.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherBackdropTest {

    @Test
    fun `solid never paints an image`() {
        assertNull(LauncherBackdrop.choose("solid", "/bg.jpg", "/fan.jpg", "http://shot"))
    }

    @Test
    fun `a chosen game background wins over fanart and screenshot`() {
        assertEquals("/bg.jpg", LauncherBackdrop.choose("blur", "/bg.jpg", null, "http://shot"))
        assertEquals("/bg.jpg", LauncherBackdrop.choose("fanart", "/bg.jpg", "/fan.jpg", "http://shot"))
    }

    @Test
    fun `fanart falls back to the screenshot until it is downloaded`() {
        assertEquals("/fan.jpg", LauncherBackdrop.choose("fanart", null, "/fan.jpg", "http://shot"))
        assertEquals("http://shot", LauncherBackdrop.choose("fanart", null, null, "http://shot"))
        assertEquals("http://shot", LauncherBackdrop.choose("blur", null, "/fan.jpg", "http://shot"))
    }

    @Test
    fun `unknown modes behave as blur`() {
        assertEquals("blur", LauncherBackdrop.normalizeMode("neon"))
        assertEquals("http://shot", LauncherBackdrop.choose("neon", null, "/fan.jpg", "http://shot"))
    }

    @Test
    fun `game background map round-trips and ignores junk`() {
        val raw = LauncherBackdrop.withGameBackground("", 7, "/a.jpg")
        val two = LauncherBackdrop.withGameBackground(raw, 3, "/b.jpg")
        assertEquals("3=/b.jpg;7=/a.jpg", two)
        assertEquals(mapOf(7 to "/a.jpg"), LauncherBackdrop.decodeGameBackgrounds(LauncherBackdrop.withGameBackground(two, 3, null)))
        assertEquals(mapOf(1 to "/x"), LauncherBackdrop.decodeGameBackgrounds("1=/x;bad;=;2="))
        // Un `;` en la ruta rompería el formato: no se guarda.
        assertEquals("", LauncherBackdrop.withGameBackground("", 4, "/a;b.jpg"))
    }
}
