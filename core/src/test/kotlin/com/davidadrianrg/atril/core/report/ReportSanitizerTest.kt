package com.davidadrianrg.atril.core.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSanitizerTest {

    @Test
    fun `server urls and lan ips are hidden`() {
        val out = ReportSanitizer.scrub(
            "java.net.ConnectException: Failed to connect to /192.168.1.20:8080 (https://romm.casa.example/api/roms?x=1)",
        )
        assertFalse(out.contains("192.168"))
        assertFalse(out.contains("romm.casa"))
        assertTrue(out.contains("<ip>"))
        assertTrue(out.contains("<url>"))
    }

    @Test
    fun `user paths keep only the system prefix`() {
        assertEquals(
            "open failed: /storage/emulated/0/<path>: EACCES",
            ReportSanitizer.scrub("open failed: /storage/emulated/0/ROMs/psx/Mi juego.chd: EACCES"),
        )
        assertEquals("/home/<user>/<path>", ReportSanitizer.scrub("/home/david/Games/x.iso"))
        assertEquals("/storage/1234-ABCD/<path>", ReportSanitizer.scrub("/storage/1234-ABCD/ROMs/a.gba"))
    }

    @Test
    fun `emails are hidden and stack frames survive`() {
        val trace = "at com.davidadrianrg.atril.MainActivity.onCreate(MainActivity.kt:42)\nuser me@example.com"
        val out = ReportSanitizer.scrub(trace)
        assertTrue(out.contains("MainActivity.kt:42"))
        assertFalse(out.contains("me@example.com"))
    }
}
