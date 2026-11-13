package com.davidadrianrg.atril.data.report

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.davidadrianrg.atril.BuildConfig
import com.davidadrianrg.atril.util.CrashLogger
import java.util.Locale

/**
 * Datos técnicos que acompañan a un reporte. Nunca incluye credenciales,
 * rutas personales ni el contenido de la biblioteca: solo el dispositivo,
 * la versión y un resumen de ajustes no sensibles.
 */
object DeviceReportCollector {

    fun collect(context: Context, rommConfigured: Boolean): String {
        val settings = com.davidadrianrg.atril.data.local.SettingsDataStore(context)
        val theme = settings.getThemeBlocking().ifBlank { "default" }
        val launcherBackground = settings.getLauncherBackgroundBlocking()
        val pm = context.packageManager
        val appVersion = runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val freeGb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
        val totalGb = stat.totalBytes / 1024.0 / 1024.0 / 1024.0

        return buildString {
            appendLine("### Device")
            appendLine("- App: Atril $appVersion")
            appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("- Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("- ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("- Locale: ${Locale.getDefault().toLanguageTag()}")
            appendLine("- Theme: $theme")
            appendLine("- Launcher background: $launcherBackground")
            appendLine("- Free storage: %.1f / %.1f GB".format(freeGb, totalGb))
            appendLine()
            appendLine("### App state")
            appendLine("- RomM configured: $rommConfigured")
            appendLine("- Storage permission: ${StoragePermission.granted(context)}")
            CrashLogger.read(context)?.let { crash ->
                appendLine()
                appendLine("### Last crash")
                appendLine("```")
                appendLine(crash.take(4000))
                appendLine("```")
            }
        }
    }
}

private object StoragePermission {
    fun granted(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }.getOrDefault(false)
}
