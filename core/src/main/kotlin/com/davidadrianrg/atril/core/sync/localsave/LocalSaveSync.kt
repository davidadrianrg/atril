package com.davidadrianrg.atril.core.sync.localsave

import com.davidadrianrg.atril.core.sync.platform.SaveHandlerRegistry
import com.davidadrianrg.atril.core.sync.webdav.WebDavClient
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Sincronización de saves de JUEGOS LOCALES (no gestionados por RomM) contra
 * un servidor WebDAV configurable.
 *
 * Estructura remota (por juego, igual de legible que el resto de la app):
 * ```
 * <raíz WebDAV>/<plataforma>/<juego>/<save.zip|save.srm|…>
 * ```
 * p. ej. `…/AtrilSaves/gamecube/Luigi's Mansion/luigi.sda`.
 *
 * Reglas (v1, sin manifiesto):
 * - No está en remoto → se sube.
 * - Está en ambos → manda el más nuevo por mtime; el perdedor se respalda
 *   antes de sobrescribirse (hook [Provider.backup]).
 * - Solo en remoto → se descarga (restauración en un dispositivo nuevo).
 * Los artefactos se preparan con el mismo handler de plataforma que RomM
 * (Dolphin/PS3 → zip; RetroArch/Eden → fichero plano), así un save de carpeta
 * viaja como un único fichero.
 */
class LocalSaveSync(
    private val webdav: WebDavClient,
    private val provider: Provider,
) {

    /** Lo que la app necesita dar al orquestador (desacoplado de Android). */
    interface Provider {
        /** Juegos locales con datos de save. Vacío si no hay ninguno sincronizable. */
        fun games(): List<GameSnapshot>

        /** Copia de seguridad del fichero local antes de pisarlo. */
        fun backup(gameId: String, fileName: String, file: File)

        /** Aviso por juego (el llamador lo acumula en el mensaje de resultado). */
        fun notify(message: String)
    }

    data class GameSnapshot(
        val id: String,
        val title: String,
        val platformSlug: String,
        val romFileName: String,
        val emulatorId: String?,
        val savesBasePath: String,
        val romId: Int = 0,
        val romLocalPath: String? = null,
    )

    data class Result(
        val uploaded: Int,
        val downloaded: Int,
        val conflicts: Int,
        val skipped: Int,
        val messages: List<String>,
    ) {
        val summary: String get() = "$uploaded↑ $downloaded↓ $conflicts⚔"
    }

    suspend fun syncAll(): Result {
        var uploaded = 0
        var downloaded = 0
        var conflicts = 0
        var skipped = 0
        val messages = mutableListOf<String>()

        for (game in provider.games()) {
            try {
                val handler = SaveHandlerRegistry.getHandler(game.platformSlug, game.emulatorId)
                val saves = handler.findSaves(
                    romId = game.romId,
                    romFileName = game.romFileName,
                    platformSlug = game.platformSlug,
                    savesBasePath = game.savesBasePath,
                    romLocalPath = game.romLocalPath,
                )
                if (saves.isEmpty()) continue

                val dir = remoteDir(game.platformSlug, game.title)
                val remote = webdav.listFiles(dir).getOrNull()
                if (remote == null) {
                    messages += "${game.title}: no se pudo listar el servidor WebDAV"
                    continue
                }
                val remoteByName = remote.associateBy { it.name }

                for (save in saves) {
                    val artifact = handler.prepareForUpload(save)
                    val remoteEntry = remoteByName[save.fileName]
                    when {
                        remoteEntry == null -> {
                            webdav.uploadFile("$dir/${save.fileName}", artifact.readBytes()).getOrThrow()
                            uploaded++
                        }
                        remoteNewer(remoteEntry, save.lastModified) -> {
                            // El remoto está más nuevo: descargar y sustituir el local
                            // (copia de seguridad antes de pisar).
                            val bytes = webdav.downloadFile("$dir/${save.fileName}").getOrThrow()
                            provider.backup(game.id, save.fileName, save.file)
                            applyDownloaded(handler, bytes, game, save.fileName)
                            downloaded++
                            provider.notify(
                                "${game.title}: restaurado ${save.fileName} (el servidor tenía una versión más nueva)",
                            )
                        }
                        localNewer(remoteEntry, save.lastModified) -> {
                            webdav.uploadFile("$dir/${save.fileName}", artifact.readBytes()).getOrThrow()
                            uploaded++
                            conflicts++
                            provider.notify(
                                "${game.title}: subido ${save.fileName} (la copia local era más nueva que la del servidor)",
                            )
                        }
                        else -> skipped++
                    }
                    if (artifact.path != save.file.path) artifact.delete()
                }

                // Solo en remoto (otro dispositivo los subió): restaurarlos.
                val localNames = saves.map { it.fileName }.toSet()
                for (entry in remoteByName.values) {
                    if (entry.name in localNames) continue
                    val bytes = webdav.downloadFile("$dir/${entry.name}").getOrThrow()
                    applyDownloaded(handler, bytes, game, entry.name)
                    downloaded++
                    provider.notify("${game.title}: restaurado ${entry.name} (solo existía en el servidor)")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                messages += "${game.title}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return Result(uploaded, downloaded, conflicts, skipped, messages)
    }

    /** `windows` y similares sin handler propio quedan fuera (no hay saves descubribles). */
    private suspend fun applyDownloaded(
        handler: com.davidadrianrg.atril.core.sync.platform.SaveHandler,
        bytes: ByteArray,
        game: GameSnapshot,
        fileName: String,
    ) {
        val temp = File.createTempFile("atril-webdav-save-", ".tmp")
        try {
            temp.writeBytes(bytes)
            val ok = handler.extractDownload(
                tempFile = temp,
                romFileName = game.romFileName,
                platformSlug = game.platformSlug,
                savesBasePath = game.savesBasePath,
                targetFileName = com.davidadrianrg.atril.core.sync.SaveNames.stripVersionTag(fileName),
            )
            check(ok) { "extract $fileName" }
        } finally {
            temp.delete()
        }
    }

    private fun remoteNewer(entry: com.davidadrianrg.atril.core.sync.webdav.WebDavEntry, localMtime: Long): Boolean {
        val remoteMs = entry.httpDateMillis() ?: return false
        return remoteMs > localMtime + CLOCK_TOLERANCE_MS
    }

    private fun localNewer(entry: com.davidadrianrg.atril.core.sync.webdav.WebDavEntry, localMtime: Long): Boolean {
        val remoteMs = entry.httpDateMillis() ?: return true
        return localMtime > remoteMs + CLOCK_TOLERANCE_MS
    }

    companion object {
        /** Relojes no sincronizados: 3 s de tolerancia evita el ping-pong. */
        private const val CLOCK_TOLERANCE_MS = 3_000L

        /** Carpeta remota del juego: `<plataforma>/<título saneado>`. */
        fun remoteDir(platformSlug: String, title: String): String =
            "${sanitizeSegment(platformSlug)}/${sanitizeSegment(title)}"

        fun sanitizeSegment(raw: String): String = raw
            .replace(Regex("[/\\\\:*?\"<>|]"), "-")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')
            .ifBlank { "juego" }

        private fun com.davidadrianrg.atril.core.sync.webdav.WebDavEntry.httpDateMillis(): Long? {
            val raw = lastModifiedHttp ?: return null
            return try {
                ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Algunos servidores usan formato RFC 850: reintento relajado.
                try {
                    ZonedDateTime.parse(
                        raw,
                        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
                    ).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
