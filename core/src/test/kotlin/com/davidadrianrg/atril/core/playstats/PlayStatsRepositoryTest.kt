package com.davidadrianrg.atril.core.playstats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fachada observable: persistencia al cerrar una partida, descarte con aviso de
 * las sesiones cortas, favorito escrito en todo el grupo y agregación/orden de
 * «Seguir jugando». El almacén es un falso en memoria y el reloj está inyectado:
 * ninguna prueba toca disco ni el reloj real (AC-11/AC-12).
 */
class PlayStatsRepositoryTest {

    /** Almacén en memoria: sustituye la persistencia sin tocar el sistema de ficheros. */
    private class FakePlayStatsStore(initial: List<PlayStats> = emptyList()) : PlayStatsStore {
        private val records = LinkedHashMap<Int, PlayStats>()
        val recorded = mutableListOf<Triple<Int, Long, Long>>()
        val favoriteWrites = mutableListOf<Pair<Int, Boolean>>()

        init {
            initial.forEach { records[it.romId] = it }
        }

        override fun all(): List<PlayStats> = records.values.toList()

        override fun get(romId: Int): PlayStats? = records[romId]

        override fun setFavorite(romId: Int, favorite: Boolean): PlayStats {
            favoriteWrites += romId to favorite
            val updated = (records[romId] ?: PlayStats(romId)).copy(favorite = favorite)
            records[romId] = updated
            return updated
        }

        override fun recordSession(romId: Int, endedAt: Long, durationMs: Long): PlayStats {
            require(durationMs >= 0L) { "durationMs must not be negative" }
            recorded += Triple(romId, endedAt, durationMs)
            val previous = records[romId] ?: PlayStats(romId)
            val updated = previous.copy(
                lastPlayedAt = maxOf(previous.lastPlayedAt ?: 0L, endedAt),
                totalPlayedMs = previous.totalPlayedMs + durationMs,
            )
            records[romId] = updated
            return updated
        }
    }

    private fun repository(
        store: PlayStatsStore,
        tracker: PlaySessionTracker = PlaySessionTracker { 0L },
        logs: MutableList<String> = mutableListOf(),
    ) = PlayStatsRepository(store, tracker, log = { logs += it })

    // ── Sesiones ─────────────────────────────────────────────────────

    @Test
    fun `the flow starts with the persisted records`() {
        val stored = PlayStats(3, favorite = true, lastPlayedAt = 500, totalPlayedMs = 1_000)
        val repo = repository(FakePlayStatsStore(listOf(stored)))
        assertEquals(mapOf(3 to stored), repo.stats.value)
    }

