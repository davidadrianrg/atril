package com.davidadrianrg.atril.data.emulators

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorInstallFlowTest {

    private fun job(id: String, state: EmulatorJobState) = EmulatorJob(id, id, false, state)

    @Test
    fun `opens each ready apk only once, in queue order`() {
        val jobs = listOf(
            job("a", EmulatorJobState.DOWNLOADING),
            job("b", EmulatorJobState.READY),
            job("c", EmulatorJobState.READY),
        )
        assertEquals("b", EmulatorInstallFlow.nextAutoInstall(jobs, emptySet())?.entryId)
        assertEquals("c", EmulatorInstallFlow.nextAutoInstall(jobs, setOf("b"))?.entryId)
        assertNull(EmulatorInstallFlow.nextAutoInstall(jobs, setOf("b", "c")))
    }

    @Test
    fun `install finished when the installed version changes or catches up`() {
        assertTrue(EmulatorInstallFlow.installFinished(before = null, after = "1.2", downloadedVersion = "1.2"))
        assertTrue(EmulatorInstallFlow.installFinished(before = "1.0", after = "1.2", downloadedVersion = "v1.2"))
        // Reinstalar la misma versión.
        assertTrue(EmulatorInstallFlow.installFinished(before = "1.2", after = "1.2", downloadedVersion = "1.2"))
    }

    @Test
    fun `cancelled installer keeps the job`() {
        assertFalse(EmulatorInstallFlow.installFinished(before = null, after = null, downloadedVersion = "1.2"))
        assertFalse(EmulatorInstallFlow.installFinished(before = "1.0", after = "1.0", downloadedVersion = "1.2"))
    }
}
