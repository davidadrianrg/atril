package com.davidadrianrg.atril.data.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.davidadrianrg.atril.core.i18n.tr
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resultado de importar un fichero elegido con el selector de documentos. */
sealed interface RomImportResult {
    /** Copiado (o ya accesible) en [path]. */
    data class Copied(val path: String) : RomImportResult

    /** El destino ya existe y no se pidió sobrescribirlo. */
    data class AlreadyExists(val intendedPath: String) : RomImportResult

    /** El nombre no es utilizable (vacío, separadores, `..`). */
    data object InvalidName : RomImportResult

    /** Fallo de lectura/escritura; [reason] es un mensaje para el usuario. */
    data class Failed(val reason: String) : RomImportResult
}

/** El documento supera el tope de tamaño aceptado. */
private class ImportTooLargeException : Exception()

/**
 * Copia un `content://` elegido con SAF a `<romsRoot>/<plataforma>/` con el
 * nombre saneado. Escribe siempre a un `.part` y renombra al final: un fallo
 * deja el directorio como estaba y nunca sobrescribe en silencio.
 *
 * Endurecimiento (SEC-002/SEC-003): rechaza carpetas y `.part` que sean
 * symlinks, verifica que el destino queda bajo la raíz configurada, escribe
 * con `CREATE_NEW` (nunca sigue un enlace preexistente), limita el tamaño y
 * comprueba el espacio libre antes de copiar.
 */
class RomFileImporter(private val context: Context) {

