package com.davidadrianrg.atril.data.repository

import android.net.Uri
import com.davidadrianrg.atril.core.download.PathMapper
import com.davidadrianrg.atril.core.download.RomFileLocator
import com.davidadrianrg.atril.core.localgames.LocalGamePaths
import com.davidadrianrg.atril.data.storage.RomFileImporter
import com.davidadrianrg.atril.data.storage.RomImportResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Resultado de localizar un fichero desde la UI. */
sealed interface LocateOutcome {
    /** El fichero quedó asociado al juego. */
    data object Linked : LocateOutcome

    /** Ya existe un fichero con ese nombre en la carpeta de la plataforma. */
    data class AlreadyExists(val fileName: String) : LocateOutcome

    /** El documento elegido no tiene un nombre utilizable. */
    data object InvalidFile : LocateOutcome

    /** No se pudo copiar (sin raíz configurada, E/S, stream nulo…). */
    data object CopyFailed : LocateOutcome
}

/**
 * Orquesta "Localizar archivo": valida la raíz configurada, evita copiar si el
 * documento ya vive bajo una raíz de ROMs y persiste la ruta en todos los ids
 * del grupo (multidisco comparte la misma ruta, como el marcado manual).
 */
class RomFileImportService(
    private val importer: RomFileImporter,
    private val romRepository: RomRepository,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Importa o asocia [uri] al juego [romId] y a su grupo.
     *
     * @param overwrite `true` solo cuando el usuario confirmó reemplazar.
     * @return [LocateOutcome]; nunca lanza por fallos de fichero o de stream.
     */
    suspend fun locate(
        romId: Int,
        groupRomIds: List<Int>,
        platformSlug: String,
        fileName: String,
        uri: Uri,
        overwrite: Boolean,
    ): LocateOutcome {
        val config = try {
            settingsRepository.settings.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return LocateOutcome.CopyFailed
        }
        val primaryRoot = config.romsRootPath.trim()
        if (primaryRoot.isBlank() || !RomFileLocator.isSafeFileName(platformSlug)) {
            return LocateOutcome.CopyFailed
        }

        val roots = buildList {
            add(primaryRoot)
            if (config.dualRomsPathsEnabled) add(config.secondaryRomsPath.trim())
        }.filter { it.isNotBlank() }

        // Si el documento ya es accesible por ruta y cae bajo una raíz, no se
        // duplica el fichero: se asocia esa misma ruta.
        val pickedPath = LocalGamePaths.fromPickedUri(uri.toString())
        val existing = pickedPath?.let { File(it) }?.takeIf { it.isFile && isUnderAnyRoot(it, roots) }
        if (existing != null) {
            attach(romId, groupRomIds, existing.absolutePath)
            return LocateOutcome.Linked
        }

        if (!PathMapper.ensurePlatformDir(primaryRoot, platformSlug)) {
            return LocateOutcome.CopyFailed
        }
        val targetDir = PathMapper.getPlatformDir(primaryRoot, platformSlug)
        return when (val result = importer.import(uri, targetDir, fileName, overwrite)) {
            is RomImportResult.Copied -> {
                attach(romId, groupRomIds, result.path)
                LocateOutcome.Linked
            }
            is RomImportResult.AlreadyExists ->
                LocateOutcome.AlreadyExists(File(result.intendedPath).name)
            RomImportResult.InvalidName -> LocateOutcome.InvalidFile
            is RomImportResult.Failed -> LocateOutcome.CopyFailed
        }
    }

    private suspend fun attach(romId: Int, groupRomIds: List<Int>, path: String) {
        (listOf(romId) + groupRomIds).distinct().filter { it > 0 }.forEach { id ->
            romRepository.updateLocalPath(id, path)
        }
    }

    private fun isUnderAnyRoot(file: File, roots: List<String>): Boolean {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return roots.any { root ->
            val canonicalRoot = runCatching { File(root).canonicalPath }.getOrNull() ?: return@any false
            path == canonicalRoot || path.startsWith(canonicalRoot + File.separator)
        }
    }
}
