package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.download.PartialDownload
import com.davidadrianrg.atril.core.download.PathMapper
import com.davidadrianrg.atril.core.download.ZipStreamExtractor
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.core.remote.RomMApiService
import com.davidadrianrg.atril.core.remote.dto.RomDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Motor de descargas de la app desktop. Replica la lógica del DownloadWorker
 * de Android: fichero único → disco directo con reanudación vía Range;
 * multi-fichero (RomM zipea al vuelo con mod_zip, sin Content-Length) →
 * extracción en streaming con contador de bytes.
 *
 * Cancelación: los streams comprueban el Job de la corrutina llamante en cada
 * reporte de progreso; al cancelar se lanza CancellationException y se cierra
 * la conexión. Si la cancelación es explícita del usuario ([discardPartialOnCancel])
 * se borra el `.part`; en otro caso queda en disco para reanudar con Range. En
 * ningún caso un ROM truncado lleva su nombre final ([PartialDownload]).
 */
class DownloadEngine(
    private val api: RomMApiService,
    private val romsRoot: File,
    private val cacheDir: File,
    private val library: DesktopLibrary? = null,
    /** ¿La cancelación en curso la pidió el usuario? Entonces se borra el parcial. */
    private val discardPartialOnCancel: () -> Boolean = { false },
) {

    /** Progreso notificado durante la descarga. total=-1 → indeterminado (zip). */
    data class Progress(val bytesRead: Long, val total: Long, val speedBps: Long)

    data class Result(val message: String, val failed: Boolean = false)

    suspend fun download(
        rom: RomDto,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val fileName = rom.fileName
        // Carpeta por plataforma (override del usuario) o slug por defecto
        val folderOverride = library?.platform(rom.platformSlug ?: "")?.romsFolderOverride
        val targetDir = if (!folderOverride.isNullOrBlank()) {
            File(romsRoot, folderOverride).also { it.mkdirs() }
        } else {
            PathMapper.getPlatformDir(romsRoot.absolutePath, rom.platformSlug ?: "unknown").also { it.mkdirs() }
        }

        // Fichero objetivo y parcial previo (para reanudar) se calculan ANTES
        // de la petición: el Range depende de lo que ya hay en disco. Los
        // bytes viven en `<fichero>.part` hasta completar y verificar (mismo
        // helper que Android), así ES-DE nunca ve un ROM truncado.
        val target = File(targetDir, fileName)
        val resumePoint = PartialDownload.resumePoint(target)
        val existing = resumePoint.bytes
        val range = if (existing > 0L) "bytes=$existing-" else null

        val response = api.downloadRom(rom.id, fileName, range)
        if (!response.isSuccessful) {
            when (response.code()) {
                416 -> {
                    // Range insatisfiable: el fichero ya está completo en disco.
                    response.raw().close()
                    if (existing <= 0L) {
                        return@withContext Result(tr("download.http_error", 416), failed = true)
                    }
                    verifyHash(rom, resumePoint.file)?.let { error ->
                        resumePoint.file.delete()
                        return@withContext Result(error, failed = true)
                    }
                    PartialDownload.commit(resumePoint.file, target)
                    registerInLibrary(rom, target)
                    return@withContext Result(tr("download.done_already_complete", rom.name))
                }
                else -> {
                    response.body()?.close()
                    response.raw().close()
                    return@withContext Result(tr("download.http_error", response.code()), failed = true)
                }
            }
        }
        val body = response.body() ?: return@withContext Result(tr("download.empty_response"))

        val job = coroutineContext[Job]

        // ¿Zip en streaming? (multi-fichero: sin Content-Length)
        val isZipStream = body.contentLength() == -1L
        if (isZipStream) {
            val created = extractZipStream(body, targetDir, job, onProgress)
            // Un parcial de fichero único ya no sirve: el servidor lo sirve como zip.
            PartialDownload.discard(target)
            registerInLibrary(rom, targetDir)
            return@withContext Result(tr("download.done_extracted", rom.name, created.size))
        }

        // Fichero único — con reanudación: si hay un parcial de una descarga
        // previa (fallida o cancelada) se pide Range y se concatena (206);
        // si el servidor ignora el Range (200) se empieza de cero en `.part`
        // y el ROM anterior, si lo hay, sigue intacto hasta el renombrado.
        val resumed = existing > 0L && response.code() == 206
        val part = PartialDownload.prepareAppend(resumePoint, target, resume = resumed)
        // En 206 Content-Length es lo que QUEDA, no el total.
        val total = if (resumed) existing + body.contentLength() else body.contentLength()
        val baseBytes = if (resumed) existing else 0L
        val counting = CountingInputStream(body.byteStream(), baseBytes) { read, speed ->
            if (job?.isActive == false) throw CancellationException("Descarga cancelada")
            onProgress(Progress(read, total, speed))
        }
        try {
            FileOutputStream(part, resumed).use { out ->
                counting.copyTo(out)
                out.fd.sync()
            }
        } catch (e: CancellationException) {
            if (discardPartialOnCancel()) PartialDownload.discard(target)
            throw e
        }

        // Verificación de integridad contra el hash del servidor (mismo
        // criterio que Android: algoritmo por longitud hex; desconocida = skip).
        val hashError = verifyHash(rom, part)
        if (hashError != null) {
            part.delete() // corrupto: no dejarlo en la biblioteca
            return@withContext Result(hashError, failed = true)
        }

        // Completo y verificado: ahora sí recibe su nombre definitivo.
        PartialDownload.commit(part, target)
        registerInLibrary(rom, target)
        return@withContext Result(tr("download.done_path", rom.name, target.absolutePath))
    }

    /**
     * Compara el hash del fichero descargado con el que reporta el servidor
     * (rom.files[0].hash). Devuelve un mensaje de error si NO coincide, o
     * null si está OK o no hay hash verificable.
     */
    private fun verifyHash(rom: RomDto, target: File): String? {
        val expected = rom.files.firstOrNull()?.hash?.takeIf { it.isNotBlank() } ?: return null
        val algo = when (expected.length) {
            32 -> "MD5"
            40 -> "SHA-1"
            64 -> "SHA-256"
            else -> return null // algoritmo desconocido: no verificar, no fallar
        }
        val actual = runCatching {
            java.security.MessageDigest.getInstance(algo).let { md ->
                java.security.DigestInputStream(target.inputStream(), md).use { dis ->
                    val buf = ByteArray(64 * 1024)
                    while (dis.read(buf) >= 0) { /* consume */ }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull() ?: return null
        return if (!actual.equals(expected, ignoreCase = true)) {
            tr("download.hash_mismatch", expected.take(8), actual.take(8))
        } else {
            null
        }
    }

    private fun registerInLibrary(rom: RomDto, localPath: File) {
        // Android/PC nativos requieren ruta de saves manual juego a juego:
        // se excluyen del sync por defecto (el usuario puede activarlo).
        val manualOnly = setOf("android", "android-games", "pc", "windows", "dos", "msdos")
        library?.upsertRom(
            DesktopLibrary.RomEntry(
                romId = rom.id,
                name = rom.name,
                fileName = rom.fileName,
                platformSlug = rom.platformSlug ?: "unknown",
                localPath = localPath.absolutePath,
                excludedFromSync = (rom.platformSlug ?: "").lowercase() in manualOnly,
            ),
        )
    }

    /**
     * Extrae el zip de un stream mod_zip, manteniendo el contador de progreso y
     * la comprobación del Job entre este motor y el helper de :core.
     */
    private fun extractZipStream(
        body: okhttp3.ResponseBody,
        targetDir: File,
        job: Job?,
        onProgress: (Progress) -> Unit,
    ): List<File> {
        val counting = CountingInputStream(body.byteStream(), 0L) { read, speed ->
            if (job?.isActive == false) throw CancellationException("Descarga cancelada")
            onProgress(Progress(read, -1L, speed))
        }
        return ZipStreamExtractor.extract(counting, targetDir)
    }
}

/**
 * InputStream que cuenta bytes leídos y notifica progreso con velocidad EMA
 * (misma suavización que el DownloadWorker de Android para que el número no
 * oscile con las ráfagas de TCP/writeback de disco).
 */
private class CountingInputStream(
    private val delegate: java.io.InputStream,
    /** Bytes ya en disco (reanudación): el contador arranca desde aquí. */
    private val baseBytes: Long,
    private val onReport: (bytesRead: Long, speed: Long) -> Unit,
) : java.io.FilterInputStream(delegate) {

    private var count = baseBytes
    private var emaBps = 0.0
    private var lastReport = baseBytes
    private var lastReportAt = System.currentTimeMillis()

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) tally(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) tally(n.toLong())
        return n
    }

    private fun tally(n: Long) {
        count += n
        val now = System.currentTimeMillis()
        val elapsed = now - lastReportAt
        if (elapsed >= 500) {
            val windowBps = (count - lastReport) * 1000.0 / elapsed
            // EMA con alfa de la ventana: ~2s de memoria
            emaBps = if (emaBps == 0.0) windowBps else emaBps * 0.75 + windowBps * 0.25
            lastReport = count
            lastReportAt = now
            onReport(count, emaBps.toLong())
        }
    }
}
