package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.playstats.PlayStats
import com.davidadrianrg.atril.core.remote.dto.RomDto
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cableado de favoritos y estadísticas en el escritorio: el favorito se escribe
 * en todos los discos del grupo, el agregado lo refleja y el dato sobrevive a un
 * arranque nuevo. Todo ocurre bajo el `user.home` aislado de build/test-home que
 * fija desktop/build.gradle.kts: nunca se toca la configuración real.
 */
class DesktopPlayStatsWiringTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun isolatedConfig() {
        assertTrue(
            "desktop tests must run under the isolated build/test-home",
            System.getProperty("java.util.prefs.userRoot").orEmpty().contains("test-home"),
        )
        DesktopConfig.serverUrl = ""
        DesktopConfig.apiKey = ""
        // Estado limpio del fichero de estadísticas dentro del home aislado.
        File(DesktopConfig.configDir, "play_stats.json").delete()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun rom(id: Int) = RomDto(
        id = id,
        name = "Juego $id",
        fileName = "juego-$id.gba",
        platformId = 1,
        platformSlug = "gba",
    )

    private fun card(vararg ids: Int): GameCard {
        val roms = ids.map(::rom)
        return GameCard(rep = roms.first(), discCount = roms.size, groupRoms = roms, downloaded = true)
    }

    @Test
    fun `a game without stats is not favorite and has never been played`() {
        val stats = DesktopAppState(scope).gameStats(card(901_001, 901_002))

        assertFalse(stats.favorite)
        assertNull(stats.lastPlayedAt)
        assertEquals(0L, stats.totalPlayedMs)
        assertFalse(stats.hasPlayData)
    }

    @Test
    fun `toggling a favorite writes every disc of the group and updates the aggregate`() {
        val state = DesktopAppState(scope)
        val card = card(901_101, 901_102, 901_103)

        state.toggleFavorite(card)

        assertTrue(state.playStats.stats.value.getValue(901_101).favorite)
        assertTrue(state.playStats.stats.value.getValue(901_102).favorite)
        assertTrue(state.playStats.stats.value.getValue(901_103).favorite)
        assertTrue(state.gameStats(card).favorite)

        state.toggleFavorite(card)
        assertFalse(state.gameStats(card).favorite)
    }

    @Test
    fun `the favorite is persisted and survives a new app state`() {
        val card = card(901_201)
        DesktopAppState(scope).toggleFavorite(card)

        val restarted = DesktopAppState(scope)

        assertTrue(restarted.gameStats(card).favorite)
        assertEquals(PlayStats(901_201, favorite = true), restarted.playStats.stats.value.getValue(901_201))
    }
}
