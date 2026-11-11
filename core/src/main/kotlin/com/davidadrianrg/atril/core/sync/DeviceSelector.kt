package com.davidadrianrg.atril.core.sync

import com.davidadrianrg.atril.core.remote.dto.DeviceDto

/**
 * Chooses a device to reuse when RomM refuses to register a new one.
 *
 * Some RomM deployments return 403 on POST /api/devices (registration is
 * restricted even for API keys with devices.write). In that case sync still
 * works by attaching to a device that already exists on the account: prefer
 * the same platform, then any enabled device, then the first one.
 */
object DeviceSelector {

    fun pickExisting(
        devices: List<DeviceDto>,
        platform: String = "android",
    ): DeviceDto? {
        if (devices.isEmpty()) return null
        val enabled = devices.filter { it.syncEnabled != false }
        return enabled.firstOrNull { it.platform.equals(platform, ignoreCase = true) }
            ?: enabled.firstOrNull()
            ?: devices.first()
    }
}
