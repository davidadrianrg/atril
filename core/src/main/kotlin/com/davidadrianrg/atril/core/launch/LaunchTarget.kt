package com.davidadrianrg.atril.core.launch

import java.io.File

/** Plataforma que ejecuta el juego: cambia lo que significa un `.apk`. */
enum class LaunchHost { ANDROID, DESKTOP }

/** Qué debe hacer el lanzador con la ruta local de un juego. */
sealed interface LaunchTarget {
    /** ROM normal: se entrega al emulador configurado. */
    data class Emulator(val path: String) : LaunchTarget

    /** APK en Android: abrir la app instalada o el instalador del sistema. */
    data class ApkInstaller(val path: String) : LaunchTarget

    /** APK en escritorio: no hay forma de lanzarlo. */
    data class ApkUnsupported(val path: String) : LaunchTarget

    /** App Android ya instalada, referenciada como `package:<name>`. */
    data class InstalledApp(val packageName: String) : LaunchTarget

    /** App Android referenciada en escritorio: no se puede lanzar. */
    data class InstalledAppUnsupported(val packageName: String) : LaunchTarget

    /** Ruta vacía o fichero inexistente. */
    data class Missing(val path: String) : LaunchTarget
}

/**
 * Decide el destino de lanzamiento a partir de la plataforma, la ruta y el
 * host. Es puro y total: no lanza y solo consulta `File.isFile`.
 */
object LaunchTargetResolver {

    /** Prefijo de ruta para apps Android ya instaladas. */
    const val PACKAGE_PREFIX: String = "package:"

    /** Plataformas cuyos ficheros son APK aunque la extensión no lo diga. */
    val APK_PLATFORMS: Set<String> = setOf("android", "android-games")

    /**
     * Reglas, en orden: ruta vacía o inexistente => [LaunchTarget.Missing];
     * `.apk` (cualquier caja) o plataforma Android => instalador en Android y
     * [LaunchTarget.ApkUnsupported] en escritorio; el resto => emulador.
     */
    fun resolve(platformSlug: String, localPath: String, host: LaunchHost): LaunchTarget {
        if (localPath.isBlank()) return LaunchTarget.Missing(localPath)

        // Apps instaladas: no dependen de un fichero, solo del paquete.
        if (localPath.startsWith(PACKAGE_PREFIX)) {
            val pkg = localPath.removePrefix(PACKAGE_PREFIX).trim()
            if (pkg.isBlank()) return LaunchTarget.Missing(localPath)
            return when (host) {
                LaunchHost.ANDROID -> LaunchTarget.InstalledApp(pkg)
                LaunchHost.DESKTOP -> LaunchTarget.InstalledAppUnsupported(pkg)
            }
        }

        val file = File(localPath)
        if (!file.isFile) return LaunchTarget.Missing(localPath)

        val isApk = file.extension.equals("apk", ignoreCase = true) ||
            LaunchPlatforms.canonical(platformSlug) in APK_PLATFORMS
        if (!isApk) return LaunchTarget.Emulator(localPath)

        return when (host) {
            LaunchHost.ANDROID -> LaunchTarget.ApkInstaller(localPath)
            LaunchHost.DESKTOP -> LaunchTarget.ApkUnsupported(localPath)
        }
    }
}
