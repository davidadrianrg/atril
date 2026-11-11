package com.davidadrianrg.atril.launch

import android.content.Context
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.core.launch.LaunchHost
import com.davidadrianrg.atril.core.launch.LaunchTarget
import com.davidadrianrg.atril.core.launch.LaunchTargetResolver
import java.io.File

/**
 * Enruta un [GameLaunchRequest] al emulador o al instalador APK según
 * [LaunchTargetResolver] y unifica el contrato de error en `String?` (null =
 * lanzado), igual que hacía [EmulatorLauncher].
 */
class GameLauncher(
    private val emulatorLauncher: EmulatorLauncher,
    private val apkGameLauncher: ApkGameLauncher,
) {

    /** Prepara el lanzamiento sin abrir nada; los fallos vuelven como plan. */
    suspend fun prepare(request: GameLaunchRequest): GameLaunchPlan {
        return when (val target = LaunchTargetResolver.resolve(
            request.platformSlug,
            request.localPath,
            LaunchHost.ANDROID,
        )) {
            is LaunchTarget.Emulator -> {
                val plan = emulatorLauncher.prepare(
                    LaunchRequest(
                        platformSlug = request.platformSlug,
                        platformLabel = request.platformLabel,
                        localPath = target.path,
                        knownFileNames = request.knownFileNames,
                        overrideFamilyId = request.overrideFamilyId,
                        saveEmulatorId = request.saveEmulatorId,
                    ),
                )
                when (plan) {
                    is LaunchPlan.Ready -> GameLaunchPlan.Ready(plan)
                    is LaunchPlan.Failed -> GameLaunchPlan.Failed(plan.message)
                }
            }
            is LaunchTarget.ApkInstaller -> {
                val apk = File(target.path)
                if (apk.isFile) {
                    GameLaunchPlan.Apk(apk)
                } else {
                    GameLaunchPlan.Failed(
                        tr("launch.error.missing", target.path),
                        needsLocate = request.romId > 0,
                    )
                }
            }
            is LaunchTarget.InstalledApp ->
                GameLaunchPlan.InstalledApp(target.packageName)
            is LaunchTarget.InstalledAppUnsupported ->
                GameLaunchPlan.Failed(tr("launch.error.app_desktop", target.packageName))
            is LaunchTarget.ApkUnsupported ->
                GameLaunchPlan.Failed(tr("launch.error.apk_desktop"), needsLocate = request.romId > 0)
            is LaunchTarget.Missing ->
                GameLaunchPlan.Failed(
                    tr("launch.error.missing", target.path),
                    needsLocate = request.romId > 0,
                )
        }
    }

    /**
     * Arranca el plan. Devuelve null si se aceptó o el mensaje de error ya
     * traducido; un [GameLaunchPlan.Failed] nunca se arranca.
     */
    fun start(activityContext: Context, plan: GameLaunchPlan): String? = when (plan) {
        is GameLaunchPlan.Ready -> emulatorLauncher.start(activityContext, plan.plan)
        is GameLaunchPlan.Failed -> plan.message
        is GameLaunchPlan.Apk -> apkMessage(apkGameLauncher.launch(plan.apkFile))
        is GameLaunchPlan.InstalledApp -> apkMessage(apkGameLauncher.launchPackage(plan.packageName))
    }

    /**
     * Variante suspendida para la UI: inspecciona el APK en `Dispatchers.IO`
     * (parseo del manifiesto y consulta al PackageManager) y deja solo el
     * `startActivity` en el hilo principal.
     */
    suspend fun startSuspending(activityContext: Context, plan: GameLaunchPlan): String? = when (plan) {
        is GameLaunchPlan.Ready -> emulatorLauncher.start(activityContext, plan.plan)
        is GameLaunchPlan.Failed -> plan.message
        is GameLaunchPlan.Apk -> apkMessage(apkGameLauncher.launchSuspending(plan.apkFile))
        is GameLaunchPlan.InstalledApp -> apkMessage(apkGameLauncher.launchPackage(plan.packageName))
    }

    private fun apkMessage(result: ApkLaunchResult): String? = when (result) {
        is ApkLaunchResult.Opened, ApkLaunchResult.InstallerOpened -> null
        ApkLaunchResult.NeedsInstallPermission -> tr("android.apk.install_permission")
        is ApkLaunchResult.NotAnApk -> tr("launch.error.apk_invalid")
        is ApkLaunchResult.Missing -> tr("launch.error.missing", result.path)
        is ApkLaunchResult.NoLaunchActivity -> tr("launch.error.apk_no_launcher", result.packageName)
        is ApkLaunchResult.Error -> result.message
    }
}
