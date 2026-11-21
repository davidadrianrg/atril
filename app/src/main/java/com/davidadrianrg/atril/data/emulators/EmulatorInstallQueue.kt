package com.davidadrianrg.atril.data.emulators

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado de un emulador en la cola de descargas. */
enum class EmulatorJobState { QUEUED, DOWNLOADING, READY, FAILED }

/**
 * Un emulador pedido para instalar/actualizar. [apkPath] y [version] existen
 * cuando el APK ya está descargado ([EmulatorJobState.READY]); [progress] solo
 * vive en memoria (no se persiste en cada porcentaje).
 */
data class EmulatorJob(
    val entryId: String,
    val name: String,
    val dualScreen: Boolean,
    val state: EmulatorJobState = EmulatorJobState.QUEUED,
    val progress: Float = 0f,
    val version: String? = null,
    val apkPath: String? = null,
    val error: String? = null,
    /** Asset elegido a mano (URL); el worker lo usa en vez de auto-seleccionar. */
    val assetUrl: String? = null,
    val assetName: String? = null,
    val assetVersion: String? = null,
)

/**
 * Cola persistente de descargas de emuladores. Es la fuente de verdad que
 * comparten [com.davidadrianrg.atril.emulators.EmulatorDownloadWorker] (la
 * drena en segundo plano) y la pantalla de Emuladores (la observa y lanza el
 * instalador): sobrevive a salir de la pantalla y a la muerte del proceso.
 *
 * Se guarda como lista de mapas planos con Moshi (sin reflexión: R8-safe) y
 * se escribe de forma atómica (`.tmp` + rename).
 */
class EmulatorInstallQueue(private val file: File) {

    private val lock = Any()
    private val adapter = Moshi.Builder().build().adapter<List<Map<String, Any?>>>(
        Types.newParameterizedType(
            List::class.java,
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        ),
    )

    private val _jobs = MutableStateFlow(load())
    val jobs: StateFlow<List<EmulatorJob>> = _jobs.asStateFlow()

    fun get(entryId: String): EmulatorJob? = _jobs.value.firstOrNull { it.entryId == entryId }

    /**
     * Añade emuladores a la cola. Los que ya están pendientes o descargados se
     * ignoran; uno fallido vuelve a la cola. Devuelve los ids añadidos.
     */
    fun enqueue(items: List<Pair<String, String>>, dualScreen: Boolean): List<String> = mutate { jobs ->
        val added = mutableListOf<String>()
        val result = jobs.toMutableList()
        for ((id, name) in items) {
            val existing = result.indexOfFirst { it.entryId == id }
            when {
                existing < 0 -> result += EmulatorJob(id, name, dualScreen)
                result[existing].state == EmulatorJobState.FAILED ->
                    result[existing] = EmulatorJob(id, name, dualScreen)
                else -> continue
            }
            added += id
        }
        result to added
    }

    /** Encola (o re-encola un FAILED) fijando el asset elegido por el usuario. */
    fun enqueueWithAsset(
        entryId: String,
        name: String,
        dualScreen: Boolean,
        assetUrl: String,
        assetName: String,
        assetVersion: String,
    ): Boolean = mutate { jobs ->
        val existing = jobs.indexOfFirst { it.entryId == entryId }
        val job = EmulatorJob(
            entryId = entryId,
            name = name,
            dualScreen = dualScreen,
            assetUrl = assetUrl,
            assetName = assetName,
            assetVersion = assetVersion,
        )
        val result = jobs.toMutableList()
        var added = false
        if (existing < 0 || jobs[existing].state == EmulatorJobState.FAILED) {
            if (existing >= 0) result[existing] = job else result += job
            added = true
        }
        result to added
    }

    /** Siguiente pendiente, en orden de llegada. */
    fun nextQueued(): EmulatorJob? = _jobs.value.firstOrNull { it.state == EmulatorJobState.QUEUED }

    fun hasQueued(): Boolean = nextQueued() != null

    fun markDownloading(entryId: String) = update(entryId) {
        it.copy(state = EmulatorJobState.DOWNLOADING, progress = 0f, error = null)
    }

    /** Progreso en memoria; no toca disco. */
    fun updateProgress(entryId: String, progress: Float) {
        synchronized(lock) {
            _jobs.value = _jobs.value.map {
                if (it.entryId == entryId && it.state == EmulatorJobState.DOWNLOADING) it.copy(progress = progress) else it
            }
        }
    }

