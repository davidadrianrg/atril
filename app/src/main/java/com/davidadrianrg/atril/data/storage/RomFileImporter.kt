package com.davidadrianrg.atril.data.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.davidadrianrg.atril.core.i18n.tr
import java.io.File
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

/**
 * Copia un `content://` elegido con SAF a `<romsRoot>/<plataforma>/` con el
 * nombre saneado. Escribe siempre a un `.part` y renombra al final: un fallo
 * deja el directorio como estaba y nunca sobrescribe en silencio.
 */
class RomFileImporter(private val context: Context) {

    /**
     * Importa [uri] en [platformDir] conservando el nombre visible del
     * documento (o [preferredName] si el proveedor no lo expone).
     *
     * @param overwrite `true` solo tras confirmación explícita del usuario.
     * @return [RomImportResult]; nunca lanza.
     */
    suspend fun import(
        uri: Uri,
        platformDir: File,
        preferredName: String,
        overwrite: Boolean,
    ): RomImportResult = withContext(Dispatchers.IO) {
        val rawName = displayName(uri)?.takeIf { it.isNotBlank() } ?: preferredName
        val name = sanitizeFileName(rawName) ?: return@withContext RomImportResult.InvalidName

        if (!platformDir.isDirectory && !platformDir.mkdirs()) {
            return@withContext RomImportResult.Failed(tr("library.file_copy_failed"))
        }
        val target = File(platformDir, name)
        if (target.exists() && !overwrite) {
            return@withContext RomImportResult.AlreadyExists(target.absolutePath)
        }

        val part = File(platformDir, "$name.part")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext RomImportResult.Failed(tr("library.file_copy_failed"))
            input.use { source ->
                part.outputStream().use { output -> source.copyTo(output, DEFAULT_BUFFER) }
            }
            if (target.exists() && !target.delete()) {
                part.delete()
                return@withContext RomImportResult.Failed(tr("library.file_copy_failed"))
            }
            if (!part.renameTo(target)) {
                // Mismo directorio: renameTo debería funcionar, pero algunos
                // proveedores lo rechazan; la copia deja el resultado correcto.
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            RomImportResult.Copied(target.absolutePath)
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            Log.w(TAG, "Couldn't import picked file (${e.javaClass.simpleName})")
            RomImportResult.Failed(tr("library.file_copy_failed"))
        }
    }

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
