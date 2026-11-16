package com.davidadrianrg.atril.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.davidadrianrg.atril.data.local.SettingsDataStore
import com.davidadrianrg.atril.domain.model.DownloadTask
import com.davidadrianrg.atril.domain.model.Rom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Manages the WorkManager download queue.
 *
 * Enqueues ROM downloads, observes their progress, and supports cancellation.
 *
 * **Concurrency limit**: the user-configured `maxConcurrentDownloads` setting
 * (1-5) is enforced inside [DownloadWorker] via a process-wide Semaphore kept
 * in its companion object. This file simply enqueues OneTimeWorkRequests —
 * WorkManager will run them in parallel, but only `maxConcurrentDownloads`
 * workers will actually transfer bytes at any given time. See
 * [DownloadWorker.syncConcurrencyLimit] for the implementation.
 *
 * Encolar nunca bloquea: «Descargar faltantes» con cientos de juegos hacía un
 * `runBlocking` sobre DataStore por ROM en el hilo principal (riesgo de ANR).
 * Ahora quien ya tiene los ajustes pasa `wifiOnly` y el resto usa las
 * variantes `suspend`, que leen el ajuste una sola vez.
 */
class DownloadManager(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    /** Ajuste «solo Wi-Fi» leído sin bloquear. */
    private suspend fun wifiOnly(): Boolean = runCatching {
        SettingsDataStore.get(context).settings.first().wifiOnlyDownloads
    }.getOrDefault(false)

    /** Encola un ROM con el ajuste de red ya conocido. No bloquea. */
    fun enqueueDownload(
        rom: Rom,
        serverUrl: String,
        romsRootPath: String? = null,
        wifiOnly: Boolean,
    ): String {
        val request = buildRequest(
            DownloadRequestSpec.of(rom, serverUrl, romsRootPath),
            wifiOnly,
        )
        // UniqueWork + KEEP: encolar dos veces el mismo ROM (doble tap,
        // "Descargar faltantes" repetido, re-entrar en la pantalla) nunca
        // duplica la descarga — si ya hay trabajo vivo para ese ROM se ignora.
        // Antes cada tap creaba un worker nuevo: dos workers escribiendo el
        // mismo fichero se pisaban el parcial mutuamente y la cola parecía
        // "reiniciarse todas las descargas".
        workManager.enqueueUniqueWork(workTagForRom(rom.id), ExistingWorkPolicy.KEEP, request)
        return request.id.toString()
    }

    /** Encola varios ROMs leyendo «solo Wi-Fi» una única vez. */
    suspend fun enqueueDownloads(roms: List<Rom>, serverUrl: String, romsRootPath: String? = null) {
        val wifiOnly = wifiOnly()
        roms.forEach { enqueueDownload(it, serverUrl, romsRootPath, wifiOnly) }
    }

    /**
     * Observes all active and completed downloads as a Flow of DownloadTask list.
     * Uses WorkManager 2.9+ native Flow API.
     */
    fun observeDownloads(): Flow<List<DownloadTask>> {
        return workManager.getWorkInfosByTagFlow(TAG_DOWNLOAD).map { workInfos ->
            workInfos
                .filter { it.state != WorkInfo.State.CANCELLED }
                .map { it.toDownloadTask() }
                .sortedByDescending { it.isRunning }
        }
    }

    /** Cancelación explícita del usuario: el worker borra el fichero parcial. */
    fun cancelDownload(romId: Int) {
        DownloadCancelRegistry.markCancelled(romId)
        workManager.cancelAllWorkByTag(workTagForRom(romId))
    }

    /** Cancelación explícita de toda la cola: los workers borran sus parciales. */
    fun cancelAllDownloads() {
        DownloadCancelRegistry.markAllCancelled()
        workManager.cancelAllWorkByTag(TAG_DOWNLOAD)
    }

    /**
     * Prunes completed (SUCCEEDED, FAILED) work from the WorkManager queue.
     * After calling this, observeDownloads() will no longer emit those items.
     */
    fun pruneCompletedWork() {
        workManager.pruneWork()
    }

    /**
     * Re-encola una descarga fallida con su identidad completa: id de
     * plataforma (para la fila de Room) y hash (para verificar el reintento),
     * además del destino original para reanudar el `.part` donde quedó.
     *
     * @return id del nuevo trabajo, o null si la tarea no identifica un ROM.
     */
    suspend fun retryDownload(task: DownloadTask): String? {
        if (task.romId < 0) return null
        // No es una cancelación del usuario: se conserva el parcial para reanudar.
        workManager.cancelAllWorkByTag(workTagForRom(task.romId))
        val request = buildRequest(DownloadRequestSpec.of(task), wifiOnly())
        // REPLACE: sustituye cualquier resto del work anterior de ese ROM.
        workManager.enqueueUniqueWork(workTagForRom(task.romId), ExistingWorkPolicy.REPLACE, request)
        return request.id.toString()
    }

    private fun workTagForRom(romId: Int) = "$ROM_TAG_PREFIX$romId"

    private fun buildRequest(spec: DownloadRequestSpec, wifiOnly: Boolean): OneTimeWorkRequest {
        // "Solo WiFi": UNMETERED evita datos móviles; saves siempre CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        return OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(spec.toData())
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_DOWNLOAD)
            .addTag(workTagForRom(spec.romId))
            .build()
    }

    /** Converts a WorkInfo to a DownloadTask. */
    private fun WorkInfo.toDownloadTask(): DownloadTask = taskFrom(
        progress = progress,
        output = outputData,
        tags = tags,
        workId = id.toString(),
        state = state,
    )

    companion object {
        private const val TAG_DOWNLOAD = "romm_download"
        internal const val ROM_TAG_PREFIX = "rom_"

        /**
         * Construye la tarea de la UI. Mientras corre, los datos vienen en el
         * progreso; al terminar el progreso queda vacío y mandan los datos de
         * salida (el worker incluye en ellos la identidad completa). Como
         * último recurso el id sale de la etiqueta `rom_<id>`.
         */
        internal fun taskFrom(
            progress: Data,
            output: Data,
            tags: Set<String>,
            workId: String,
            state: WorkInfo.State,
        ): DownloadTask {
            fun string(key: String): String? = progress.getString(key) ?: output.getString(key)
            fun int(key: String): Int? = when {
                progress.keyValueMap.containsKey(key) -> progress.getInt(key, 0)
                output.keyValueMap.containsKey(key) -> output.getInt(key, 0)
                else -> null
            }
            val romId = int(DownloadWorker.KEY_ROM_ID)?.takeIf { it >= 0 }
                ?: tags.firstNotNullOfOrNull { tag ->
                    tag.takeIf { it.startsWith(ROM_TAG_PREFIX) }?.removePrefix(ROM_TAG_PREFIX)?.toIntOrNull()
                }
                ?: -1
            return DownloadTask(
                romId = romId,
                romName = string(DownloadWorker.KEY_ROM_NAME)
                    ?: com.davidadrianrg.atril.core.i18n.tr("android.downloads.unknown_game"),
                fileName = string(DownloadWorker.KEY_FILE_NAME) ?: "",
                platformSlug = string(DownloadWorker.KEY_PLATFORM_SLUG) ?: "",
                romsRootPath = string(DownloadWorker.KEY_ROMS_ROOT_PATH),
                platformId = int(DownloadWorker.KEY_PLATFORM_ID) ?: 0,
                fileHash = string(DownloadWorker.KEY_FILE_HASH),
                workId = workId,
                progress = progress.getInt(DownloadWorker.KEY_PROGRESS, 0),
                isIndeterminate = progress.getBoolean(DownloadWorker.KEY_INDETERMINATE, false),
                progressText = progress.getString(DownloadWorker.KEY_PROGRESS_TEXT),
                isRunning = state == WorkInfo.State.RUNNING,
                isCompleted = state == WorkInfo.State.SUCCEEDED,
                isFailed = state == WorkInfo.State.FAILED,
                errorMessage = output.getString(DownloadWorker.KEY_ERROR_MESSAGE),
                downloadedBytes = progress.getLong(DownloadWorker.KEY_DOWNLOADED_BYTES, 0L),
                totalBytes = progress.getLong(DownloadWorker.KEY_TOTAL_BYTES, 0L),
                speedBps = progress.getLong(DownloadWorker.KEY_SPEED_BPS, 0L),
            )
        }
    }
}

