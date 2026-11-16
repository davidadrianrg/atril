package com.davidadrianrg.atril.download

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.davidadrianrg.atril.domain.model.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1: «Reintentar» tiene que reconstruir la descarga completa. Una vez
 * terminado el trabajo `WorkInfo.progress` está vacío, así que la identidad
 * tiene que viajar en los datos de salida del worker.
 */
class DownloadIdentityTest {

    private val spec = DownloadRequestSpec(
        romId = 42,
        romName = "Metroid",
        fileName = "Metroid.gba",
        platformId = 7,
        platformSlug = "gba",
        fileHash = "d41d8cd98f00b204e9800998ecf8427e",
        serverUrl = "http://romm.local",
        romsRootPath = "/sdcard/ROMs",
    )

    @Test
    fun `failed work keeps the whole identity for retry`() {
        // Lo que el worker deja en la salida al fallar.
        val output = workDataOf(
            *DownloadWorker.identityOf(spec.toData()),
            DownloadWorker.KEY_ERROR_MESSAGE to "boom",
        )
        val task = DownloadManager.taskFrom(
            progress = Data.EMPTY,
            output = output,
            tags = setOf("romm_download", "rom_42"),
            workId = "w1",
            state = WorkInfo.State.FAILED,
        )
        assertEquals(42, task.romId)
        assertEquals("Metroid", task.romName)
        assertEquals("Metroid.gba", task.fileName)
        assertEquals("gba", task.platformSlug)
        assertEquals(7, task.platformId)
        assertEquals(spec.fileHash, task.fileHash)
        assertEquals("/sdcard/ROMs", task.romsRootPath)
        assertTrue(task.isFailed)
        assertEquals("boom", task.errorMessage)
    }

    @Test
    fun `retry request carries hash and platform id`() {
        val task = DownloadTask(
            romId = 42, romName = "Metroid", fileName = "Metroid.gba", platformSlug = "gba",
            romsRootPath = "/sdcard/ROMs", platformId = 7, fileHash = spec.fileHash, workId = "w1",
        )
        val data = DownloadRequestSpec.of(task).toData()
        assertEquals(42, data.getInt(DownloadWorker.KEY_ROM_ID, -1))
        assertEquals(7, data.getInt(DownloadWorker.KEY_PLATFORM_ID, -1))
        assertEquals(spec.fileHash, data.getString(DownloadWorker.KEY_FILE_HASH))
        assertEquals("/sdcard/ROMs", data.getString(DownloadWorker.KEY_ROMS_ROOT_PATH))
        // El reintento usa la URL de los ajustes actuales, no una copia antigua.
        assertNull(data.getString(DownloadWorker.KEY_SERVER_URL))
    }

    @Test
    fun `failure output of older workers falls back to the rom tag`() {
        val legacyOutput = workDataOf(
            DownloadWorker.KEY_ROM_NAME to "Metroid",
            DownloadWorker.KEY_FILE_NAME to "Metroid.gba",
            DownloadWorker.KEY_PLATFORM_SLUG to "gba",
        )
        val task = DownloadManager.taskFrom(
            progress = Data.EMPTY,
            output = legacyOutput,
            tags = setOf("romm_download", "rom_42"),
            workId = "w1",
            state = WorkInfo.State.FAILED,
        )
        assertEquals(42, task.romId)
        assertEquals(0, task.platformId)
        assertNull(task.fileHash)
    }

    @Test
    fun `running work reads its identity from progress`() {
        val progress = workDataOf(
            *DownloadWorker.identityOf(spec.toData()),
            DownloadWorker.KEY_PROGRESS to 30,
            DownloadWorker.KEY_DOWNLOADED_BYTES to 300L,
            DownloadWorker.KEY_TOTAL_BYTES to 1000L,
        )
        val task = DownloadManager.taskFrom(
            progress = progress,
            output = Data.EMPTY,
            tags = setOf("romm_download", "rom_42"),
            workId = "w1",
            state = WorkInfo.State.RUNNING,
        )
        assertEquals(42, task.romId)
        assertEquals(7, task.platformId)
        assertEquals(30, task.progress)
        assertTrue(task.isRunning)
    }

    @Test
    fun `identity omits the optional hash when the request had none`() {
        val keys = DownloadWorker.identityOf(spec.copy(fileHash = null).toData()).map { it.first }
        assertTrue(DownloadWorker.KEY_FILE_HASH !in keys)
        assertTrue(DownloadWorker.KEY_PLATFORM_ID in keys)
    }
}
