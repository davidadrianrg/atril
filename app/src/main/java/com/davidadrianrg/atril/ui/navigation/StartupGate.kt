package com.davidadrianrg.atril.ui.navigation

import com.davidadrianrg.atril.data.local.ServerConfig

/**
 * Puerta de arranque (M5).
 *
 * Mientras DataStore no ha emitido los ajustes no se sabe si hace falta el
 * primer arranque guiado: antes se componía Ajustes con una config vacía y,
 * al llegar la real, el grafo se rehacía hacia Play (Ajustes creado y
 * destruido en cada arranque en frío). Ahora no hay destino hasta la primera
 * emisión y la actividad mantiene la splash del sistema mientras tanto.
 */
object StartupGate {

    /** Tope para no dejar la splash colgada si DataStore no responde. */
    const val MAX_SPLASH_MS = 2_000L

    /**
     * Ruta inicial del grafo, o null si los ajustes aún no han cargado (no se
     * compone ninguna pantalla).
     */
    fun startRoute(
        settings: ServerConfig?,
        hasStorageAccess: Boolean,
        startInUpdates: Boolean,
        startInEmulators: Boolean,
    ): String? = when {
        settings == null -> null
        needsOnboarding(settings, hasStorageAccess) -> Screen.Config.route
        startInUpdates -> Screen.Config.route
        startInEmulators -> Screen.Emulators.route
        else -> Screen.Play.route
    }

    fun needsOnboarding(settings: ServerConfig, hasStorageAccess: Boolean): Boolean =
        !settings.canStart || !hasStorageAccess
}
