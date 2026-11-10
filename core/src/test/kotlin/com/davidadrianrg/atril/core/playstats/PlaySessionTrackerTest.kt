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
}
