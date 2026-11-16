package com.davidadrianrg.atril.desktop

import androidx.compose.runtime.mutableStateOf
import com.davidadrianrg.atril.core.library.CoverOverrides
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Carátulas elegidas a mano por el usuario: misma lógica que Android
 * ([CoverOverrides], en `~/.config/atril/cover-overrides/`). Tiene prioridad
 * sobre la de RomM y el caché (ver `coverUrlFor`).
 */
object DesktopCovers {
    private val overrides = CoverOverrides(
        file = File(DesktopConfig.configDir, "cover_overrides.json"),
        dir = File(DesktopConfig.configDir, "cover-overrides"),
    )

    /**
     * Copia en estado de Compose: `coverUrlFor` se llama en composición y, al
     * leerlo aquí, las tarjetas se recomponen solas al elegir/quitar carátula.
     */
    private val snapshot = mutableStateOf(overrides.covers.value)

    val covers: StateFlow<Map<Int, String>> = overrides.covers

    fun cover(romId: Int): String? = snapshot.value[romId]

    suspend fun setFromUrl(romId: Int, url: String): Boolean = update { overrides.setFromUrl(romId, url) }

    suspend fun setFromFile(romId: Int, source: File): Boolean = update { overrides.setFromFile(romId, source) }

    suspend fun clear(romId: Int) {
        update { overrides.clear(romId); true }
    }

    private suspend fun update(action: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching(action).getOrDefault(false)
        snapshot.value = overrides.covers.value
        ok
    }
}
