package com.davidadrianrg.atril.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Una app Android instalada con actividad de lanzamiento. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isGame: Boolean,
    val icon: ImageBitmap?,
)

/**
 * Lista las apps instaladas que se pueden abrir. El manifiesto ya declara
 * visibilidad para `MAIN/LAUNCHER`, así que no hace falta QUERY_ALL_PACKAGES.
 */
class InstalledAppsRepository(private val context: Context) {

    suspend fun list(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        resolved
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { activity ->
                val info = activity.applicationInfo
                InstalledApp(
                    packageName = activity.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(activity.packageName),
                    isGame = isGame(info),
                    icon = runCatching {
                        info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                    }.getOrNull(),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun isGame(info: ApplicationInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            info.category == ApplicationInfo.CATEGORY_GAME
        } else {
            false
        }
}
