package com.davidadrianrg.atril.core.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverOverridesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    private fun overrides(maxBytes: Long = 1024) = CoverOverrides(
        file = File(tmp.root, "cover_overrides.json"),
        dir = File(tmp.root, "covers"),
        maxBytes = maxBytes,
        clock = { now++ },
    )

    @Test
    fun `each change gets a new file, the old one is deleted and state persists`() {
        val covers = overrides()
        assertTrue(covers.setFromStream(7, png.inputStream()))
        val first = covers.get(7)!!
        assertTrue(covers.setFromStream(7, png.inputStream()))
        val second = covers.get(7)!!

        // Nombre distinto: los cachés de imagen van por URL.
        assertNotEquals(first, second)
        assertFalse(File(first).exists())
        assertEquals(second, overrides().get(7))
        assertEquals(mapOf(7 to second), covers.covers.value)
    }

    @Test
    fun `rejects non images and oversized files without touching the current cover`() {
        val covers = overrides(maxBytes = 16)
        assertTrue(covers.setFromStream(-3, png.inputStream()))
        val current = covers.get(-3)

        assertFalse(covers.setFromStream(-3, "hello world".byteInputStream()))
        assertFalse(covers.setFromStream(-3, (png + ByteArray(64)).inputStream()))
        assertFalse(covers.setFromStream(-3, ByteArray(0).inputStream()))

        assertEquals(current, covers.get(-3))
        assertEquals(listOf(File(current!!).name), File(tmp.root, "covers").list()!!.toList())
    }

    @Test
    fun `clear deletes the file and a cover removed from disk falls back to none`() {
        val covers = overrides()
        covers.setFromStream(1, png.inputStream())
        covers.setFromStream(2, png.inputStream())
        val path1 = covers.get(1)!!

        covers.clear(1)
        assertNull(covers.get(1))
        assertFalse(File(path1).exists())

        File(covers.get(2)!!).delete()
        assertNull(overrides().get(2))
    }

    @Test
    fun `never deletes a file outside its own directory`() {
        val foreign = File(tmp.root, "mine.png").apply { writeBytes(png) }
        File(tmp.root, "cover_overrides.json").writeText("""{"5":"${foreign.absolutePath}"}""")
        val covers = overrides()
        assertEquals(foreign.absolutePath, covers.get(5))

        covers.clear(5)

        assertTrue(foreign.exists())
        assertNull(covers.get(5))
    }
}
