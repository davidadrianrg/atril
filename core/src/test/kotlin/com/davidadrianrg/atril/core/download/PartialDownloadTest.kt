package com.davidadrianrg.atril.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * M1/F3: el ROM a medio descargar vive en `.part` y solo aparece con su nombre
 * final al terminar; los parciales de versiones anteriores (con el nombre
 * final) siguen sirviendo para reanudar.
 */
class PartialDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun target(): File = File(tmp.newFolder("gba"), "Game.gba")

    @Test
    fun `fresh download starts from zero on the part file`() {
        val target = target()
        val point = PartialDownload.resumePoint(target)
        assertEquals(PartialDownload.partFileFor(target), point.file)
        assertEquals(0L, point.bytes)
        assertEquals("Game.gba.part", point.file.name)
    }

    @Test
    fun `existing part file is the resume point`() {
        val target = target()
        PartialDownload.partFileFor(target).writeText("12345")
        val point = PartialDownload.resumePoint(target)
        assertEquals(PartialDownload.partFileFor(target), point.file)
        assertEquals(5L, point.bytes)
    }

    @Test
    fun `legacy partial with the final name resumes into the part file`() {
        val target = target()
        target.writeText("abc")
        val point = PartialDownload.resumePoint(target)
        assertEquals(target, point.file)
        assertEquals(3L, point.bytes)

        val out = PartialDownload.prepareAppend(point, target, resume = true)
        assertEquals(PartialDownload.partFileFor(target), out)
        assertFalse("the truncated ROM no longer has the final name", target.exists())
        out.appendText("def")
        PartialDownload.commit(out, target)
        assertEquals("abcdef", target.readText())
        assertFalse(out.exists())
    }

    @Test
    fun `restart from zero discards a stale part but keeps the old ROM until commit`() {
        val target = target()
        target.writeText("old complete rom")
        PartialDownload.partFileFor(target).writeText("stale")
        val point = PartialDownload.resumePoint(target)

        val out = PartialDownload.prepareAppend(point, target, resume = false)
        assertFalse(out.exists())
        assertEquals("old complete rom", target.readText())

        out.writeText("new rom")
        PartialDownload.commit(out, target)
        assertEquals("new rom", target.readText())
        assertFalse(out.exists())
    }

    @Test
    fun `commit of a legacy file confirmed complete is a no-op`() {
        val target = target()
        target.writeText("complete")
        PartialDownload.commit(target, target)
        assertEquals("complete", target.readText())
    }

    @Test
    fun `discard removes only the part file`() {
        val target = target()
        target.writeText("rom")
        PartialDownload.partFileFor(target).writeText("partial")
        assertTrue(PartialDownload.discard(target))
        assertFalse(PartialDownload.partFileFor(target).exists())
        assertTrue(target.exists())
        assertTrue("nothing to discard is still a success", PartialDownload.discard(target))
    }
}