    /**
     * Importa [uri] en [platformDir] conservando el nombre visible del
     * documento (o [preferredName] si el proveedor no lo expone).
     *
     * @param overwrite `true` solo tras confirmación explícita del usuario.
     * @param rootDir raíz de ROMs configurada; si se indica, [platformDir]
     *   debe resolverse dentro de ella (contención canónica).
     * @return [RomImportResult]; nunca lanza.
     */
    suspend fun import(
        uri: Uri,
        platformDir: File,
        preferredName: String,
        overwrite: Boolean,
        rootDir: File? = null,
    ): RomImportResult = withContext(Dispatchers.IO) {
        val rawName = displayName(uri)?.takeIf { it.isNotBlank() } ?: preferredName
        val name = sanitizeFileName(rawName) ?: return@withContext RomImportResult.InvalidName

        if (Files.isSymbolicLink(platformDir.toPath())) {
            return@withContext RomImportResult.Failed(tr("library.file_copy_failed"))
        }
        if (!isUnderRoot(platformDir, rootDir)) {
            return@withContext RomImportResult.Failed(tr("library.file_copy_failed"))
        }
        if (!platformDir.isDirectory && !platformDir.mkdirs()) {
            return@withContext RomImportResult.Failed(tr("library.file_copy_failed"))
        }
        // La carpeta pudo aparecer como symlink entre la comprobación y mkdirs.
        if (Files.isSymbolicLink(platformDir.toPath()) || !isUnderRoot(platformDir, rootDir)) {
            return@withContext RomImportResult.Failed(tr("library.file_copy_failed"))
        }

        val target = File(platformDir, name)
        if (target.exists() && !overwrite) {
            return@withContext RomImportResult.AlreadyExists(target.absolutePath)
        }

        val part = File(platformDir, "$name.part")
        if (Files.isSymbolicLink(part.toPath())) {
            return@withContext RomImportResult.Failed(tr("library.file_copy_failed"))
        }
        val declaredSize = declaredSize(uri)
        if (declaredSize != null && declaredSize > MAX_IMPORT_BYTES) {
            return@withContext RomImportResult.Failed(tr("library.file_copy_too_large"))
        }
        if (declaredSize != null && declaredSize > 0 && !hasFreeSpace(platformDir, declaredSize)) {
            return@withContext RomImportResult.Failed(tr("library.file_copy_no_space"))
        }

        try {
            // Un `.part` huérfano de una ejecución anterior se descarta; si es
            // un symlink ya se rechazó arriba y nunca se sigue al escribir.
            if (part.exists() && !part.delete()) {
                return@withContext RomImportResult.Failed(tr("library.file_copy_failed"))
            }
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext RomImportResult.Failed(tr("library.file_copy_failed"))
            input.use { source ->
                // CREATE_NEW falla si ya existe (incluido un symlink), así que
                // nunca se sigue ni se trunca un `.part` ajeno.
                Files.newOutputStream(
                    part.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { output -> copyBounded(source, output) }
            }
            // Revalidar después de escribir: el `.part` no puede ser un enlace
            // ni haber quedado fuera de la carpeta de la plataforma.
            if (Files.isSymbolicLink(part.toPath()) || !isDirectChildOf(part, platformDir)) {
                part.delete()
                return@withContext RomImportResult.Failed(tr("library.file_copy_failed"))
            }
            if (target.exists() && !overwrite) {
                part.delete()
                return@withContext RomImportResult.AlreadyExists(target.absolutePath)
            }
            if (target.exists() && !target.delete()) {
                part.delete()
                return@withContext RomImportResult.Failed(tr("library.file_copy_failed"))
            }
            moveAtomically(part, target)
            RomImportResult.Copied(target.absolutePath)
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: ImportTooLargeException) {
            part.delete()
            Log.w(TAG, "Picked file is too large (${e.javaClass.simpleName})")
            RomImportResult.Failed(tr("library.file_copy_too_large"))
        } catch (e: Exception) {
            part.delete()
            Log.w(TAG, "Couldn't import picked file (${e.javaClass.simpleName})")
            RomImportResult.Failed(tr("library.file_copy_failed"))
        }
    }

    /** Copia con tope duro de bytes, abortando si el proveedor miente. */
    private fun copyBounded(source: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER)
        var downloaded = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            downloaded += read
            if (downloaded > MAX_IMPORT_BYTES) throw ImportTooLargeException()
            output.write(buffer, 0, read)
        }
    }

    /** `true` si [dir] (canónica) es [rootDir] o cuelga de ella. */
    private fun isUnderRoot(dir: File, rootDir: File?): Boolean {
        if (rootDir == null) return true
        val root = runCatching { rootDir.canonicalFile }.getOrNull() ?: return false
        val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        return canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
    }

    /** `true` si [file] (canónico) es hijo directo de [dir] (canónico). */
    private fun isDirectChildOf(file: File, dir: File): Boolean {
        val canonicalDir = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return canonicalFile.parentFile?.path == canonicalDir.path
    }

    /** Comprueba que hay espacio para el tamaño declarado (con margen). */
    private fun hasFreeSpace(dir: File, size: Long): Boolean {
        val usable = runCatching { dir.usableSpace }.getOrDefault(0L)
        // Algunos sistemas de ficheros devuelven 0 sin información: no se
        // bloquea la importación por un dato desconocido.
        if (usable <= 0L) return true
        return usable > size + MIN_FREE_MARGIN
    }

    /** Renombra el `.part`; usa `ATOMIC_MOVE` cuando el sistema lo permite. */
    private fun moveAtomically(part: File, target: File) {
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath())
        }
    }

    private fun declaredSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()

    private companion object {
        const val TAG = "RomFileImporter"
        const val DEFAULT_BUFFER = 64 * 1024

        /** Tope de tamaño aceptado para una ROM importada (16 GiB). */
        const val MAX_IMPORT_BYTES = 16L * 1024 * 1024 * 1024

        /** Margen de espacio libre que se deja libre tras la copia. */
        const val MIN_FREE_MARGIN = 64L * 1024 * 1024
    }
}

/**
 * Sanea el nombre visible de un documento para usarlo como nombre de fichero.
 *
 * Quita caracteres de control y rechaza vacío, `.`, `..`, separadores de ruta
 * y NUL. Devuelve `null` cuando no hay un nombre seguro que usar.
 */
internal fun sanitizeFileName(raw: String): String? {
    val cleaned = raw.trim().filterNot { it.isISOControl() }
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
    return cleaned.takeIf { name -> name.none { it == '/' || it == '\\' || it == '\u0000' } }
}
