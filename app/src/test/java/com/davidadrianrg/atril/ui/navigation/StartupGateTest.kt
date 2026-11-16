package com.davidadrianrg.atril.ui.navigation

import com.davidadrianrg.atril.data.local.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupGateTest {

    private val configured = ServerConfig("http://romm", "key", "/roms", 2)
    private val empty = ServerConfig("", "", "", 2)

    @Test
    fun `no destination until settings have loaded`() {
        assertNull(StartupGate.startRoute(null, hasStorageAccess = true, startInUpdates = false, startInEmulators = false))
        assertNull(StartupGate.startRoute(null, hasStorageAccess = false, startInUpdates = true, startInEmulators = true))
    }

    @Test
    fun `configured device starts in Play`() {
        assertEquals(
            Screen.Play.route,
            StartupGate.startRoute(configured, hasStorageAccess = true, startInUpdates = false, startInEmulators = false),
        )
    }

    @Test
    fun `onboarding wins over notification targets`() {
        assertEquals(
            Screen.Config.route,
            StartupGate.startRoute(empty, hasStorageAccess = true, startInUpdates = false, startInEmulators = true),
        )
        assertEquals(
            Screen.Config.route,
            StartupGate.startRoute(configured, hasStorageAccess = false, startInUpdates = false, startInEmulators = true),
        )
    }

    @Test
    fun `notification targets open their screen once configured`() {
        assertEquals(
            Screen.Config.route,
            StartupGate.startRoute(configured, hasStorageAccess = true, startInUpdates = true, startInEmulators = false),
        )
        assertEquals(
            Screen.Emulators.route,
            StartupGate.startRoute(configured, hasStorageAccess = true, startInUpdates = false, startInEmulators = true),
        )
    }
}
