package com.davidadrianrg.atril.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherInfoLineTest {

    private val sep = GameMetadataLine.SEPARATOR

    @Test
    fun `joins title, metadata and last played in order`() {
        assertEquals(
            "Chrono Trigger${sep}1995${sep}RPG${sep}Last played${sep}yesterday",
            LauncherInfoLine.format("Chrono Trigger", "1995${sep}RPG", "Last played${sep}yesterday"),
        )
    }

    @Test
    fun `missing or blank parts are dropped`() {
        assertEquals("Zelda", LauncherInfoLine.format("Zelda", null, null))
        assertEquals("Zelda${sep}ayer", LauncherInfoLine.format(" Zelda ", "  ", "ayer"))
        assertEquals(listOf("Zelda", "1991"), LauncherInfoLine.parts("Zelda", "1991", ""))
    }
}
