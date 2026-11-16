package com.davidadrianrg.atril.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceSnapshotTest {

    @Test
    fun `encode tags every supported type and round-trips through decode`() {
        val values = mapOf(
            "roms_root_path" to "/storage/emulated/0/ROMs",
            "wifi_only_downloads" to true,
            "max_concurrent_downloads" to 3,
            "some_long" to 42L,
            "launcher_blur_strength" to 12.5f,
            "launch_emulator_overrides" to "psx=duckstation;n64=mupen:extra",
        )

        val encoded = PreferenceSnapshot.encode(values)

        assertEquals("b:true", encoded["wifi_only_downloads"])
        assertEquals("i:3", encoded["max_concurrent_downloads"])
        assertEquals(values, PreferenceSnapshot.decode(encoded))
    }

    @Test
    fun `credentials, server and device state never enter the snapshot`() {
        val encoded = PreferenceSnapshot.encode(
            mapOf(
                "server_url" to "http://romm.lan",
                "local_only" to false,
                "api_key" to "legacy",
                "scraper_ss_password" to "pw",
                "scraper_steamgriddb_key" to "k",
                "client_device_id" to "atril-android-x",
                "sync_device_id" to 7,
                "last_sync_timestamp" to 1L,
                "save_sync_watch_enabled" to true,
                "game_backgrounds" to "1=/data/bg/1.jpg",
                "theme" to "amoled",
            ),
        )

        assertEquals(setOf("theme"), encoded.keys)
    }

    @Test
    fun `decode ignores blocked keys even if a crafted file carries them`() {
        val decoded = PreferenceSnapshot.decode(
            mapOf("server_url" to "s:http://evil", "local_only" to "b:false", "theme" to "s:x"),
        )

        assertEquals(mapOf<String, Any>("theme" to "x"), decoded)
    }

    @Test
    fun `decode drops malformed entries instead of failing the whole import`() {
        val decoded = PreferenceSnapshot.decode(
            mapOf(
                "a" to 3.0,           // no es texto (JSON manipulado)
                "b" to "i:notanint",
                "c" to "b:maybe",
                "d" to "x:unknown",
                "e" to "no-tag",
                5 to "s:clave no textual",
                "ok" to "s:",
            ),
        )

        assertEquals(mapOf<String, Any>("ok" to ""), decoded)
    }

    @Test
    fun `a desktop backup contributes only the shared preferences, typed`() {
        val tagged = PreferenceSnapshot.fromDesktop(
            mapOf(
                "max_concurrent_downloads" to "4",
                "save_sync_enabled" to "false",
                "auto_physical_media" to "true",
                "library_view_mode" to "grid",
                "roms_root" to "/home/me/ROMs",
                "close_to_tray" to "true",
            ),
        )

        assertEquals(
            mapOf<String, Any>(
                "max_concurrent_downloads" to 4,
                "save_sync_enabled" to "false",
                "auto_physical_media" to true,
                "library_view_mode" to "grid",
            ),
            PreferenceSnapshot.decode(tagged),
        )
    }

    @Test
    fun `isBackedUp keeps library and emulator preferences`() {
        assertTrue(PreferenceSnapshot.isBackedUp("library_view_mode"))
        assertTrue(PreferenceSnapshot.isBackedUp("installed_app_links"))
        assertFalse(PreferenceSnapshot.isBackedUp("scraper_ss_user"))
    }
}
