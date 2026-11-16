package com.davidadrianrg.atril.desktop

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DesktopSettingsBackupTest {

    private class MapPrefs(val map: MutableMap<String, String> = linkedMapOf()) : PrefsAccess {
        override fun keys() = map.keys.toList()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    private fun parse(json: String): Map<String, Any?> =
        Moshi.Builder().build()
            .adapter<Map<String, Any?>>(
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
            )
            .fromJson(json)!!

    private val source = MapPrefs(
        linkedMapOf(
            "server_url" to "http://romm.lan",
            "api_key" to "secret",
            "client_device_id" to "atril-desktop-1",
            "device_id" to "7",
            "last_sync_at" to "123",
            "scraper_ss_password" to "pw",
            "theme" to "amoled",
            "language" to "es",
            "roms_root" to "/home/me/ROMs",
            "max_concurrent_downloads" to "3",
            "close_to_tray" to "true",
            "launcher_background" to "fanart",
            "game_backgrounds" to "1=/home/me/.config/atril/game_backgrounds/1.jpg",
            "cover_overrides" to "1=/home/me/.config/atril/covers/1.jpg",
        ),
    )

    @Test
    fun `export without secrets keeps preferences and drops credentials and device state`() = runBlocking {
        val json = parse(DesktopSettingsBackup(source).export(includeSecrets = false))

        assertEquals(3, (json["version"] as Number).toInt())
        assertEquals("amoled", json["theme"])
        @Suppress("UNCHECKED_CAST")
        val settings = json["desktopSettings"] as Map<String, String>
        assertEquals("/home/me/ROMs", settings["roms_root"])
        assertEquals("3", settings["max_concurrent_downloads"])
        assertEquals("fanart", json["launcherBackground"])
        listOf(
            "server_url", "api_key", "client_device_id", "device_id", "last_sync_at",
            "scraper_ss_password", "game_backgrounds", "cover_overrides",
        )
            .forEach { assertFalse(it, settings.containsKey(it)) }
        assertFalse(json.containsKey("apiKey"))
        assertFalse(json.containsKey("serverUrl"))
    }

    @Test
    fun `round trip with secrets restores everything and reconnects`() = runBlocking {
        val json = DesktopSettingsBackup(source).export(includeSecrets = true)
        val target = MapPrefs(linkedMapOf("client_device_id" to "atril-desktop-2"))
        var reconnects = 0

        assertEquals(DesktopBackupResult.OK, DesktopSettingsBackup(target) { reconnects++ }.import(json))

        assertEquals("http://romm.lan", target.map["server_url"])
        assertEquals("secret", target.map["api_key"])
        assertEquals("pw", target.map["scraper_ss_password"])
        assertEquals("true", target.map["close_to_tray"])
        // El id de este equipo no se pisa con el del otro.
        assertEquals("atril-desktop-2", target.map["client_device_id"])
        assertNull(target.map["device_id"])
        assertEquals(1, reconnects)
    }

    @Test
    fun `an android backup only brings shared preferences and server`() = runBlocking {
        val android = """
            {"version":2,"theme":"crimson","language":"en","serverUrl":"http://host/","apiKey":"k",
             "settings":{"max_concurrent_downloads":"i:4","library_view_mode":"s:grid",
               "save_sync_enabled":"s:false","roms_root_path":"s:/storage/emulated/0/ROMs",
               "conflict_policy":"i:notanint"}}
        """.trimIndent()
        val target = MapPrefs()

        assertEquals(DesktopBackupResult.OK, DesktopSettingsBackup(target).import(android))

        assertEquals("4", target.map["max_concurrent_downloads"])
        assertEquals("grid", target.map["library_view_mode"])
        assertEquals("false", target.map["save_sync_enabled"])
        assertEquals("http://host", target.map["server_url"])
        assertEquals("crimson", target.map["theme"])
        assertFalse(target.map.containsKey("roms_root_path"))
        assertFalse(target.map.containsKey("conflict_policy"))
    }

    @Test
    fun `crafted desktop settings cannot inject credentials or device ids`() = runBlocking {
        val target = MapPrefs()
        DesktopSettingsBackup(target).import(
            """{"version":2,"desktopSettings":{"api_key":"x","device_id":"9","theme":"y","n":3}}""",
        )
        assertEquals(mapOf("theme" to "y"), target.map)
    }

    @Test
    fun `rejects foreign json and newer schemas`() = runBlocking {
        val target = MapPrefs()
        val backup = DesktopSettingsBackup(target)
        assertEquals(DesktopBackupResult.INVALID, backup.import("nope"))
        assertEquals(DesktopBackupResult.INVALID, backup.import("""{"theme":"x"}"""))
        assertEquals(DesktopBackupResult.UNSUPPORTED_VERSION, backup.import("""{"version":99,"theme":"x"}"""))
        assertTrue(target.map.isEmpty())
    }

    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    private fun library(name: String) = Pair(
        com.davidadrianrg.atril.core.playstats.PlayStatsRepository(
            com.davidadrianrg.atril.core.playstats.JsonPlayStatsStore(tmp.root.resolve("$name-stats.json")),
        ),
        com.davidadrianrg.atril.core.localgames.LocalGamesRepository(
            store = com.davidadrianrg.atril.core.localgames.LocalGameStore(tmp.root.resolve("$name-local.json")),
            coversDir = tmp.root.resolve("$name-covers"),
            credentials = { com.davidadrianrg.atril.core.localgames.ScraperCredentials() },
        ),
    )

    @Test
    fun `desktop round trip merges play stats and local games keeping their ids`() = runBlocking {
        val (srcStats, srcLocal) = library("src")
        val game = srcLocal.add("Doom", "dos", "/games/doom.exe")
        val localId = game.romId!!
        srcStats.setFavorite(listOf(10, localId), true)
        val json = DesktopSettingsBackup(MapPrefs(), srcStats, srcLocal).export(includeSecrets = false)

        val (dstStats, dstLocal) = library("dst")
        dstStats.setFavorite(listOf(20), true)
        assertEquals(DesktopBackupResult.OK, DesktopSettingsBackup(MapPrefs(), dstStats, dstLocal).import(json))

        assertEquals(listOf(game.id), dstLocal.games.value.map { it.id })
        assertEquals(localId, dstLocal.games.value.single().romId)
        assertEquals(setOf(10, 20, localId), dstStats.stats.value.filterValues { it.favorite }.keys)
    }

    @Test
    fun `an android v3 backup brings romm play stats but not android local games`() = runBlocking {
        val android = """
            {"version":3,"platform":"android",
             "playStats":[{"romId":5,"favorite":true,"lastPlayedAt":1700000000000,"totalPlayedMs":60000},
                          {"romId":-77,"favorite":true}],
             "localGames":[{"id":"a","romId":-77,"title":"X","platformSlug":"gba","path":"content://x"}]}
        """.trimIndent()
        val (stats, local) = library("android")

        assertEquals(DesktopBackupResult.OK, DesktopSettingsBackup(MapPrefs(), stats, local).import(android))

        assertEquals(setOf(5), stats.stats.value.keys)
        assertEquals(60000L, stats.stats.value.getValue(5).totalPlayedMs)
        assertTrue(local.games.value.isEmpty())
    }
}
