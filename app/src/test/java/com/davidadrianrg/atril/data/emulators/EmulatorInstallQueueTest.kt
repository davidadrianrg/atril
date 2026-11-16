package com.davidadrianrg.atril.data.emulators

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EmulatorInstallQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val file get() = File(tmp.root, "queue.json")
    private fun queue() = EmulatorInstallQueue(file)

    @Test
    fun `enqueue keeps arrival order and ignores what is already pending`() {
        val q = queue()
        assertEquals(listOf("a", "b"), q.enqueue(listOf("a" to "A", "b" to "B"), dualScreen = false))
        assertEquals(emptyList<String>(), q.enqueue(listOf("a" to "A"), dualScreen = false))

        assertEquals("a", q.nextQueued()?.entryId)
        q.markDownloading("a")
        assertEquals("b", q.nextQueued()?.entryId)
    }

    @Test
    fun `a failed job can be queued again`() {
        val q = queue()
        q.enqueue(listOf("a" to "A"), dualScreen = true)
        q.markFailed("a", "HTTP 500")

        assertEquals(listOf("a"), q.enqueue(listOf("a" to "A"), dualScreen = false))
        val job = q.get("a")!!
        assertEquals(EmulatorJobState.QUEUED, job.state)
        assertNull(job.error)
        assertFalse(job.dualScreen)
    }

    @Test
    fun `state survives a new instance but progress does not`() {
        val apk = tmp.newFile("emu.apk")
        queue().apply {
            enqueue(listOf("a" to "A", "b" to "B", "c" to "C"), dualScreen = false)
            markDownloading("a")
            updateProgress("a", 0.5f)
            markReady("b", "1.2", apk.path)
            markFailed("c", "boom")
        }

        val reloaded = queue()
        assertEquals(EmulatorJobState.DOWNLOADING, reloaded.get("a")?.state)
        assertEquals(0f, reloaded.get("a")?.progress)
        assertEquals("1.2", reloaded.get("b")?.version)
        assertEquals(apk.path, reloaded.get("b")?.apkPath)
        assertEquals("boom", reloaded.get("c")?.error)
    }

    @Test
    fun `an interrupted download goes back to the queue`() {
        queue().apply {
            enqueue(listOf("a" to "A"), dualScreen = false)
            markDownloading("a")
        }

        val q = queue()
        q.recoverInterrupted()

        assertEquals(EmulatorJobState.QUEUED, q.get("a")?.state)
        assertEquals("a", q.nextQueued()?.entryId)
    }

    @Test
    fun `removing a ready job deletes its apk and markReady after removal reports it`() {
        val apk = tmp.newFile("emu.apk")
        val q = queue()
        q.enqueue(listOf("a" to "A", "b" to "B"), dualScreen = false)
        q.markReady("a", "1.0", apk.path)

        assertEquals("a", q.remove("a")?.entryId)
        assertFalse(apk.exists())
        q.remove("b")
        assertFalse(q.markReady("b", "1.0", "/nope"))
        assertTrue(q.jobs.value.isEmpty())
    }

    @Test
    fun `rate limit fails every pending job but keeps ready ones`() {
        val q = queue()
        q.enqueue(listOf("a" to "A", "b" to "B", "c" to "C"), dualScreen = false)
        q.markReady("a", "1.0", "/x.apk")
        q.failAllQueued("limit")

        assertEquals(EmulatorJobState.READY, q.get("a")?.state)
        assertEquals(EmulatorJobState.FAILED, q.get("b")?.state)
        assertEquals("limit", q.get("c")?.error)
        assertFalse(q.hasQueued())
    }

    @Test
    fun `requeue forgets the lost apk`() {
        val q = queue()
        q.enqueue(listOf("a" to "A"), dualScreen = false)
        q.markReady("a", "1.0", "/gone.apk")
        q.requeue("a")

        val job = q.get("a")!!
        assertEquals(EmulatorJobState.QUEUED, job.state)
        assertNull(job.apkPath)
    }

    @Test
    fun `a corrupt file starts an empty queue`() {
        file.writeText("{not a list")
        assertTrue(queue().jobs.value.isEmpty())
    }
}
