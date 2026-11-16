package com.davidadrianrg.atril.data.emulators

import com.davidadrianrg.atril.core.emulators.VersionCompare

/** Decisiones puras del flujo «APK listo → instalador del sistema». */
object EmulatorInstallFlow {

    /**
     * Siguiente APK listo que abrir solo, una única vez por trabajo: si el
     * usuario cancela el instalador no se le vuelve a abrir en cada regreso
     * a la pantalla (le queda el botón Instalar o quitarlo de la cola).
     */
    fun nextAutoInstall(jobs: List<EmulatorJob>, attempted: Set<String>): EmulatorJob? =
        jobs.firstOrNull { it.state == EmulatorJobState.READY && it.entryId !in attempted }

    /**
     * Tras volver del instalador: la instalación terminó si la versión
     * instalada cambió o ya no es más antigua que la descargada (cubre las
     * reinstalaciones de la misma versión y etiquetas `v1.2` vs `1.2`).
     */
    fun installFinished(before: String?, after: String?, downloadedVersion: String?): Boolean =
        after != null && (after != before || !VersionCompare.isNewer(downloadedVersion, after))
}
