package com.davidadrianrg.atril.emulators

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.davidadrianrg.atril.MainActivity
import com.davidadrianrg.atril.R
import com.davidadrianrg.atril.core.emulators.VersionCompare
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.data.emulators.EmulatorRepository
import com.davidadrianrg.atril.data.emulators.EmulatorUpdatesStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Comprobación diaria de actualizaciones de los emuladores instalados.
 * Solo consulta apps presentes en el dispositivo para respetar el límite
 * anónimo de la API de GitHub; guarda el resultado y notifica si hay novedades.
 */
class EmulatorUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = EmulatorRepository(applicationContext)
        val entries = runCatching { repository.loadPack() }.getOrDefault(emptyList())
        val updates = mutableMapOf<String, String>()
        for (entry in entries) {
            val installed = repository.installedVersion(entry) ?: continue
            val release = runCatching { repository.latestRelease(entry) }.getOrNull() ?: continue
            if (VersionCompare.isNewer(release.version, installed)) {
                updates[entry.id] = release.version
            }
        }
        EmulatorUpdatesStore(File(applicationContext.filesDir, "emulator_updates.json")).save(updates)
        if (updates.isNotEmpty()) notify(updates.size)
        return Result.success()
    }

    private fun notify(count: Int) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    tr("emulators.notifications.channel"),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_EMULATORS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(tr("emulators.notifications.title"))
            .setContentText(tr("emulators.notifications.body", count))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "atril_emulators"
        const val NOTIFICATION_ID = 7301
        const val EXTRA_OPEN_EMULATORS = "open_emulators"
        private const val WORK_NAME = "atril-emulator-updates"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EmulatorUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
