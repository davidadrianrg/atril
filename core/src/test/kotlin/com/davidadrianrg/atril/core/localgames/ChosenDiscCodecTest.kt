package com.davidadrianrg.atril.core.localgames

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Códec del mapa `clave de grupo -> disco elegido` que persisten Android y
 * escritorio. Debe ser determinista y no lanzar nunca al leer.
 */
class ChosenDiscCodecTest {

    @Test
    fun `round-trips keys and ids with awkward characters`() {
        val choices = mapOf(
            "ps1|final fantasy vii" to "@playlist",
            "snes/game|x = y;z" to "disc/1|2 = 3;4",
            "a b" to "c d",
            "acentos|ñandú" to "disco-2",
        )

        val encoded = ChosenDiscCodec.encode(choices)

        assertEquals(choices, ChosenDiscCodec.decode(encoded))
    }

    @Test
    fun `encode is deterministic and sorted by key`() {
        val first = ChosenDiscCodec.encode(linkedMapOf("b" to "2", "a" to "1"))
        val second = ChosenDiscCodec.encode(linkedMapOf("a" to "1", "b" to "2"))

        assertEquals("a=1;b=2", first)
        assertEquals(first, second)
    }

    @Test
    fun `an empty map encodes to empty and decodes to empty`() {
        assertEquals("", ChosenDiscCodec.encode(emptyMap()))
        assertEquals(emptyMap<String, String>(), ChosenDiscCodec.decode(""))
        assertEquals(emptyMap<String, String>(), ChosenDiscCodec.decode(null))
        assertEquals(emptyMap<String, String>(), ChosenDiscCodec.decode("   "))
    }

    @Test
    fun `malformed entries are skipped without throwing`() {
        val decoded = ChosenDiscCodec.decode(
            "good=1;;no-equals;=missing-key;key=;bad%zz=1;other=2",
        )

        assertEquals("1", decoded["good"])
        assertEquals("2", decoded["other"])
        assertEquals(2, decoded.size)
    }
}
