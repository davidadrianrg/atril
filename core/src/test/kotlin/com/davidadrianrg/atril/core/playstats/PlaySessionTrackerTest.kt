package com.davidadrianrg.atril.core.playstats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Semántica del cronómetro de partidas: umbral de 5 s, sesión feliz, cierre
 * sin partida (no-op), lanzamiento que sustituye a otro y desajuste de reloj.
 * Todo con instantes fijos: nunca se lee el reloj del sistema (AC-12).
 */
class PlaySessionTrackerTest {

    @Test
    fun `the minimum session is five seconds`() {
        assertEquals(5_000L, PlaySessionTracker.MIN_SESSION_MS)
    }

    @Test
    fun `a session of 4999 ms is discarded`() {
        val tracker = PlaySessionTracker()
        tracker.begin(7, 1_000)
        assertNull(tracker.end(5_999))
        assertFalse(tracker.isRunning())
    }

    @Test
    fun `a session of exactly 5000 ms is kept`() {
        val tracker = PlaySessionTracker()
        tracker.begin(7, 1_000)
        val session = tracker.end(6_000)
        assertNotNull(session)
        assertEquals(7, session!!.romId)
        assertEquals(1_000L, session.startedAt)
        assertEquals(6_000L, session.endedAt)
        assertEquals(5_000L, session.durationMs)
        assertFalse(tracker.isRunning())
    }

    @Test
    fun `begin and end return the completed session with its duration`() {
        val tracker = PlaySessionTracker()
        tracker.begin(42, 10_000)
        assertTrue(tracker.isRunning())
        val session = tracker.end(70_000)!!
        assertEquals(42, session.romId)
        assertEquals(10_000L, session.startedAt)
        assertEquals(70_000L, session.endedAt)
        assertEquals(60_000L, session.durationMs)
    }

    @Test
    fun `ending without a started session is a no-op`() {
        val tracker = PlaySessionTracker()
        assertNull(tracker.end(10_000))
        assertFalse(tracker.isRunning())
    }

    @Test
    fun `ending twice returns the session only once`() {
        val tracker = PlaySessionTracker()
        tracker.begin(3, 0)
        assertNotNull(tracker.end(6_000))
        assertNull(tracker.end(20_000))
    }

    @Test
    fun `a new launch while one is running drops the previous session`() {
        val tracker = PlaySessionTracker()
        tracker.begin(1, 0)
        tracker.begin(2, 1_000)
        val session = tracker.end(10_000)!!
        assertEquals(2, session.romId)
        assertEquals(1_000L, session.startedAt)
        assertEquals(9_000L, session.durationMs)
    }

    @Test
    fun `clock skew makes the session too short and it is discarded`() {
        val tracker = PlaySessionTracker()
        tracker.begin(5, 10_000)
        assertNull(tracker.end(9_000))
        assertFalse(tracker.isRunning())
    }

    @Test
    fun `the injected clock feeds the default arguments`() {
        var now = 100_000L
        val tracker = PlaySessionTracker { now }
        tracker.begin(3)
        now = 105_000L
        val session = tracker.end()!!
        assertEquals(100_000L, session.startedAt)
        assertEquals(105_000L, session.endedAt)
        assertEquals(5_000L, session.durationMs)
    }

    // ── B3: la sesión abierta sobrevive a la muerte del proceso ──────────

    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    private fun journal() = FilePlaySessionJournal(java.io.File(tmp.root, "play_session"))

    @Test
    fun `an open session is restored by a new tracker after the process dies`() {
        PlaySessionTracker(journal = journal()).begin(9, 1_000_000)
        // Proceso nuevo: otro tracker sobre el mismo fichero.
        val revived = PlaySessionTracker(journal = journal())
        assertTrue(revived.isRunning())
        val session = revived.end(1_600_000)!!
        assertEquals(9, session.romId)
        assertEquals(600_000L, session.durationMs)
    }

    @Test
    fun `ending clears the journal so the session is not counted twice`() {
        val first = PlaySessionTracker(journal = journal())
        first.begin(9, 0)
        assertNotNull(first.end(60_000))
        assertFalse(PlaySessionTracker(journal = journal()).isRunning())
    }

    @Test
    fun `a session never counts more than the maximum`() {
        val tracker = PlaySessionTracker()
        tracker.begin(4, 0)
        val threeDays = 3L * 24 * 60 * 60 * 1000
        val session = tracker.end(threeDays)!!
        assertEquals(PlaySessionTracker.MAX_SESSION_MS, session.durationMs)
    }

    @Test
    fun `an unreadable journal means no open session`() {
        java.io.File(tmp.root, "play_session").writeText("garbage")
        assertFalse(PlaySessionTracker(journal = journal()).isRunning())
    }
}