    @Test
    fun `end session persists a kept session and refreshes the flow`() {
        var now = 1_000L
        val store = FakePlayStatsStore()
        val logs = mutableListOf<String>()
        val repo = repository(store, PlaySessionTracker { now }, logs)

        repo.beginSession(11)
        now = 6_000L
        val session = repo.endSession()

        assertNotNull(session)
        assertEquals(11, session!!.romId)
        assertEquals(5_000L, session.durationMs)
        assertEquals(listOf(Triple(11, 6_000L, 5_000L)), store.recorded)
        assertEquals(5_000L, repo.stats.value.getValue(11).totalPlayedMs)
        assertEquals(6_000L, repo.stats.value.getValue(11).lastPlayedAt)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `a session shorter than five seconds is discarded with a warning`() {
        var now = 1_000L
        val store = FakePlayStatsStore()
        val logs = mutableListOf<String>()
        val repo = repository(store, PlaySessionTracker { now }, logs)

        repo.beginSession(11)
        now = 5_999L
        assertNull(repo.endSession())

        assertTrue(store.recorded.isEmpty())
        assertTrue(repo.stats.value.isEmpty())
        assertEquals(1, logs.size)
        assertTrue("expected a WARN line, got ${logs.single()}", logs.single().startsWith("WARN"))
        assertTrue(
            "the warning names the 5 s threshold: ${logs.single()}",
            logs.single().contains("5"),
        )
    }

    @Test
    fun `ending without a running session is a silent no-op`() {
        val store = FakePlayStatsStore()
        val logs = mutableListOf<String>()
        val repo = repository(store, logs = logs)

        assertNull(repo.endSession())
        assertNull(repo.endSession())

        assertTrue(store.recorded.isEmpty())
        assertTrue(logs.isEmpty())
    }

    // ── Favoritos ────────────────────────────────────────────────────

    @Test
    fun `toggling the favorite writes the same value to every id of the group`() {
        val store = FakePlayStatsStore()
        val repo = repository(store)

        assertTrue(repo.toggleFavorite(listOf(101, 102, 103)))
        assertEquals(listOf(101, 102, 103), store.favoriteWrites.map { it.first })
        assertTrue(listOf(101, 102, 103).all { store.get(it)!!.favorite })
        assertTrue(repo.aggregate(listOf(101, 102, 103)).favorite)

        assertFalse(repo.toggleFavorite(listOf(101, 102, 103)))
        assertTrue(listOf(101, 102, 103).none { store.get(it)!!.favorite })
        assertFalse(repo.aggregate(listOf(101, 102, 103)).favorite)
    }

    @Test
    fun `a repeated id is written only once`() {
        val store = FakePlayStatsStore()
        repository(store).setFavorite(listOf(7, 7, 8), true)
        assertEquals(listOf(7, 8), store.favoriteWrites.map { it.first })
    }

    // ── Agregado y «Seguir jugando» ─────────────────────────────────

    @Test
    fun `the group aggregate is favorite any, last played max and time sum`() {
        val store = FakePlayStatsStore(
            listOf(
                PlayStats(1, favorite = true, lastPlayedAt = 500, totalPlayedMs = 1_000),
                PlayStats(2, favorite = false, lastPlayedAt = 900, totalPlayedMs = 3_000),
            ),
        )
        val group = repository(store).aggregate(listOf(1, 2, 3, 1))

        assertEquals(listOf(1, 2, 3), group.groupIds)
        assertTrue(group.favorite)
        assertEquals(900L, group.lastPlayedAt)
        assertEquals(4_000L, group.totalPlayedMs)
        assertTrue(group.hasPlayData)
    }

    @Test
    fun `a never played group has no last played date`() {
        val group = repository(FakePlayStatsStore()).aggregate(listOf(4, 5))

        assertFalse(group.favorite)
        assertNull(group.lastPlayedAt)
        assertEquals(0L, group.totalPlayedMs)
        assertFalse(group.hasPlayData)
    }

    @Test
    fun `recent returns at most five groups ordered by last played descending`() {
        assertEquals(5, PlayStatsLogic.CONTINUE_LIMIT)
        val store = FakePlayStatsStore(
            listOf(
                PlayStats(1, lastPlayedAt = 1_000, totalPlayedMs = 10),
                PlayStats(2, lastPlayedAt = 6_000, totalPlayedMs = 20),
                PlayStats(3, lastPlayedAt = 3_000, totalPlayedMs = 30),
                PlayStats(4, lastPlayedAt = 5_000, totalPlayedMs = 40),
                PlayStats(5, lastPlayedAt = 2_000, totalPlayedMs = 50),
                PlayStats(6, lastPlayedAt = 4_000, totalPlayedMs = 60),
            ),
        )
        val groups = listOf(listOf(1), listOf(2), listOf(3), listOf(4), listOf(5), listOf(6), listOf(7))
        val repo = repository(store)

        val recent = repo.recent(groups)

        assertEquals(5, recent.size)
        assertEquals(listOf(2, 4, 6, 3, 5), recent.map { it.groupIds.single() })
        assertEquals(listOf(2, 4), repo.recent(groups, limit = 2).map { it.groupIds.single() })
    }

    @Test
    fun `recent aggregates a multi disc group before ordering`() {
        val store = FakePlayStatsStore(
            listOf(
                PlayStats(8, lastPlayedAt = 8_000, totalPlayedMs = 100),
                PlayStats(9, lastPlayedAt = 9_000, totalPlayedMs = 200),
                PlayStats(10, lastPlayedAt = 9_500, totalPlayedMs = 1),
            ),
        )

        val recent = repository(store).recent(listOf(listOf(8, 9), listOf(10)))

        assertEquals(listOf(listOf(10), listOf(8, 9)), recent.map { it.groupIds })
        assertEquals(9_000L, recent[1].lastPlayedAt)
        assertEquals(300L, recent[1].totalPlayedMs)
    }
}
