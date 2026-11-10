package com.davidadrianrg.atril.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tope duro de 8 segundos del pull de saves previo al lanzamiento.
 *
 * Se usa un cliente falso y tiempo virtual: ningún test espera de verdad,
 * ningún test toca la red, y el reloj del scheduler demuestra que el tope se
 * respeta al milisegundo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavePullTest {

    private class FakeClient(private val action: suspend (Int) -> Int) : SavePullClient {
        var calls = 0

        override suspend fun pullNewSaves(romId: Int): Int {
            calls++
            return action(romId)
        }
    }

    @Test
    fun `the default budget is eight seconds`() {
        assertEquals(8_000L, SavePull.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun `a fast client returns the number of pulled saves`() = runTest {
        val result = SavePull.beforeLaunch(FakeClient { 3 }, romId = 42)

        assertEquals(SavePullResult.Pulled(3), result)
    }

    @Test
    fun `zero new saves means nothing to pull`() = runTest {
        val result = SavePull.beforeLaunch(FakeClient { 0 }, romId = 42)

        assertEquals(SavePullResult.NothingToPull, result)
    }

    @Test
    fun `a hanging client times out at exactly eight seconds without throwing`() = runTest {
        val result = SavePull.beforeLaunch(
            FakeClient {
                delay(60_000)
                5
            },
            romId = 42,
        )

        assertEquals(SavePullResult.TimedOut, result)
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test
    fun `a timeout does not cancel the caller coroutine`() = runTest {
        val result = SavePull.beforeLaunch(
            FakeClient {
                delay(60_000)
                1
            },
            romId = 42,
        )

        assertEquals(SavePullResult.TimedOut, result)
        // El llamador sigue vivo: puede seguir trabajando tras el timeout.
        delay(1_000)
        assertEquals(9_000L, testScheduler.currentTime)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test
    fun `a client that finishes just under the budget still pulls`() = runTest {
        val result = SavePull.beforeLaunch(
            FakeClient {
                delay(7_999)
                2
            },
            romId = 42,
        )

        assertEquals(SavePullResult.Pulled(2), result)
        assertEquals(7_999L, testScheduler.currentTime)
    }

    @Test
    fun `a custom budget is honoured`() = runTest {
        val result = SavePull.beforeLaunch(
            FakeClient {
                delay(2_000)
                1
            },
            romId = 42,
            timeoutMs = 1_000L,
        )

        assertEquals(SavePullResult.TimedOut, result)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun `a throwing client becomes Failed and never throws`() = runTest {
        val result = SavePull.beforeLaunch(
            FakeClient { throw IOException("network down") },
            romId = 42,
        )

        assertEquals(SavePullResult.Failed("network down"), result)
    }

    @Test
    fun `a client cancellation while the caller is active becomes Failed`() = runTest {
        val result = SavePull.beforeLaunch(
            FakeClient { throw CancellationException("client gave up") },
            romId = 42,
        )

        assertEquals(SavePullResult.Failed("client gave up"), result)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test
    fun `null client and non RomM ids are skipped without suspending or calling the client`() = runTest {
        val client = FakeClient { 1 }

        assertEquals(SavePullResult.Skipped, SavePull.beforeLaunch(null, romId = 42))
        assertEquals(SavePullResult.Skipped, SavePull.beforeLaunch(client, romId = 0))
        assertEquals(SavePullResult.Skipped, SavePull.beforeLaunch(client, romId = -7))

        assertEquals(0, client.calls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `cancelling the caller still propagates cancellation`() = runTest {
        var propagated = false
        val job = launch {
            try {
                SavePull.beforeLaunch(
                    FakeClient {
                        delay(60_000)
                        1
                    },
                    romId = 42,
                )
            } catch (e: CancellationException) {
                propagated = true
                throw e
            }
        }

        advanceTimeBy(100)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(propagated)
    }
}
