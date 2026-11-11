package com.davidadrianrg.atril.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class SafeExtractionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bytes(text: String) = ByteArrayInputStream(text.toByteArray())

    @Test
    fun `writes nested file inside base`() {
        val base = tmp.newFolder("saves")
        val ok = SafeExtraction.extractEntry(base, "user/profile/save.srm", false, bytes("data"))
        assertTrue(ok)
        assertEquals("data", File(base, "user/profile/save.srm").readText())
    }

    @Test
    fun `creates directory entries`() {
        val base = tmp.newFolder("saves")
        assertTrue(SafeExtraction.extractEntry(base, "user/profile", true, null))
        assertTrue(File(base, "user/profile").isDirectory)
    }

    @Test
    fun `rejects path traversal`() {
        val base = tmp.newFolder("saves")
        assertFalse(SafeExtraction.extractEntry(base, "../escape.srm", false, bytes("x")))
        assertFalse(File(base.parentFile, "escape.srm").exists())
    }

    @Test
    fun `rejects intermediate symlink that escapes base`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val base = tmp.newFolder("saves")
        val outside = tmp.newFolder("outside")
        java.nio.file.Files.createSymbolicLink(
            File(base, "link").toPath(),
            outside.toPath(),
        )
        val ok = SafeExtraction.extractEntry(base, "link/evil.srm", false, bytes("x"))
        assertFalse(ok)
        assertFalse(File(outside, "evil.srm").exists())
    }

    @Test
    fun `overwrites an existing regular file`() {
        val base = tmp.newFolder("saves")
        File(base, "save.srm").writeText("old")
        assertTrue(SafeExtraction.extractEntry(base, "save.srm", false, bytes("new")))
        assertEquals("new", File(base, "save.srm").readText())
    }

    @Test
    fun `refuses to write through a symlinked file`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val base = tmp.newFolder("saves")
        val victim = File(tmp.root, "victim.srm").apply { writeText("safe") }
        java.nio.file.Files.createSymbolicLink(File(base, "save.srm").toPath(), victim.toPath())
        assertFalse(SafeExtraction.extractEntry(base, "save.srm", false, bytes("evil")))
        assertEquals("safe", victim.readText())
    }
}
