package com.davidadrianrg.atril.launch

import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.util.Log
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.data.update.ApkInstallHelper
import com.davidadrianrg.atril.data.update.InstallLaunchResult
import java.io.File

/** Resultado de intentar abrir o instalar un APK. */
sealed interface ApkLaunchResult {
    /** Ya estaba instalado y se abrió su pantalla de inicio. */
    data class Opened(val packageName: String) : ApkLaunchResult

    /** No estaba instalado: se abrió el instalador del sistema. */
    data object InstallerOpened : ApkLaunchResult

    /** Falta el permiso "Instalar apps desconocidas"; se abrió Ajustes. */
    data object NeedsInstallPermission : ApkLaunchResult

    /** El fichero no es un APK legible. */
    data class NotAnApk(val path: String) : ApkLaunchResult

    /** El fichero ya no existe. */
    data class Missing(val path: String) : ApkLaunchResult

    /** Está instalado pero no tiene actividad de lanzamiento. */
    data class NoLaunchActivity(val packageName: String) : ApkLaunchResult

    /** Fallo externo con mensaje ya traducido. */
    data class Error(val message: String) : ApkLaunchResult
}

/**
 * Lanza un juego APK: si el paquete ya está instalado abre la app; si no,
 * delega en [ApkInstallHelper] para abrir el instalador del sistema. No
 * reinstala una app presente y nunca lanza excepciones a la UI.
 */
class ApkGameLauncher(
    private val context: Context,
    private val installHelper: ApkInstallHelper,
) {

    /** Abre o instala [apkFile] y devuelve qué ocurrió realmente. */
    fun launch(apkFile: File): ApkLaunchResult {
        if (!apkFile.isFile) return ApkLaunchResult.Missing(apkFile.path)

        val pm = context.packageManager
        val archive = packageArchiveInfo(pm, apkFile)
        val packageName = archive?.packageName ?: return ApkLaunchResult.NotAnApk(apkFile.path)

        if (!isInstalled(pm, packageName)) {
            return when (val result = installHelper.launchInstaller(apkFile)) {
                InstallLaunchResult.Started -> ApkLaunchResult.InstallerOpened
                InstallLaunchResult.NeedsPermission -> ApkLaunchResult.NeedsInstallPermission
                is InstallLaunchResult.Error -> ApkLaunchResult.Error(result.message)
            }
        }

        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: return ApkLaunchResult.NoLaunchActivity(packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ApkLaunchResult.Opened(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open installed APK ($packageName) ${e.javaClass.simpleName}")
            ApkLaunchResult.Error(tr("launch.error.failed", packageName, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun packageArchiveInfo(pm: PackageManager, apkFile: File) = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.path, 0)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read APK package (${e.javaClass.simpleName})")
        null
    }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't check installed package ($packageName) ${e.javaClass.simpleName}")
        false
    }

    private companion object {
        const val TAG = "ApkGameLauncher"
    }
}
