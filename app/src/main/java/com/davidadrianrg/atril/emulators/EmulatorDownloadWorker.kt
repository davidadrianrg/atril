package com.davidadrianrg.atril.emulators

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.davidadrianrg.atril.MainActivity
import com.davidadrianrg.atril.R
import com.davidadrianrg.atril.core.emulators.EmulatorEntry
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.data.emulators.EmulatorInstallQueue
import com.davidadrianrg.atril.data.emulators.EmulatorJobState
import com.davidadrianrg.atril.data.emulators.EmulatorRepository
import com.davidadrianrg.atril.data.emulators.GitHubRateLimitException
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Drena [EmulatorInstallQueue] en segundo plano: resuelve la release, descarga
 * y valida cada APK con una notificación de progreso en primer plano. No
 * instala (Android no deja abrir el instalador desde segundo plano): deja el
 * APK listo y avisa; la pantalla de Emuladores lanza el instalador.
 */
class EmulatorDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val notifications =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val queue = EmulatorInstallQueue.get(applicationContext)
        queue.recoverInterrupted()
        if (!queue.hasQueued()) return Result.success()
        ensureChannel(applicationContext)
        val repository = EmulatorRepository(applicationContext)
        var catalog: List<EmulatorEntry>? = null
        try {
            drain(queue, repository) { catalog ?: repository.loadPack().also { catalog = it } }
        } finally {
            // Si setForeground no pudo promoverse, la notificación de progreso
            // la publicamos nosotros: nunca debe quedar colgada.
            notifications.cancel(NOTIFICATION_ID)
        }
        syncReadyNotification(applicationContext, queue)
        return Result.success()
    }

    private suspend fun drain(
        queue: EmulatorInstallQueue,
        repository: EmulatorRepository,
        catalog: suspend () -> List<EmulatorEntry>,
    ) {
        while (!isStopped) {
            val job = queue.nextQueued() ?: break
            queue.markDownloading(job.entryId)
            setForegroundSafely(job.name, 0)
            try {
                val entry = catalog().firstOrNull { it.id == job.entryId }
                    ?: error(tr("emulators.queue.missing", job.name))
                val release = job.assetUrl?.let {
                    com.davidadrianrg.atril.core.emulators.SelectedRelease(
                        version = job.assetVersion ?: "",
                        assetName = job.assetName ?: it.substringAfterLast('/'),
                        downloadUrl = it,
                        sizeBytes = 0L,
                        tagName = "",
                    )
                } ?: repository.latestRelease(entry, dualScreen = job.dualScreen)
                    ?: error(tr("emulators.no_release", entry.name))
                var lastPercent = -1
                val file = repository.download(release, repository.expectedPackageOf(entry)) { p ->
                    queue.updateProgress(job.entryId, p)
                    val percent = (p * 100).toInt()
                    // La notificación se refresca por saltos de 2 %: el sistema
                    // limita las actualizaciones por segundo.
                    if (percent >= lastPercent + 2) {
                        lastPercent = percent
                        notifications.notify(NOTIFICATION_ID, progressNotification(job.name, percent))
                    }
                }
                repository.learnPackageName(entry, file)
                // Cancelado durante la descarga: el trabajo ya no existe.
                if (!queue.markReady(job.entryId, release.version, file.path)) file.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubRateLimitException) {
                val message = e.message.orEmpty()
                queue.markFailed(job.entryId, message)
                queue.failAllQueued(message)
            } catch (e: Exception) {
                queue.markFailed(job.entryId, e.message?.takeIf { it.isNotBlank() } ?: tr("emulators.error"))
            }
        }
    }

    private suspend fun setForegroundSafely(name: String, percent: Int) {
        val notification = progressNotification(name, percent)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
        // Sin permiso de notificaciones o con restricciones de primer plano
        // se sigue igual: el trabajo puede acabar sin promoverse.
        runCatching { setForeground(info) }
    }

    private fun progressNotification(name: String, percent: Int) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("emulators.downloads.progress", name))
            .setContentIntent(openEmulatorsIntent(applicationContext))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, percent <= 0)
            .build()

    companion object {
        const val CHANNEL_ID = "atril_emulator_downloads"
        const val NOTIFICATION_ID = 7302
        const val READY_NOTIFICATION_ID = 7303
        const val WORK_NAME = "atril-emulator-downloads"

        /**
         * Asegura que hay un worker drenando la cola. APPEND_OR_REPLACE: si
         * otro está terminando, el nuevo corre después y recoge lo añadido.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<EmulatorDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /**
         * Detiene la descarga en curso (el trabajo ya se quitó de la cola) y
         * relanza el drenado si quedan pendientes.
         */
        fun restart(context: Context, queue: EmulatorInstallQueue) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(WORK_NAME)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            if (queue.hasQueued()) enqueue(context)
        }

        /**
         * Una sola notificación resumen con los APK listos; desaparece en
         * cuanto no queda ninguno (instalados o quitados de la cola).
         */
        fun syncReadyNotification(context: Context, queue: EmulatorInstallQueue) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ready = queue.jobs.value.count { it.state == EmulatorJobState.READY }
            if (ready == 0) {
                manager.cancel(READY_NOTIFICATION_ID)
                return
            }
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle(tr("emulators.downloads.ready_title"))
                .setContentText(tr("emulators.downloads.ready_body", ready))
                .setContentIntent(openEmulatorsIntent(context))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            runCatching { manager.notify(READY_NOTIFICATION_ID, notification) }
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("emulators.downloads.channel"), NotificationManager.IMPORTANCE_LOW),
            )
        }

        private fun openEmulatorsIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(EmulatorUpdateWorker.EXTRA_OPEN_EMULATORS, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** El APK listo sigue en disco (la caché del sistema puede haberlo borrado). */
        fun apkExists(path: String?): Boolean = path != null && File(path).isFile
    }
}