/**
 * Datos de entrada de un [DownloadWorker]. Un único sitio construye el `Data`
 * tanto para la primera descarga como para el reintento, así ninguno de los
 * dos olvida el hash o el id de plataforma.
 */
internal data class DownloadRequestSpec(
    val romId: Int,
    val romName: String,
    val fileName: String,
    val platformId: Int,
    val platformSlug: String,
    val fileHash: String?,
    val serverUrl: String?,
    val romsRootPath: String?,
) {
    fun toData(): Data = Data.Builder()
        .putInt(DownloadWorker.KEY_ROM_ID, romId)
        .putString(DownloadWorker.KEY_ROM_NAME, romName)
        .putString(DownloadWorker.KEY_FILE_NAME, fileName)
        .putInt(DownloadWorker.KEY_PLATFORM_ID, platformId)
        .putString(DownloadWorker.KEY_PLATFORM_SLUG, platformSlug)
        .putString(DownloadWorker.KEY_SERVER_URL, serverUrl)
        .putString(DownloadWorker.KEY_FILE_HASH, fileHash)
        .putString(DownloadWorker.KEY_ROMS_ROOT_PATH, romsRootPath)
        .build()

    companion object {
        fun of(rom: Rom, serverUrl: String, romsRootPath: String?) = DownloadRequestSpec(
            romId = rom.id,
            romName = rom.name,
            fileName = rom.fileName,
            platformId = rom.platformId,
            platformSlug = rom.platformSlug,
            fileHash = rom.files.firstOrNull()?.hash,
            serverUrl = serverUrl,
            romsRootPath = romsRootPath,
        )

        /** Reintento: el worker lee la URL del servidor de los ajustes actuales. */
        fun of(task: DownloadTask) = DownloadRequestSpec(
            romId = task.romId,
            romName = task.romName,
            fileName = task.fileName,
            platformId = task.platformId,
            platformSlug = task.platformSlug,
            fileHash = task.fileHash,
            serverUrl = null,
            romsRootPath = task.romsRootPath,
        )
    }
}
