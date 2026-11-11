package com.davidadrianrg.atril.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Identidad persistida del estilo de carátula: ids estables, claves de
 * traducción canónicas y decodificación tolerante a valores ausentes o
 * corruptos.
 */
class CoverStyleTest {

    @Test
    fun `default style is case`() {
        assertEquals(CoverStyle.CASE, CoverStyle.DEFAULT)
        assertEquals("case", CoverStyle.DEFAULT.id)
    }

    @Test
    fun `persisted ids are exactly case, physical and screenshot`() {
        assertEquals(listOf("case", "physical", "screenshot"), CoverStyle.entries.map { it.id })
    }

    @Test
    fun `label keys are the canonical translation keys`() {
        assertEquals(
            listOf(
                "library.cover_style.case",
                "library.cover_style.physical",
                "library.cover_style.screenshot",
            ),
            CoverStyle.entries.map { it.labelKey },
        )
    }

    @Test
    fun `absent blank and unknown ids fall back to case`() {
        assertEquals(CoverStyle.CASE, CoverStyle.fromId(null))
        assertEquals(CoverStyle.CASE, CoverStyle.fromId(""))
        assertEquals(CoverStyle.CASE, CoverStyle.fromId("   "))
        assertEquals(CoverStyle.CASE, CoverStyle.fromId("bogus"))
    }

    @Test
    fun `decoding is case sensitive so mismatched ids fall back to case`() {
        assertEquals(CoverStyle.CASE, CoverStyle.fromId("Physical"))
        assertEquals(CoverStyle.CASE, CoverStyle.fromId("SCREENSHOT"))
        assertEquals(CoverStyle.CASE, CoverStyle.fromId("Case"))
    }

    @Test
    fun `canonical ids decode to their entry`() {
        assertEquals(CoverStyle.CASE, CoverStyle.fromId("case"))
        assertEquals(CoverStyle.PHYSICAL, CoverStyle.fromId("physical"))
        assertEquals(CoverStyle.SCREENSHOT, CoverStyle.fromId("screenshot"))
    }
}
