package com.davidadrianrg.atril.core.sync

import com.davidadrianrg.atril.core.remote.dto.DeviceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceSelectorTest {

    private fun device(
        id: String,
        platform: String? = "android",
        enabled: Boolean? = true,
    ) = DeviceDto(id = id, name = id, platform = platform, syncEnabled = enabled)

    @Test
    fun `picks android device first`() {
        val devices = listOf(
            device("desktop", platform = "linux"),
            device("phone", platform = "android"),
        )
        assertEquals("phone", DeviceSelector.pickExisting(devices)?.id)
    }

    @Test
    fun `falls back to first enabled device when no platform matches`() {
        val devices = listOf(
            device("disabled", platform = "android", enabled = false),
            device("linux", platform = "linux"),
        )
        assertEquals("linux", DeviceSelector.pickExisting(devices)?.id)
    }

    @Test
    fun `returns null for empty list`() {
        assertNull(DeviceSelector.pickExisting(emptyList()))
    }

    @Test
    fun `uses platform case-insensitively`() {
        val devices = listOf(device("phone", platform = "Android"))
        assertEquals("phone", DeviceSelector.pickExisting(devices)?.id)
    }
}
