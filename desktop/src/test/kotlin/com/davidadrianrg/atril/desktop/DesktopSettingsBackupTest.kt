package com.davidadrianrg.atril.desktop

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        ),
    )

    @Test
    fun `export without secrets keeps preferences and drops credentials and device state`() {
        val json = parse(DesktopSettingsBackup(source).export(includeSecrets = false))

        assertEquals(2, (json["version"] as Number).toInt())
        assertEquals("amoled", json["theme"])
        @Suppress("UNCHECKED_CAST")
        val settings = json["desktopSettings"] as Map<String, String>
        assertEquals("/home/me/ROMs", settings["roms_root"])
        assertEquals("3", settings["max_concurrent_downloads"])
        assertEquals("fanart", json["launcherBackground"])
        listOf(
            "server_url", "api_key", "client_device_id", "device_id", "last_sync_at",
            "scraper_ss_password", "game_backgrounds",
        )
            .forEach { assertFalse(it, settings.containsKey(it)) }
        assertFalse(json.containsKey("apiKey"))
        assertFalse(json.containsKey("serverUrl"))
    }

    @Test
    fun `round trip with secrets restores everything and reconnects`() {
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
    fun `an android backup only brings shared preferences and server`() {
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
    fun `crafted desktop settings cannot inject credentials or device ids`() {
        val target = MapPrefs()
        DesktopSettingsBackup(target).import(
            """{"version":2,"desktopSettings":{"api_key":"x","device_id":"9","theme":"y","n":3}}""",
        )
        assertEquals(mapOf("theme" to "y"), target.map)
    }

    @Test
    fun `rejects foreign json and newer schemas`() {
        val target = MapPrefs()
        val backup = DesktopSettingsBackup(target)
        assertEquals(DesktopBackupResult.INVALID, backup.import("nope"))
        assertEquals(DesktopBackupResult.INVALID, backup.import("""{"theme":"x"}"""))
        assertEquals(DesktopBackupResult.UNSUPPORTED_VERSION, backup.import("""{"version":99,"theme":"x"}"""))
        assertTrue(target.map.isEmpty())
    }
}
