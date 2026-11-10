package com.davidadrianrg.atril.core.sync

import com.davidadrianrg.atril.core.remote.RomMApiService
import com.davidadrianrg.atril.core.sync.platform.LocalSave
import com.davidadrianrg.atril.core.sync.platform.SaveHandler
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

/**
 * Subida/descarga de un save, compartida por Android y escritorio.
 *
 * - Reintenta fallos transitorios con backoff ([SyncRetry]).
 * - La descarga se verifica contra el MD5 del servidor ANTES de tocar el save
 *   local: un fichero truncado o corrupto no sobrescribe nada.
 * - El temporal se borra siempre, también si falla.
 */
object SyncTransfers {

    /** Descarga corrupta/truncada: se trata como transitoria y se reintenta. */
    class IntegrityException(message: String) : IOException(message)

    suspend fun upload(
        api: RomMApiService,
        save: LocalSave,
        romId: Int,
        deviceId: String,
        handler: SaveHandler,
        attempts: Int = SyncRetry.DEFAULT_ATTEMPTS,
        baseDelayMs: Long = SyncRetry.DEFAULT_BASE_DELAY_MS,
        log: (String) -> Unit = {},
    ): Boolean = try {
        SyncRetry.withRetry(attempts, baseDelayMs, onRetry = { n, e ->
            log("upload retry $n for ${LogSafe.fileName(save.fileName)}: ${LogSafe.exception(e)}")
        }) {
            val fileToUpload = handler.prepareForUpload(save)
            val requestFile = fileToUpload.asRequestBody("application/octet-stream".toMediaType())
            val filePart = MultipartBody.Part.createFormData("saveFile", save.fileName, requestFile)
            api.uploadSave(
                file = filePart,
                romId = romId,
                deviceId = deviceId,
                slot = SyncSlots.DEFAULT,
                overwrite = true,
            )
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("upload failed for ${LogSafe.fileName(save.fileName)}: ${LogSafe.exception(e)}")
        false
    }

    suspend fun download(
        api: RomMApiService,
        saveId: Int,
        deviceId: String,
        romId: Int,
        romFileName: String,
        platformSlug: String,
        fileName: String,
        savesBasePath: String,
        handler: SaveHandler,
        cacheDir: File,
        expectedHash: String?,
        attempts: Int = SyncRetry.DEFAULT_ATTEMPTS,
        baseDelayMs: Long = SyncRetry.DEFAULT_BASE_DELAY_MS,
        log: (String) -> Unit = {},
    ): Boolean {
        // El nombre lo elige el servidor: se valida ANTES de descargar y de
        // construir cualquier ruta. Un nombre hostil (ruta absoluta, `..`,
        // separadores o NUL) se salta sin escribirlo ni registrarlo tal cual.
        val safeFileName = SaveNames.stripVersionTag(fileName)
        if (!SaveNames.isSafeSaveFileName(safeFileName)) {
            log("download skipped for rom $romId: unsafe server file name")
            return false
        }
        val tempFile = File(cacheDir, "sync_dl_${romId}_${safeFileName.replace(Regex("[^A-Za-z0-9._ -]"), "_")}")
        return try {
            SyncRetry.withRetry(attempts, baseDelayMs, onRetry = { n, e ->
                log("download retry $n for ${LogSafe.fileName(safeFileName)}: ${LogSafe.exception(e)}")
            }) {
                api.downloadSave(saveId, deviceId).use { body ->
                    tempFile.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                }
                when (val v = SyncIntegrity.verify(tempFile, expectedHash)) {
                    is SyncIntegrity.Verdict.Mismatch ->
                        throw IntegrityException("md5 mismatch: expected ${v.expected} got ${v.actual}")
                    else -> Unit
                }
            }
            handler.extractDownload(
                tempFile = tempFile,
                romFileName = romFileName,
                platformSlug = platformSlug,
                savesBasePath = savesBasePath,
                targetFileName = safeFileName,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("download failed for ${LogSafe.fileName(safeFileName)} (rom $romId): ${LogSafe.exception(e)}")
            false
        } finally {
            tempFile.delete()
        }
    }
}