    /** false si el trabajo se canceló mientras se descargaba (ya no está). */
    fun markReady(entryId: String, version: String, apkPath: String): Boolean = update(entryId) {
        it.copy(state = EmulatorJobState.READY, progress = 1f, version = version, apkPath = apkPath, error = null)
    }

    fun markFailed(entryId: String, message: String) = update(entryId) {
        it.copy(state = EmulatorJobState.FAILED, progress = 0f, error = message)
    }

    /** Falla todo lo pendiente con el mismo motivo (p. ej. límite de GitHub). */
    fun failAllQueued(message: String) = mutate { jobs ->
        jobs.map {
            if (it.state == EmulatorJobState.QUEUED) it.copy(state = EmulatorJobState.FAILED, error = message) else it
        } to Unit
    }

    /** Un APK listo cuyo fichero se perdió (caché limpiada) vuelve a la cola. */
    fun requeue(entryId: String) = update(entryId) {
        it.copy(state = EmulatorJobState.QUEUED, progress = 0f, apkPath = null, version = null, error = null)
    }

    /** Quita el trabajo y borra su APK descargado, si lo había. */
    fun remove(entryId: String): EmulatorJob? = mutate { jobs ->
        val job = jobs.firstOrNull { it.entryId == entryId }
        job?.apkPath?.let { File(it).delete() }
        jobs.filterNot { it.entryId == entryId } to job
    }

    /**
     * Un DOWNLOADING al arrancar el worker es una descarga interrumpida (el
     * proceso murió o el sistema paró el trabajo): vuelve a la cola.
     */
    fun recoverInterrupted() = mutate { jobs ->
        jobs.map {
            if (it.state == EmulatorJobState.DOWNLOADING) it.copy(state = EmulatorJobState.QUEUED, progress = 0f) else it
        } to Unit
    }

    private fun update(entryId: String, transform: (EmulatorJob) -> EmulatorJob): Boolean = mutate { jobs ->
        var found = false
        jobs.map { if (it.entryId == entryId) transform(it).also { found = true } else it } to found
    }

    private fun <T> mutate(block: (List<EmulatorJob>) -> Pair<List<EmulatorJob>, T>): T = synchronized(lock) {
        val (next, result) = block(_jobs.value)
        if (next != _jobs.value) {
            _jobs.value = next
            persist(next)
        }
        result
    }

    private fun persist(jobs: List<EmulatorJob>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(adapter.toJson(jobs.map { it.toMap() }))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load(): List<EmulatorJob> {
        if (!file.isFile) return emptyList()
        val raw = runCatching { adapter.fromJson(file.readText()) }.getOrNull().orEmpty()
        return raw.mapNotNull { it.toJob() }.distinctBy { it.entryId }
    }

    private fun EmulatorJob.toMap(): Map<String, Any?> = mapOf(
        "entryId" to entryId,
        "name" to name,
        "dualScreen" to dualScreen,
        "state" to state.name,
        "version" to version,
        "apkPath" to apkPath,
        "error" to error,
        "assetUrl" to assetUrl,
        "assetName" to assetName,
        "assetVersion" to assetVersion,
    )

    private fun Map<String, Any?>.toJob(): EmulatorJob? {
        val id = this["entryId"] as? String ?: return null
        val state = (this["state"] as? String)
            ?.let { s -> EmulatorJobState.entries.firstOrNull { it.name == s } }
            ?: EmulatorJobState.QUEUED
        return EmulatorJob(
            entryId = id,
            name = this["name"] as? String ?: id,
            dualScreen = this["dualScreen"] as? Boolean ?: false,
            state = state,
            progress = if (state == EmulatorJobState.READY) 1f else 0f,
            assetUrl = this["assetUrl"] as? String,
            assetName = this["assetName"] as? String,
            assetVersion = this["assetVersion"] as? String,
            version = this["version"] as? String,
            apkPath = this["apkPath"] as? String,
            error = this["error"] as? String,
        )
    }

    companion object {
        @Volatile
        private var instance: EmulatorInstallQueue? = null

        /** Una sola instancia por proceso: worker y pantalla ven el mismo flujo. */
        fun get(context: Context): EmulatorInstallQueue =
            instance ?: synchronized(this) {
                instance ?: EmulatorInstallQueue(
                    File(context.applicationContext.filesDir, "emulator_install_queue.json"),
                ).also { instance = it }
            }
    }
}
