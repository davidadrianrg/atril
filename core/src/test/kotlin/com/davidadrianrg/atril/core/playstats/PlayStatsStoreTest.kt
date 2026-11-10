package com.davidadrianrg.atril.core.playstats

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Almacén JSON de estadísticas: ida y vuelta entre instancias, favoritos,
 * acumulación de sesiones, recuperación de un fichero corrupto y lectura
 * defensiva del JSON. Usa una carpeta temporal propia y un reloj fijo; nunca
 * toca rutas del usuario (AC-04/AC-05/AC-11/AC-12).
 */
class PlayStatsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_700_000_000_000L

    private fun file(name: String = "play_stats.json") = File(tmp.root, name)

    private fun store(file: File, logs: MutableList<String> = mutableListOf()) =
        JsonPlayStatsStore(file, clock = { now }, log = { logs += it })

    @Test
    fun `a recorded session and a favorite survive a new store instance`() {
        val f = file()
        store(f).apply {
            recordSession(7, endedAt = now - 3_600_000, durationMs = 1_500_000)
            setFavorite(7, true)
        }

        val reopened = store(f)
        assertEquals(
            PlayStats(romId = 7, favorite = true, lastPlayedAt = now - 3_600_000, totalPlayedMs = 1_500_000),
            reopened.get(7),
        )
        assertEquals(1, reopened.all().size)
    }

    @Test
    fun `favorite toggles are persisted per game`() {
        val f = file()
        assertTrue(store(f).setFavorite(42, true).favorite)
        assertTrue(store(f).get(42)!!.favorite)
        assertFalse(store(f).setFavorite(42, false).favorite)
        assertFalse(store(f).get(42)!!.favorite)
    }

    @Test
    fun `marking a favorite on a new game keeps it at zero play time`() {
        val f = file()
        store(f).setFavorite(43, true)
        assertEquals(PlayStats(43, favorite = true), store(f).get(43))
    }

    @Test
    fun `two sessions add up and the last played never goes back`() {
        val f = file()
        val s = store(f)
        s.recordSession(9, endedAt = 1_000_000, durationMs = 60_000)
        s.recordSession(9, endedAt = 900_000, durationMs = 30_000)

        assertEquals(PlayStats(9, favorite = false, lastPlayedAt = 1_000_000, totalPlayedMs = 90_000), s.get(9))
        assertEquals(PlayStats(9, lastPlayedAt = 1_000_000, totalPlayedMs = 90_000), store(f).get(9))
    }

    @Test
    fun `a missing file is an empty store without warnings`() {
        val f = file("absent.json")
        val logs = mutableListOf<String>()
        val s = store(f, logs)

        assertTrue(s.all().isEmpty())
        assertNull(s.get(1))
        assertTrue(logs.isEmpty())
        assertFalse(File(f.path + ".corrupt").exists())
    }

    @Test
    fun `a corrupt file is kept aside and the store starts empty`() {
        val f = file()
        f.writeText("{not json")
        val logs = mutableListOf<String>()
        val s = store(f, logs)

        assertTrue(s.all().isEmpty())
        assertEquals("{not json", File(f.path + ".corrupt").readText())
        assertEquals(1, logs.size)
        assertTrue("expected a WARN line, got ${logs.single()}", logs.single().startsWith("WARN"))

        s.recordSession(1, endedAt = 1_000, durationMs = 5_000)
        assertEquals(1, store(f).all().size)
    }

    @Test
    fun `invalid, unknown and duplicate entries are dropped or coerced`() {
        val f = file()
        f.writeText(
            """
            [
              {"romId": 1, "favorite": true, "lastPlayedAt": 1000, "totalPlayedMs": 5000, "unknown": "ignored"},
              {"favorite": true, "lastPlayedAt": 2000},
              {"romId": 2, "favorite": false, "lastPlayedAt": -5, "totalPlayedMs": -100},
              {"romId": 3, "favorite": false, "lastPlayedAt": 9999999999999, "totalPlayedMs": 1000},
              {"romId": 1, "favorite": false, "lastPlayedAt": 2000, "totalPlayedMs": 99999}
            ]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                // La primera entrada de un romId repetido gana.
                PlayStats(1, favorite = true, lastPlayedAt = 1_000, totalPlayedMs = 5_000),
                // Una última partida no positiva o en un futuro imposible se descarta.
                PlayStats(2, favorite = false, lastPlayedAt = null, totalPlayedMs = 0),
                PlayStats(3, favorite = false, lastPlayedAt = null, totalPlayedMs = 1_000),
            ),
            store(f).all(),
        )
    }

    @Test
    fun `a failed write is logged as WARN and keeps the state in memory`() {
        // La ruta padre es un fichero normal: ni el temporal ni el destino se pueden escribir.
        val blocker = File(tmp.root, "not-a-directory").apply { writeText("x") }
        val target = File(blocker, "play_stats.json")
        val logs = mutableListOf<String>()
        val s = store(target, logs)

        val saved = s.setFavorite(5, true)

        assertTrue(saved.favorite)
        assertEquals(1, logs.size)
        assertTrue("expected a WARN line, got ${logs.single()}", logs.single().startsWith("WARN"))
        assertTrue("the in-memory state must survive a failed write", s.get(5)!!.favorite)
        assertTrue(s.all().isNotEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative session duration is a programmer error`() {
        store(file()).recordSession(1, endedAt = 1_000, durationMs = -1)
    }
}
