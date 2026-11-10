package com.davidadrianrg.atril.launch

import com.davidadrianrg.atril.core.sync.SavePullClient
import com.davidadrianrg.atril.core.sync.SavePullResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Puerta de Android para "bajar saves antes de lanzar".
 *
 * El lanzamiento siempre debe continuar: sync apagado, servidor sin configurar,
 * id local o un pull que falla se resuelven sin lanzar y sin bloquear. El aviso
 * de "actualizando" solo aparece cuando de verdad se va a intentar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreLaunchSaveSyncTest {

    private fun fixedClient(count: Int): SavePullClient = object : SavePullClient {
        override suspend fun pullNewSaves(romId: Int): Int = count
    }

    @Test
    fun `disabled save sync skips without creating a client`() = runTest {
        var factoryCalls = 0
        var notified = false
        val sync = PreLaunchSaveSync(
            saveSyncEnabled = flowOf(false),
            clientFactory = {
                factoryCalls++
                fixedClient(1)
            },
        )

        val result = sync.run(romId = 42) { notified = true }

        assertEquals(SavePullResult.Skipped, result)
        assertEquals(0, factoryCalls)
        assertFalse(notified)
    }

    @Test
    fun `an unconfigured server skips without creating a client`() = runTest {
        var factoryCalls = 0
        var notified = false
        val sync = PreLaunchSaveSync(
            saveSyncEnabled = flowOf(true),
            clientFactory = {
                factoryCalls++
                fixedClient(1)
            },
            serverConfigured = { false },
        )

        val result = sync.run(romId = 42) { notified = true }

        assertEquals(SavePullResult.Skipped, result)
        assertEquals(0, factoryCalls)
        assertFalse(notified)
    }

    @Test
    fun `non RomM ids skip without notifying`() = runTest {
        var factoryCalls = 0
        var notified = false
        val sync = PreLaunchSaveSync(
            saveSyncEnabled = flowOf(true),
            clientFactory = {
                factoryCalls++
                fixedClient(1)
            },
        )

        assertEquals(SavePullResult.Skipped, sync.run(romId = 0) { notified = true })
        assertEquals(SavePullResult.Skipped, sync.run(romId = -3) { notified = true })

        assertEquals(0, factoryCalls)
        assertFalse(notified)
    }

    @Test
    fun `enabled sync notifies once before pulling and returns the result`() = runTest {
        val events = mutableListOf<String>()
        val sync = PreLaunchSaveSync(
            saveSyncEnabled = flowOf(true),
            clientFactory = {
                object : SavePullClient {
                    override suspend fun pullNewSaves(romId: Int): Int {
                        events += "pull:$romId"
                        return 2
                    }
                }
            },
        )

        val result = sync.run(romId = 42) { events += "updating" }

        assertEquals(SavePullResult.Pulled(2), result)
        assertEquals(listOf("updating", "pull:42"), events)
    }

    @Test
    fun `a failing client returns Failed and run does not throw`() = runTest {
        val sync = PreLaunchSaveSync(
            saveSyncEnabled = flowOf(true),
            clientFactory = {
                object : SavePullClient {
                    override suspend fun pullNewSaves(romId: Int): Int = throw IOException("offline")
                }
            },
        )

        val result = sync.run(romId = 42) {}

        assertEquals(SavePullResult.Failed("offline"), result)
    }

    @Test
    fun `a hanging pull is capped at eight seconds and still returns`() = runTest {
        val sync = PreLaunchSaveSync(
            saveSyncEnabled = flowOf(true),
            clientFactory = {
                object : SavePullClient {
                    override suspend fun pullNewSaves(romId: Int): Int {
                        delay(60_000)
                        return 1
                    }
                }
            },
        )

        val result = sync.run(romId = 42) {}

        assertEquals(SavePullResult.TimedOut, result)
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test
    fun `a failing settings read degrades to Skipped and the launch continues`() = runTest {
        var factoryCalls = 0
        var notified = false
        val sync = PreLaunchSaveSync(
            saveSyncEnabled = flow { throw IOException("datastore unavailable") },
            clientFactory = {
                factoryCalls++
                fixedClient(1)
            },
        )

        val result = sync.run(romId = 42) { notified = true }

        assertEquals(SavePullResult.Skipped, result)
        assertEquals(0, factoryCalls)
        assertFalse(notified)
    }

    @Test
    fun `a failing server check degrades to Skipped and the launch continues`() = runTest {
        var factoryCalls = 0
        var notified = false
        val sync = PreLaunchSaveSync(
            saveSyncEnabled = flowOf(true),
            clientFactory = {
                factoryCalls++
                fixedClient(1)
            },
            serverConfigured = { throw IllegalStateException("settings read failed") },
        )

        val result = sync.run(romId = 42) { notified = true }

        assertEquals(SavePullResult.Skipped, result)
        assertEquals(0, factoryCalls)
        assertFalse(notified)
    }

    @Test
    fun `settings cancellation is not swallowed`() = runTest {
        val sync = PreLaunchSaveSync(
            saveSyncEnabled = flow { throw CancellationException("caller cancelled") },
            clientFactory = { fixedClient(1) },
        )

        val thrown = runCatching { sync.run(romId = 42) {} }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }
}
