package com.davidadrianrg.atril.core.sync.platform

import com.davidadrianrg.atril.core.sync.SaveNames
import com.davidadrianrg.atril.core.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Handler de saves para juegos nativos Android.
 *
 * A diferencia de los emuladores, cada juego Android guarda sus datos en una
 * ubicación distinta. Por tanto, este handler requiere que la ruta de saves se
 * configure por juego (savesPathOverride en DownloadedRomEntity) o por
 * plataforma (savesPathOverride en PlatformEntity).
 *
 * Comportamiento:
 * - findSaves: zipea todo el contenido de savesBasePath como un único asset.
 * - extractDownload: descomprime el zip en savesBasePath (reemplaza contenido).
 *
 * Rutas con root (teléfonos rooteados):
 * Si savesBasePath NO es accesible con la API File (p. ej.
 * `/data/data/com.juego/`) pero hay root disponible, el handler empaqueta la
 * carpeta con `su tar` (formato .tar, determinista) y la restaura igual.
 * Esto permite sincronizar las partidas de juegos Android directamente desde
 * su almacenamiento privado.
 *
 * Ruta base por defecto (genérica, el usuario debe configurarla por juego):
 * `/storage/emulated/0/Android/data`
 */
class AndroidSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String?,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val romBaseName = romFileName.substringBeforeLast('.')
        val saveDir = File(savesBasePath)

        if (saveDir.isDirectory) {
            // ── Ruta accesible sin root: zip determinista ────────────────
            val files = saveDir.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) return@withContext emptyList()

            val newestMtime = files.maxOf { it.lastModified() }
            val zipFile = File.createTempFile("android_save_${romBaseName}_", ".zip")
            zipFolderDeterministic(saveDir, zipFile)

            listOf(
                LocalSave(
                    romId = romId,
                    fileName = "${romBaseName}_android_save.zip",
                    file = zipFile,
                    lastModified = newestMtime,
                    sha1 = zipFile.sha1(),
                ),
            )
        } else if (RootShell.available && RootShell.run("test -d ${RootShell.sq(savesBasePath)}") != null) {
            // ── Ruta privilegiada (/data/...) con root: tar determinista ──
            findRootSaves(romId, romBaseName, savesBasePath)
        } else {
            emptyList()
        }
    }

    /**
     * Empaqueta una carpeta inaccesible para la app usando `su tar`.
     * El tar plano (sin gzip) es determinista: solo depende de contenidos,
     * mtimes y dueños de los ficheros, así el SHA-1 es estable entre corridas.
     */
    private fun findRootSaves(romId: Int, romBaseName: String, savesBasePath: String): List<LocalSave> {
        val dir = File(savesBasePath)
        val parent = dir.parent ?: return emptyList()
        val folderName = dir.name

        val count = RootShell.run("find ${RootShell.sq(savesBasePath)} -type f | wc -l")
            ?.trim()?.toIntOrNull() ?: return emptyList()
        if (count == 0) return emptyList()

        val newestMtime = RootShell.run(
            "find ${RootShell.sq(savesBasePath)} -type f -exec stat -c %Y {} + | sort -n | tail -1",
        )?.trim()?.toLongOrNull() ?: System.currentTimeMillis()

        val tarFile = File.createTempFile("android_root_${folderName}_", ".tar")
        if (!tarFile.delete()) return emptyList()
        val ok = RootShell.runToFile(
            "tar -cf ${RootShell.sq(tarFile.absolutePath)} -C ${RootShell.sq(parent)} ${RootShell.sq(folderName)}",
        )
        if (!ok || !tarFile.isFile || tarFile.length() == 0L) {
            tarFile.delete()
            return emptyList()
        }

        return listOf(
            LocalSave(
                romId = romId,
                fileName = "${romBaseName}_android_save.tar",
                file = tarFile,
                lastModified = newestMtime,
                sha1 = tarFile.sha1(),
            ),
        )
    }

    override suspend fun prepareForUpload(save: LocalSave): File = save.file

    override suspend fun savesFingerprint(
        romId: Int, romFileName: String, platformSlug: String,
        savesBasePath: String, romLocalPath: String?,
    ): String? = withContext(Dispatchers.IO) {
        val dir = File(savesBasePath)
        if (dir.isDirectory) {
            folderFingerprint(dir)
        } else if (RootShell.available &&
            RootShell.run("test -d ${RootShell.sq(savesBasePath)}") != null
        ) {
            // Huella vía root: tamaño+mtime de cada fichero, hasheada para
            // no almacenar listados enormes en prefs.
            val listing = RootShell.run(
                "find ${RootShell.sq(savesBasePath)} -type f -exec stat -c '%n %s %Y' {} + | sort",
            ) ?: return@withContext null
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(listing.toByteArray())
            digest.digest().joinToString("") { "%02x".format(it) }
        } else {
            null
        }
    }

    override suspend fun extractDownload(
        tempFile: File,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        targetFileName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (targetFileName.endsWith(".tar")) {
            extractRootTar(tempFile, savesBasePath)
        } else {
            extractZip(tempFile, savesBasePath)
        }
    }

    /**
     * Restaura un tar creado por [findRootSaves].
     *
     * El archivo se valida y se extrae como el usuario de la app en una carpeta
     * de staging propia; root solo se usa después, sobre el árbol ya validado,
     * para fijar el propietario y cambiar la carpeta destino. Un tar hostil se
     * rechaza antes de cualquier operación privilegiada y los saves existentes
     * quedan intactos.
     */
    private fun extractRootTar(tempFile: File, savesBasePath: String): Boolean {
        val dir = File(savesBasePath)
        val parent = dir.parent ?: return false
        val folderName = dir.name
        if (!SaveNames.isSafeSaveFileName(folderName)) return false

        val staging = createStagingDir("android_save_stage_") ?: return false
        try {
            if (!TarExtractor.extract(tempFile, staging)) return false
            val stagedTop = staging.listFiles()?.singleOrNull()
            if (stagedTop == null || !stagedTop.isDirectory || stagedTop.name != folderName) return false
            if (!RootShell.available) return false
            val owner = RootShell.ownerOf(dir.absolutePath)
                ?: RootShell.ownerOf(parent)
                ?: return false

            val targetExists = RootShell.run("test -e ${RootShell.sq(dir.absolutePath)}") != null
            val swap = File(parent, "$folderName.swap").absolutePath
            val cmd = "chown -R $owner ${RootShell.sq(stagedTop.absolutePath)}" +
                (if (targetExists) " && mv ${RootShell.sq(dir.absolutePath)} ${RootShell.sq(swap)}" else "") +
                " && mv ${RootShell.sq(stagedTop.absolutePath)} ${RootShell.sq(dir.absolutePath)}" +
                (if (targetExists) " && rm -rf ${RootShell.sq(swap)}" else "")
            return RootShell.runToFile(cmd)
        } catch (_: Exception) {
            return false
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Crea una carpeta temporal accesible que después usa [TarExtractor]. */
    private fun createStagingDir(prefix: String): File? = try {
        val staging = File.createTempFile(prefix, ".dir")
        if (staging.delete() && staging.mkdirs()) staging else null
    } catch (_: Exception) {
        null
    }

    private fun extractZip(tempFile: File, savesBasePath: String): Boolean {
        val saveDir = File(savesBasePath)
        // Limpiar contenido existente antes de restaurar
        if (saveDir.isDirectory) {
            saveDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        saveDir.mkdirs()

        return try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                while (entry != null) {
                    // Eliminar el directorio raíz del zip (nombre de la carpeta original)
                    val entryName = entry.name.substringAfter('/', entry.name)
                    if (entryName.isEmpty()) {
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }
                    val outFile = SaveNames.resolveArchiveEntry(saveDir, entryName)
                    if (outFile == null) {
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> zipIn.copyTo(out) }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val DEFAULT_SAVES_PATH = "/storage/emulated/0/Android/data"
    }
}
