package com.davidadrianrg.atril.core.sync.platform

import com.davidadrianrg.atril.core.sync.SafeExtraction
import com.davidadrianrg.atril.core.sync.RootCommandNames
import com.davidadrianrg.atril.core.sync.SaveNames
import com.davidadrianrg.atril.core.util.RomHeaderIdReader
import com.davidadrianrg.atril.core.util.RootShell
import com.davidadrianrg.atril.core.util.SafeDelete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Handler de saves para PS2 (AetherSX2 / NetherSX2) en modo Folder Memory Card.
 *
 * Estructura esperada:
 * ```
 * {basePath}/memcards/{cardName}.ps2/{BAserial}/
 * ```
 *
 * El emulador debe estar configurado con "Folder Memory Card" activo.
 * Cada juego tiene su propia subcarpeta identificada por serial del disco
 * con prefijo "BA" (p.ej. `BASLUS-21050`).
 *
 * Para sync se zipea la carpeta del serial y se sube/baja como un solo asset.
 *
 * NOTA: Si hay multiples memory cards (.ps2 dirs), se busca en todas.
 * El serial se extrae del nombre del ROM.
 */
class Ps2SaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String?,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        val memcardsDir = File(savesBasePath)
        // Android 13+ bloquea Android/data/<pkg> a apps terceras aunque tengan
        // "todos los archivos": listFiles() devuelve null. Con root disponible
        // se accede vía su (tar determinista), igual que AndroidSaveHandler.
        if (!memcardsDir.isDirectory) {
            return@withContext if (RootShell.available) {
                findRootSaves(romId, romFileName, platformSlug, savesBasePath, romLocalPath)
            } else {
                results
            }
        }

        // Serial: primero desde el header del ISO, luego desde el nombre.
        val serial = romLocalPath?.let { RomHeaderIdReader.readGameId(File(it), platformSlug) }
            ?: extractSerialFromFileName(romFileName)
        if (serial == null) return@withContext results
        val baSerial = toBaFolderName(serial)

        // Buscar en todas las memory cards (.ps2 dirs)
        val cardDirs = memcardsDir.listFiles()?.filter {
            it.isDirectory && it.name.endsWith(".ps2", ignoreCase = true)
        } ?: return@withContext results

        for (cardDir in cardDirs) {
            val saveFolder = cardDir.listFiles()?.firstOrNull { dir ->
                dir.isDirectory && matchesFolderName(dir.name, serial)
            } ?: continue

            val mtime = folderLastModified(saveFolder)
            val zipFile = File.createTempFile("ps2_save_${baSerial}_", ".zip")
            zipFolderDeterministic(saveFolder, zipFile)

            results.add(
                LocalSave(
                    romId = romId,
                    fileName = "${baSerial}_save.zip",
                    file = zipFile,
                    lastModified = mtime,
                    sha1 = zipFile.sha1(),
                ),
            )
            break // Usar la primera memory card que tenga el save
        }

        results
    }

    override suspend fun prepareForUpload(save: LocalSave): File = save.file

    override suspend fun savesFingerprint(
        romId: Int, romFileName: String, platformSlug: String,
        savesBasePath: String, romLocalPath: String?,
    ): String? = withContext(Dispatchers.IO) {
        val serial = romLocalPath?.let { RomHeaderIdReader.readGameId(File(it), platformSlug) }
            ?: extractSerialFromFileName(romFileName) ?: return@withContext null
        val cardDirs = File(savesBasePath).listFiles()?.filter {
            it.isDirectory && it.name.endsWith(".ps2", ignoreCase = true)
        }
        if (cardDirs == null) {
            // Ruta inaccesible (Android 13+): fingerprint vía root si existe.
            if (!RootShell.available) return@withContext null
            for (cardDirPath in rootListCardDirs(savesBasePath)) {
                val saveFolderPath = rootFindSaveFolder(cardDirPath, serial) ?: continue
                return@withContext rootFolderFingerprint(saveFolderPath)
            }
            return@withContext null
        }
        cardDirs.forEach { card ->
            val saveFolder = card.listFiles()?.firstOrNull {
                it.isDirectory && matchesFolderName(it.name, serial)
            } ?: return@forEach
            return@withContext folderFingerprint(saveFolder)
        }
        null
    }

    override suspend fun extractDownload(
        tempFile: File,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        targetFileName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        // Tar creado por findRootSaves (ruta inaccesible + root): extraer con su
        if (targetFileName.endsWith(".tar")) {
            return@withContext extractRootTar(tempFile, savesBasePath)
        }

        val memcardsDir = File(savesBasePath)
        // Si la ruta no es accesible con la API File (Android 13+ bloquea
        // Android/data/<pkg>), ni mkdirs ni la extracción zip funcionarían —
        // con root se extrae el zip a un temp accesible y se copia con su.
        if (!memcardsDir.isDirectory && !memcardsDir.mkdirs()) {
            return@withContext if (RootShell.available) {
                extractZipViaRoot(tempFile, romFileName, savesBasePath)
            } else {
                false
            }
        }

        val serial = extractSerialFromFileName(romFileName) ?: return@withContext false
        val baSerial = toBaFolderName(serial)

        // Encontrar o crear una memory card
        val cardDirs = memcardsDir.listFiles()?.filter {
            it.isDirectory && it.name.endsWith(".ps2", ignoreCase = true)
        } ?: emptyList()

        val targetCard = if (cardDirs.isEmpty()) {
            File(memcardsDir, "Shared.ps2").also { it.mkdirs() }
        } else {
            cardDirs[0]
        }

        // Borrar carpeta existente del serial (solo su contenido): la card se
        // conserva para no perder los saves de otros juegos.
        targetCard.listFiles()?.filter { dir ->
            dir.isDirectory && matchesFolderName(dir.name, serial)
        }?.forEach { SafeDelete.clearDirectoryContents(targetCard, it) }

        // Extraer zip dentro de la memory card
        try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    SafeExtraction.extractEntry(
                        base = targetCard,
                        entryPath = entry.name,
                        isDirectory = entry.isDirectory,
                        input = if (entry.isDirectory) null else zipIn,
                    )
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Intenta extraer un serial PS2 del nombre del ROM.
     * Formatos típicos: "SLUS-21050 - Game.iso", "[SLUS_21050] Game.iso"
     */
    private fun extractSerialFromFileName(fileName: String): String? {
        val patterns = listOf(
            Regex("""([A-Z]{4}[-_]\d{5})"""),
            Regex("""\[?([A-Z]{4}\d{5})\]?"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(fileName)
            if (match != null) {
                return match.groupValues[1].replace("_", "-")
            }
        }
        return null
    }

    /**
     * Convierte un serial PS2 (SLUS-21050) al formato carpeta BA (BASLUS-21050).
     */
    private fun toBaFolderName(serial: String): String {
        val cleaned = serial.replace("-", "").replace("_", "")
        val match = Regex("^([A-Za-z]{4})(\\d+)$").find(cleaned)
        return if (match != null) {
            "BA${match.groupValues[1].uppercase()}-${match.groupValues[2]}"
        } else {
            "BA$cleaned"
        }
    }

    private fun matchesFolderName(folderName: String, serial: String): Boolean {
        val stripped = serial.replace("-", "")
        val baSerial = if (stripped.startsWith("BA", ignoreCase = true)) stripped else "BA$stripped"
        val folderStripped = folderName.replace("-", "")
        return folderStripped.startsWith(baSerial, ignoreCase = true)
    }

    private fun folderLastModified(dir: File): Long {
        var newest = dir.lastModified()
        dir.walkTopDown().forEach { file ->
            if (file.lastModified() > newest) newest = file.lastModified()
        }
        return newest
    }

    // ─────────────────────────────────────────────────────────────────────
    // Acceso root: Android 13+ impide leer Android/data/<pkg> con la API File
    // incluso con MANAGE_EXTERNAL_STORAGE. Con su se lista el árbol con find,
    // se empaqueta con tar y se restaura igual (mismo patrón que
    // AndroidSaveHandler para /data/data/<pkg>).
    // ─────────────────────────────────────────────────────────────────────

    /** Lista las carpetas de memory card (.ps2) de una ruta inaccesible. */
    private fun rootListCardDirs(savesBasePath: String): List<String> {
        val listing = RootShell.run(
            "find ${RootShell.sq(savesBasePath)} -maxdepth 1 -type d -name '*.ps2' | sort",
        ) ?: return emptyList()
        return listing.lineSequence().filter { it.isNotBlank() }.toList()
    }

    /** Busca la carpeta de save del serial dentro de un card dir (vía root). */
    private fun rootFindSaveFolder(cardDirPath: String, serial: String): String? {
        val listing = RootShell.run(
            "find ${RootShell.sq(cardDirPath)} -maxdepth 1 -type d | sort",
        ) ?: return null
        return listing.lineSequence()
            .map { it.trim() }
            .firstOrNull { matchesFolderName(File(it).name, serial) }
    }

    /**
     * findSaves por root: empaqueta la carpeta BASLUS-xxxxx del serial como
     * tar determinista (uid/gid/mtime estables bajo su).
     */
    private fun findRootSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String?,
    ): List<LocalSave> {
        val serial = romLocalPath?.let { RomHeaderIdReader.readGameId(File(it), platformSlug) }
            ?: extractSerialFromFileName(romFileName)
        if (serial == null) return emptyList()
        val baSerial = toBaFolderName(serial)

        val base = File(savesBasePath)
        for (cardDirPath in rootListCardDirs(savesBasePath)) {
            val saveFolderPath = rootFindSaveFolder(cardDirPath, serial) ?: continue

            // Card y carpeta del serial se resuelven con la regla estricta y la
            // contención canónica antes de interpolarse en el comando tar.
            val card = RootCommandNames.resolveUnder(base, File(cardDirPath).name) ?: continue
            val saveFolder = RootCommandNames.resolveUnder(card, File(saveFolderPath).name) ?: continue
            val parent = card.parentFile ?: continue

            // mtime más reciente dentro de la carpeta (est -c %Y)
            val newestMtime = RootShell.run(
                "find ${RootShell.sq(saveFolderPath)} -type f -exec stat -c %Y {} + | sort -n | tail -1",
            )?.trim()?.toLongOrNull() ?: System.currentTimeMillis()

            // tar de la carpeta del serial dentro de su card dir. La ruta se
            // cita siempre con RootShell.sq y tarCreateCommand exige que los
            // dos componentes (card/serial) sean nombres seguros.
            val tarFile = File.createTempFile("ps2_root_${baSerial}_", ".tar")
            if (!tarFile.delete()) continue
            val command = RootCommandNames.tarCreateCommand(tarFile, parent, saveFolder) ?: continue
            val ok = RootShell.runToFile(command)
            if (!ok || !tarFile.isFile || tarFile.length() == 0L) {
                tarFile.delete()
                continue
            }

            return listOf(
                LocalSave(
                    romId = romId,
                    fileName = "${baSerial}_save.tar",
                    file = tarFile,
                    lastModified = newestMtime,
                    sha1 = tarFile.sha1(),
                ),
            )
        }
        return emptyList()
    }

    /** Fingerprint de carpeta inaccesible vía root: stat de cada fichero. */
    private fun rootFolderFingerprint(saveFolderPath: String): String? {
        val listing = RootShell.run(
            "find ${RootShell.sq(saveFolderPath)} -type f -exec stat -c '%n %s %Y' {} + | sort",
        ) ?: return null
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(listing.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Restaura un tar (creado por [findRootSaves]) bajo la raíz de memcards.
     *
     * La entrada superior del tar es la carpeta de la memory card (p. ej.
     * `Shared.ps2`), no la carpeta del serial: por eso el paso final copia el
     * contenido del staging (la card) bajo `savesBasePath` en modo merge, para
     * no borrar los saves de otros juegos de la misma card.
     *
     * Se valida y extrae primero como el usuario de la app; root solo interviene
     * sobre el árbol ya validado.
     */
    private fun extractRootTar(tempFile: File, savesBasePath: String): Boolean {
        val base = File(savesBasePath)
        val staging = createStagingDir("ps2_save_stage_") ?: return false
        try {
            if (!TarExtractor.extract(tempFile, staging)) return false
            val stagedTop = staging.listFiles()?.singleOrNull() ?: return false
            if (!stagedTop.isDirectory || !SaveNames.isSafeSaveFileName(stagedTop.name)) return false
            if (!RootShell.available) return false

            val targetDir = File(base, stagedTop.name)
            val owner = RootShell.ownerOf(targetDir.absolutePath)
                ?: RootShell.ownerOf(base.absolutePath)
                ?: return false
            // Copiar el contenido del staging (la card entera) dentro de la raíz
            // de memcards: `cp -a <staging>/. <base>/` recrea/mergea
            // base/<card>/ conservando los saves de otros juegos.
            val cmd = "mkdir -p ${RootShell.sq(base.absolutePath)}" +
                " && chown -R $owner ${RootShell.sq(stagedTop.absolutePath)}" +
                " && cp -a ${RootShell.sq(staging.absolutePath)}/. ${RootShell.sq(base.absolutePath)}/"
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

    /**
     * Restauración de un zip (creado sin root) hacia una memory card
     * inaccesible: extrae a una carpeta temporal accesible y la mueve dentro
     * del card dir con su. La estructura original (cardName.ps2/BASLUS-xxxx)
     * se reconstruye bajo savesBasePath.
     */
    private fun extractZipViaRoot(tempFile: File, romFileName: String, savesBasePath: String): Boolean {
        val serial = extractSerialFromFileName(romFileName) ?: return false
        val baSerial = toBaFolderName(serial)

        // 1. Extraer el zip a un temp accesible
        val staging = File.createTempFile("ps2_stage_${baSerial}_", ".dir")
        if (!staging.delete()) return false
        if (!staging.mkdirs()) return false
        try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    SafeExtraction.extractEntry(
                        base = staging,
                        entryPath = entry.name,
                        isDirectory = entry.isDirectory,
                        input = if (entry.isDirectory) null else zipIn,
                    )
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } catch (_: Exception) {
            staging.deleteRecursively()
            return false
        }

        // 2. Copiar la carpeta del serial dentro del primer card dir existente
        //    (o crear Shared.ps2) bajo savesBasePath, todo con su. El nombre de
        //    la card viene de la salida de find: se valida antes de mkdir/cp.
        val base = File(savesBasePath)
        val existingCards = rootListCardDirs(savesBasePath)
        val cardName = existingCards.firstOrNull()?.let { File(it).name } ?: "Shared.ps2"
        val targetCard = RootCommandNames.resolveUnder(base, cardName)
        if (targetCard == null) {
            staging.deleteRecursively()
            return false
        }
        val targetCardPath = targetCard.path
        val ok = RootShell.runToFile(
            "mkdir -p ${RootShell.sq(targetCardPath)} && " +
                "cp -a ${RootShell.sq(staging.absolutePath)}/. ${RootShell.sq(targetCardPath)}/",
        )
        staging.deleteRecursively()
        return ok
    }


    companion object {
        const val DEFAULT_SAVES_PATH = "/storage/emulated/0/Android/data/xyz.aethersx2.android/files/memcards"

        /**
         * ARMSX2 (fork nativo ARM64 de PCSX2). Mismo layout de folder memory
         * cards que AetherSX2 (directorios ".ps2" bajo files/memcards),
         * distinto package: `com.armsx2` (configurable en build; este es el
         * stable). Ojo: el asistente de primera ejecución propone una carpeta
         * custom fuera de Android/data — si el usuario la usó, la ruta base
         * debe apuntar ahí (configurable por plataforma).
         */
        const val ARMSX2_SAVES_PATH = "/storage/emulated/0/Android/data/com.armsx2/files/memcards"
    }
}
