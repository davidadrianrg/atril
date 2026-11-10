package com.davidadrianrg.atril.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * SEC-OOS-3: el vaciado de un destino de restauración solo puede borrar
 * contenido que siga canónicamente bajo la base de confianza.
 *
 * Árboles reales con [TemporaryFolder]; los enlaces simbólicos se crean de
 * verdad y se omiten con [Assume] en sistemas de ficheros que no los soportan.
 * Los asserts son sobre el disco: el contenido exterior se conserva, el
 * directorio destino sigue en pie y los hijos legítimos se borran.
 */
class SafeDeleteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun symlink(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: Exception) {
            Assume.assumeTrue("el sistema de ficheros no admite enlaces simbólicos: ${e.message}", false)
        }
    }

    @Test
    fun `empties the contents when base and target are the same directory`() {
        val base = tmp.newFolder("base")
        File(base, "a.txt").writeText("a")
        File(base, "sub/b.txt").apply { parentFile!!.mkdirs(); writeText("b") }

        SafeDelete.clearDirectoryContents(base, base)

        assertTrue("el directorio destino nunca se borra", base.isDirectory)
        assertTrue(base.listFiles()!!.isEmpty())
    }

    @Test
    fun `does nothing when the target does not exist`() {
        val base = tmp.newFolder("base")
        val target = File(tmp.root, "missing")

        SafeDelete.clearDirectoryContents(base, target)

        assertFalse(target.exists())
        assertTrue(base.listFiles()!!.isEmpty())
    }

    @Test
    fun `deletes a child symlink without following it outside the base`() {
        val base = tmp.newFolder("base")
        val target = File(base, "target").also { it.mkdirs() }
        File(target, "legit.bin").writeText("legit")
        val outside = tmp.newFolder("outside")
        File(outside, "keep.bin").writeText("keep")
        val escape = File(target, "escape")
        symlink(escape, outside)

        SafeDelete.clearDirectoryContents(base, target)

        assertEquals("el contenido exterior se conserva", "keep", File(outside, "keep.bin").readText())
        assertFalse("el enlace se elimina sin seguir su destino", Files.exists(escape.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(File(target, "legit.bin").exists())
        assertTrue("el destino sigue existiendo", target.isDirectory)
    }

    @Test
    fun `deletes a target that is a symlink outside the base without touching its contents`() {
        val base = tmp.newFolder("base")
        val outside = tmp.newFolder("outside")
        File(outside, "keep.bin").writeText("keep")
        val link = File(base, "linked-target")
        symlink(link, outside)

        SafeDelete.clearDirectoryContents(base, link)

        assertEquals("el contenido exterior se conserva", "keep", File(outside, "keep.bin").readText())
        assertTrue("el directorio exterior sigue existiendo", outside.isDirectory)
        assertFalse("como máximo se elimina el enlace", Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `deletes a nested child symlink at depth two without following it outside the base`() {
        val base = tmp.newFolder("base")
        val target = File(base, "target").also { it.mkdirs() }
        val sub = File(target, "sub").also { it.mkdirs() }
        val outside = tmp.newFolder("outside")
        File(outside, "keep.bin").writeText("keep")
        File(outside, "nested/deep.bin").apply { parentFile!!.mkdirs(); writeText("deep") }
        val nestedEscape = File(sub, "evil")
        symlink(nestedEscape, outside)

        SafeDelete.clearDirectoryContents(base, target)

        assertEquals("el fichero exterior se conserva", "keep", File(outside, "keep.bin").readText())
        assertEquals("el subárbol exterior se conserva", "deep", File(outside, "nested/deep.bin").readText())
        assertFalse(
            "el enlace anidado se elimina sin seguir su destino",
            Files.exists(nestedEscape.toPath(), LinkOption.NOFOLLOW_LINKS)
        )
        assertFalse("el contenedor legítimo se elimina tras vaciarlo", sub.exists())
        assertTrue("el destino sigue en pie", target.isDirectory)
        assertTrue("el destino queda vacío", target.listFiles()!!.isEmpty())
    }

    @Test
    fun `deletes a nested symlink to a directory inside the base without deleting its destination`() {
        val base = tmp.newFolder("base")
        val target = File(base, "target").also { it.mkdirs() }
        val sub = File(target, "sub").also { it.mkdirs() }
        val sibling = File(base, "sibling").also { it.mkdirs() }
        File(sibling, "keep.bin").writeText("keep")
        val internalLink = File(sub, "link")
        symlink(internalLink, sibling)

        SafeDelete.clearDirectoryContents(base, target)

        assertEquals(
            "el directorio destino dentro de la base no se destruye",
            "keep",
            File(sibling, "keep.bin").readText()
        )
        assertTrue("el directorio destino sigue existiendo", sibling.isDirectory)
        assertFalse(
            "solo se elimina la entrada del enlace",
            Files.exists(internalLink.toPath(), LinkOption.NOFOLLOW_LINKS)
        )
        assertFalse(sub.exists())
        assertTrue(target.isDirectory)
        assertTrue(target.listFiles()!!.isEmpty())
    }

    @Test
    fun `deletes a legitimate subdirectory recursively`() {
        val base = tmp.newFolder("base")
        val target = File(base, "target").also { it.mkdirs() }
        File(target, "sub/deep/file.bin").apply { parentFile!!.mkdirs(); writeText("data") }

        SafeDelete.clearDirectoryContents(base, target)

        assertTrue(target.isDirectory)
        assertTrue(target.listFiles()!!.isEmpty())
        assertFalse(File(target, "sub").exists())
    }
}
