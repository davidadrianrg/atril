package com.davidadrianrg.atril.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fondo cinematográfico: solo capturas (o fanart servido como captura).
 * La carátula nunca se reutiliza como fondo.
 */
class HeroBackdropTest {

    @Test
    fun `first non-blank screenshot wins`() {
        assertEquals(
            "https://cdn.atril.test/shot-1.png",
            CoverArtwork.heroBackdropUrl(
                listOf(
                    "https://cdn.atril.test/shot-1.png",
                    "https://cdn.atril.test/shot-2.png",
                ),
            ),
        )
    }

    @Test
    fun `blank screenshots are skipped`() {
        assertEquals(
            "https://cdn.atril.test/shot-2.png",
            CoverArtwork.heroBackdropUrl(listOf("", "   ", "https://cdn.atril.test/shot-2.png")),
        )
    }

    @Test
    fun `no screenshot means no hero even if the game has a cover`() {
        assertNull(CoverArtwork.heroBackdropUrl(emptyList()))
        assertNull(CoverArtwork.heroBackdropUrl(listOf(" ", "\t")))
    }

    @Test
    fun `returned url is trimmed`() {
        assertEquals(
            "https://cdn.atril.test/shot-1.png",
            CoverArtwork.heroBackdropUrl(listOf("  https://cdn.atril.test/shot-1.png ")),
        )
    }
}
