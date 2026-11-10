package com.davidadrianrg.atril.core.download

import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * SEC-OOS-1: el extractor de los zips de descarga (mod_zip) es la única puerta
 * de escritura de los dos motores y no puede crear nada fuera de la carpeta de
 * plataforma.
 *
 * Los zips son reales (ZipOutputStream sobre bytes) porque los nombres son
 * hostiles: `zipFolderDeterministic` nunca los generaría. Los valores esperados
 * salen del requisito (todo queda bajo `targetDir`, las entradas rechazadas se
 * saltan sin abortar el resto y un fallo no deja restos), no de la
 * implementación.
 */
class ZipStreamExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Construye un zip real con las entradas (nombre, contenido) indicadas. */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun text(value: String): ByteArray = value.toByteArray()

    private fun extract(bytes: ByteArray, targetDir: File): List<File> =
        ZipStreamExtractor.extract(ByteArrayInputStream(bytes), targetDir)

    @Test
    fun `traversal entries cannot create a sibling that shares the target prefix`() {
        val targetDir = tmp.newFolder("target")
        val sentinel = File(tmp.root, "keep.bin").apply { writeText("keep") }

        val created = extract(
            zipOf(
                "../evil.txt" to text("evil"),
                "../targetX/y.txt" to text("sibling"),
                "legit.sav" to text("ok"),
            ),
            targetDir,
        )

        assertFalse("no debe escribirse un traversal fuera de la base", File(tmp.root, "evil.txt").exists())
        assertFalse(
            "el hermano targetX comparte el prefijo textual de target y no debe crearse",
            File(tmp.root, "targetX").exists(),
        )
        assertEquals("keep", sentinel.readText())
        assertEquals(listOf(File(targetDir, "legit.sav").canonicalFile), created)
        assertEquals("ok", File(targetDir, "legit.sav").readText())
    }

    @Test
    fun `absolute entries never write outside the target directory`() {
        val targetDir = tmp.newFolder("target-abs")
        // Ruta absoluta real (bajo el TemporaryFolder) para que el fallo del
        // código antiguo fuese observable: tenemos permiso de escritura ahí.
        val absoluteEscape = File(tmp.root, "abs-out/x.bin").absolutePath

        val created = extract(
            zipOf(
                absoluteEscape to text("absolute"),
                "/abs/x" to text("root-absolute"),
                "legit.bin" to text("ok"),
            ),
            targetDir,
        )

        assertFalse("una entrada absoluta no debe crear el árbol abs-out", File(tmp.root, "abs-out").exists())
        assertEquals(listOf(File(targetDir, "legit.bin").canonicalFile), created)
        assertEquals("ok", File(targetDir, "legit.bin").readText())
    }

    @Test
    fun `extracts a legitimate nested archive and skips directory entries from the result`() {
        val targetDir = tmp.newFolder("target-nested")

        val created = extract(
            zipOf(
                "sub/rom.bin" to text("rom"),
                "a//b" to text("b"),
                "./a/" to ByteArray(0),
                "empty/" to ByteArray(0),
            ),
            targetDir,
        )

        assertEquals(
            listOf(
                File(targetDir, "sub/rom.bin").canonicalFile,
                File(targetDir, "a/b").canonicalFile,
            ),
            created,
        )
        assertEquals("rom", File(targetDir, "sub/rom.bin").readText())
        assertEquals("b", File(targetDir, "a/b").readText())
        assertTrue(File(targetDir, "a").isDirectory)
        assertTrue(File(targetDir, "empty").isDirectory)
    }

    @Test
    fun `resolves a leading dot component inside the target directory`() {
        val targetDir = tmp.newFolder("target-dot")

        val created = extract(zipOf("./a" to text("dot")), targetDir)

        assertEquals(listOf(File(targetDir, "a").canonicalFile), created)
        assertEquals("dot", File(targetDir, "a").readText())
    }

    @Test
    fun `creates a missing target directory`() {
        val targetDir = File(tmp.root, "missing/platform")
        assertFalse(targetDir.exists())

        val created = extract(zipOf("rom.bin" to text("rom")), targetDir)

        assertTrue(targetDir.isDirectory)
        assertEquals(listOf(File(targetDir, "rom.bin").canonicalFile), created)
        assertEquals("rom", File(targetDir, "rom.bin").readText())
    }

    @Test
    fun `a stream failure removes every created file including the partial one`() {
        val targetDir = tmp.newFolder("target-fail")
        val bytes = zipOf(
            "big.bin" to incompressible(256 * 1024),
            "next.bin" to text("next"),
        )
        val failure = IOException("disco lleno")

        try {
            extractWith(
                ExplodingInputStream(ByteArrayInputStream(bytes), failAfter = 8_192, failure = failure),
                targetDir,
            )
            fail("la IOException debe propagarse")
        } catch (e: IOException) {
            assertSame("se relanza la misma instancia", failure, e)
        }

        assertTrue("la carpeta destino sigue existiendo", targetDir.isDirectory)
        assertEquals(
            "no debe quedar ningún fichero, tampoco el parcial en curso",
            0,
            targetDir.walkTopDown().count { it.isFile },
        )
    }

    @Test
    fun `a cancellation removes every created file and is rethrown unchanged`() {
        val targetDir = tmp.newFolder("target-cancel")
        val bytes = zipOf(
            "big.bin" to incompressible(256 * 1024),
            "next.bin" to text("next"),
        )
        val cancellation = CancellationException("descarga cancelada")

        try {
            extractWith(
                ExplodingInputStream(ByteArrayInputStream(bytes), failAfter = 8_192, failure = cancellation),
                targetDir,
            )
            fail("la CancellationException debe propagarse")
        } catch (e: CancellationException) {
            assertSame("la cancelación nunca se envuelve", cancellation, e)
        }

        assertTrue(targetDir.isDirectory)
        assertEquals(0, targetDir.walkTopDown().count { it.isFile })
    }

    private fun extractWith(input: InputStream, targetDir: File): List<File> =
        ZipStreamExtractor.extract(input, targetDir)

    /** Contenido poco comprimible para que el fallo caiga dentro de la copia. */
    private fun incompressible(size: Int): ByteArray =
        ByteArray(size).also { java.util.Random(7L).nextBytes(it) }

    /**
     * Flujo que delega en [delegate] y lanza [failure] en cuanto se han leído
     * [failAfter] bytes, de modo que la excepción ocurre a mitad de la entrada
     * grande (ya registrada como creada).
     */
    private class ExplodingInputStream(
        delegate: InputStream,
        private val failAfter: Long,
        private val failure: Exception,
    ) : FilterInputStream(delegate) {

        private var consumed = 0L

        private fun charge(bytes: Int) {
            if (bytes <= 0) return
            consumed += bytes
            if (consumed >= failAfter) throw failure
        }

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) charge(1)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = super.read(b, off, len)
            charge(read)
            return read
        }
    }
}
