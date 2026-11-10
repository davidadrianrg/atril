package com.davidadrianrg.atril.data.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.davidadrianrg.atril.core.i18n.I18n
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Frontera de entrada del selector SAF: el nombre elegido por el usuario acaba
 * como nombre de fichero dentro de `<romsRoot>/<plataforma>`, así que no puede
 * contener separadores ni componentes de navegación.
 *
 * Además cubre el camino de E/S real de [RomFileImporter] con un
 * `ContentResolver` de Robolectric: stream nulo, stream que falla a mitad,
 * destino existente sin confirmación, reemplazo confirmado y rechazo de
 * symlinks (SEC-002/SEC-003). Nunca hay dispositivo ni proveedor real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RomFileImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var platformDir: File
    private lateinit var importer: RomFileImporter
    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousLocale = I18n.locale
        I18n.locale = Locale.ENGLISH
        context = RuntimeEnvironment.getApplication()
        root = tmp.newFolder("roms")
        platformDir = File(root, "gba").apply { mkdirs() }
        importer = RomFileImporter(context)
    }

    @After
    fun tearDown() {
        I18n.locale = previousLocale
    }

    // ── Nombre saneado (función pura) ──────────────────────────────────

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

    // ── Camino de E/S (REV-009) ────────────────────────────────────────

    @Test
    fun `a provider without a stream fails and leaves no partial file`() = runBlocking {
        Robolectric.buildContentProvider(NullStreamProvider::class.java).create("atril.test.null")
        val uri = Uri.parse("content://atril.test.null/Game.gba")

        val result = importer.import(uri, platformDir, "Game.gba", overwrite = false, rootDir = root)

        assertEquals(RomImportResult.Failed("Couldn't copy the file"), result)
        assertTrue("nothing may be created when there is no stream", filesIn(platformDir).isEmpty())
    }

    @Test
    fun `a stream that fails mid copy fails and leaves no partial file`() = runBlocking {
        val uri = Uri.parse("content://atril.test.broken/Game.gba")
        shadowOf(context.contentResolver).registerInputStream(uri, FailingStream("ATRIL".toByteArray()))

        val result = importer.import(uri, platformDir, "Game.gba", overwrite = false, rootDir = root)

        assertEquals(RomImportResult.Failed("Couldn't copy the file"), result)
        assertFalse("the target must not exist", File(platformDir, "Game.gba").exists())
        assertTrue(
            "a failed import must not leave a .part behind: ${filesIn(platformDir)}",
            filesIn(platformDir).none { it.name.endsWith(".part") },
        )
        assertTrue("a failed import must not leave any file", filesIn(platformDir).isEmpty())
    }

    @Test
    fun `an existing file is not overwritten without confirmation`() = runBlocking {
        val existing = File(platformDir, "Game.gba").apply { writeText("OLD") }
        val uri = Uri.parse("content://atril.test.exists/Game.gba")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("NEW".toByteArray()))

        val result = importer.import(uri, platformDir, "Game.gba", overwrite = false, rootDir = root)

        assertEquals(RomImportResult.AlreadyExists(existing.absolutePath), result)
        assertEquals("OLD", existing.readText())
        assertTrue(filesIn(platformDir).none { it.name.endsWith(".part") })
    }

    @Test
    fun `an explicit overwrite replaces the existing file`() = runBlocking {
        val existing = File(platformDir, "Game.gba").apply { writeText("OLD") }
        val uri = Uri.parse("content://atril.test.replace/Game.gba")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("NEWROM".toByteArray()))

        val result = importer.import(uri, platformDir, "Game.gba", overwrite = true, rootDir = root)

        assertEquals(RomImportResult.Copied(existing.absolutePath), result)
        assertEquals("NEWROM", existing.readText())
        assertTrue(filesIn(platformDir).none { it.name.endsWith(".part") })
    }

    // ── Endurecimiento (SEC-002/SEC-003) ───────────────────────────────

    @Test
    fun `a partial file that is a symbolic link is refused and its target is untouched`() = runBlocking {
        val outside = tmp.newFolder("outside-part")
        val victim = File(outside, "victim.gba").apply { writeText("ORIGINAL") }
        val partLink = File(platformDir, "Game.gba.part")
        val created = runCatching { Files.createSymbolicLink(partLink.toPath(), victim.toPath()) }.isSuccess
        assumeTrue("the filesystem does not support symbolic links", created)
        val uri = Uri.parse("content://atril.test.partlink/Game.gba")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("NEW".toByteArray()))

        val result = importer.import(uri, platformDir, "Game.gba", overwrite = false, rootDir = root)

        assertEquals(RomImportResult.Failed("Couldn't copy the file"), result)
        assertEquals("ORIGINAL", victim.readText())
        assertFalse("the linked destination must not be created", File(platformDir, "Game.gba").exists())
    }

    @Test
    fun `a platform folder that is a symbolic link is refused`() = runBlocking {
        val realFolder = tmp.newFolder("real-platform")
        val link = File(root, "gbc")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), realFolder.toPath()) }.isSuccess
        assumeTrue("the filesystem does not support symbolic links", created)
        val uri = Uri.parse("content://atril.test.dirlink/Game.gbc")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("NEW".toByteArray()))

        val result = importer.import(uri, link, "Game.gbc", overwrite = false, rootDir = root)

        assertEquals(RomImportResult.Failed("Couldn't copy the file"), result)
        assertTrue("nothing may be written through the link", filesIn(realFolder).isEmpty())
    }

    @Test
    fun `a declared size over the import limit is refused before copying`() = runBlocking {
        Robolectric.buildContentProvider(HugeFileProvider::class.java).create("atril.test.huge")
        val uri = Uri.parse("content://atril.test.huge/Huge.gba")

        val result = importer.import(uri, platformDir, "Huge.gba", overwrite = false, rootDir = root)

        assertEquals(RomImportResult.Failed("That file is too large to import"), result)
        assertTrue(filesIn(platformDir).isEmpty())
    }

    private fun filesIn(dir: File): List<File> = dir.listFiles()?.toList().orEmpty()
}

/** Proveedor SAF que declara el documento pero no ofrece ningún stream. */
class NullStreamProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null
}

/** Stream que entrega unos bytes y después falla, como un proveedor que se cae. */
private class FailingStream(private val prefix: ByteArray) : InputStream() {
    private var index = 0

    override fun read(): Int {
        if (index < prefix.size) return prefix[index++].toInt() and 0xFF
        throw IOException("broken provider stream")
    }
}

/**
 * Proveedor que declara un documento de 17 GiB: por encima del tope de 16 GiB
 * de [RomFileImporter], sin necesidad de escribir esos bytes.
 */
class HugeFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf("_display_name", "_size")).apply {
        addRow(arrayOf<Any>("Huge.gba", 17L * 1024 * 1024 * 1024))
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null
}
