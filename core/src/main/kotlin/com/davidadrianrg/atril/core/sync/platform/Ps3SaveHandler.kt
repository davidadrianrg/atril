package com.davidadrianrg.atril.core.sync.platform

import com.davidadrianrg.atril.core.sync.SaveNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Handler de saves para PS3 (ARMSX3 / RPCSX, port Android de RPCS3).
 *
 * Estructura esperada:
 * ```
 * {savesBasePath}/home/{userId}/savedata/{saveDirName}/
 *   ├── ICON0.PNG
 *   ├── PIC1.PNG
 *   ├── PARAM.SFO
 *   └── USRDIR/…   ← los datos de partida
 * ```
 *
 * `savesBasePath` debe apuntar a `config/dev_hdd0` dentro del almacenamiento
 * de la app (p. ej. `/storage/emulated/0/Android/data/com.armsx3/files/config/dev_hdd0`).
 * El `userId` es "00000001" tipicamente. El `saveDirName` lo decide cada juego:
 * suele ser el serial del disco con sufijos (p. ej. `BLUS30181USERDATA` o
 * `NPUA80XXX-USERDATA`), pero la unica fuente fiable es el PARAM.SFO del
 * propio save (campo SAVEDATA_DIRECTORY). Para localizar el save de un ROM sin
 * leer su PARAM.SFO embebido en el PKG/ISO, emparejamos por prefijo del serial
 * extraido del nombre del ROM (formatos `BLUS-30181`, `[BLUS30181]`, etc.).
 *
 * Solo se zipea y sincroniza la carpeta del save (ICON/PIC/PARAM incluidos:
 * ARMSX3 los usa para mostrar y validar el save al restaurar).
 */
class Ps3SaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String?,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        val serial = extractSerial(romFileName) ?: return@withContext results
        val saveDir = findSaveDir(savesBasePath, serial) ?: return@withContext results

        val files = saveDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return@withContext results

        val newestMtime = files.maxOf { it.lastModified() }
        val zipFile = File.createTempFile("ps3_save_${serial}_", ".zip")
        zipFolderDeterministic(saveDir, zipFile)

        results.add(
            LocalSave(
                romId = romId,
                fileName = "${serial}_ps3_save.zip",
                file = zipFile,
                lastModified = newestMtime,
                sha1 = zipFile.sha1(),
            ),
        )
        results
    }

    override suspend fun prepareForUpload(save: LocalSave): File = save.file

    override suspend fun savesFingerprint(
        romId: Int, romFileName: String, platformSlug: String,
        savesBasePath: String, romLocalPath: String?,
    ): String? = withContext(Dispatchers.IO) {
        val serial = extractSerial(romFileName) ?: return@withContext null
        val saveDir = findSaveDir(savesBasePath, serial) ?: return@withContext null
        folderFingerprint(saveDir)
    }

    override suspend fun extractDownload(
        tempFile: File,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        targetFileName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val serial = extractSerial(romFileName) ?: return@withContext false

        // Ruta destino: reconstruir con el userId local.
        val savedataRoot = savedataRoot(savesBasePath)
            ?: File(savesBasePath, "home/$DEFAULT_USER_ID/savedata").also { it.mkdirs() }

        // El zip se creó con prefijo "{saveDirName}/" — el nombre real de la
        // carpeta del save viaja en la primera entrada del zip (es la fuente
        // fiable: los juegos eligen sufijos tipo USERDATA que no se derivan
        // del serial). Fallback: carpeta local existente o el serial pelado.
        val candidate = firstZipEntryTopDir(tempFile)?.takeIf { SaveNames.isSafeSaveFileName(it) }
            ?: findSaveDir(savesBasePath, serial)?.name?.takeIf { SaveNames.isSafeSaveFileName(it) }
            ?: serial.replace("-", "")
        if (!SaveNames.isSafeSaveFileName(candidate)) return@withContext false

        // El destino debe quedar estrictamente dentro de savedataRoot.
        val root = try {
            savedataRoot.canonicalFile
        } catch (_: Exception) {
            return@withContext false
        }
        val targetDir = try {
            File(root, candidate).canonicalFile
        } catch (_: Exception) {
            return@withContext false
        }
        val rootPrefix = root.path + File.separator
        if (targetDir == root || !targetDir.path.startsWith(rootPrefix)) return@withContext false

        // Primera pasada: validar TODO el archivo antes de tocar el destino.
        if (!forEachSafeEntry(tempFile, candidate, null)) return@withContext false

        // Extraer primero a un staging propio (bajo savedataRoot, mismo
        // sistema de ficheros para poder renombrar, nunca dentro del destino):
        // un archivo estructuralmente hostil ('X' como fichero y 'X/Y') o un
        // fallo de E/S a mitad no puede destruir el save existente.
        if (!root.isDirectory && !root.mkdirs()) return@withContext false
        val staging = createStagingDir(root) ?: return@withContext false
        try {
            val targetPrefix = targetDir.path + File.separator
            if (staging.path == targetDir.path || staging.path.startsWith(targetPrefix)) {
                return@withContext false
            }

            var failed = false
            val extracted = forEachSafeEntry(tempFile, candidate) { rel, isDirectory, zipIn ->
                val outFile = SaveNames.resolveArchiveEntry(staging, rel)
                if (outFile == null) {
                    failed = true
                    return@forEachSafeEntry
                }
                if (isDirectory) {
                    outFile.mkdirs()
                    if (!outFile.isDirectory) failed = true
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zipIn.copyTo(out) }
                }
            }
            if (!extracted || failed) return@withContext false

            // Sustitución: el staging ya contiene el árbol completo y validado.
            if (targetDir.isDirectory) clearDirectoryContents(targetDir)
            targetDir.mkdirs()
            val staged = staging.listFiles() ?: return@withContext false
            staged.all { child ->
                val dest = SaveNames.resolveArchiveEntry(targetDir, child.name) ?: return@all false
                child.renameTo(dest)
            }
        } catch (_: Exception) {
            false
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Crea un directorio de staging nuevo bajo [parent].
     *
     * Debe colgar de `savedataRoot` para compartir sistema de ficheros con el
     * destino: el intercambio final se hace con `renameTo`, que solo es
     * atómico dentro del mismo volumen. Se usa un nombre oculto y aleatorio
     * para no colisionar con saves ni con restos de una corrida anterior.
     */
    private fun createStagingDir(parent: File): File? = try {
        repeat(4) {
            val dir = File(parent, ".ps3_restore_stage_${UUID.randomUUID()}")
            if (!dir.exists() && dir.mkdirs()) return dir
        }
        null
    } catch (_: Exception) {
        null
    }

    /**
     * Recorre las entradas del zip validando cada ruta relativa a [dirName].
     *
     * Un `rel` en blanco se salta. Cualquier ruta que no pase
     * [SaveNames.isSafeArchiveEntryPath] aborta la pasada con `false`. Con
     * `onEntry == null` solo valida (no escribe nada).
     */
    private fun forEachSafeEntry(
        zip: File,
        dirName: String,
        onEntry: ((rel: String, isDirectory: Boolean, input: ZipInputStream) -> Unit)?,
    ): Boolean = try {
        ZipInputStream(FileInputStream(zip)).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                // Quitar el prefijo "{dirName}/" con el que se zipeó.
                val rel = entry.name.removePrefix("$dirName/")
                if (rel.isNotBlank()) {
                    if (!SaveNames.isSafeArchiveEntryPath(rel)) return false
                    onEntry?.invoke(rel, entry.isDirectory, zipIn)
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Borra solo el contenido de [dir]; nunca el propio directorio.
     *
     * Cada hijo se canonicaliza: si sigue dentro de [dir] se borra
     * recursivamente; si escapa (enlace simbólico) solo se borra el enlace.
     */
    private fun clearDirectoryContents(dir: File) {
        val canonicalDir = try {
            dir.canonicalFile
        } catch (_: Exception) {
            return
        }
        val prefix = canonicalDir.path + File.separator
        canonicalDir.listFiles()?.forEach { child ->
            val canonicalChild = try {
                child.canonicalFile
            } catch (_: Exception) {
                null
            }
            if (canonicalChild != null && canonicalChild.path.startsWith(prefix)) {
                canonicalChild.deleteRecursively()
            } else {
                child.delete()
            }
        }
    }

    /**
     * Primer segmento de ruta de la primera entrada del zip: el nombre de la
     * carpeta de savedata con la que se subió (p. ej. "BLUS30181USERDATA").
     */
    private fun firstZipEntryTopDir(zip: File): String? = try {
        ZipInputStream(FileInputStream(zip)).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null && entry.isDirectory) entry = zipIn.nextEntry
            entry?.name?.takeIf { it.contains('/') }?.substringBefore('/')
                ?: entry?.name?.substringBefore('/')  // entrada plana: usar el nombre base
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Localiza `home/{user}/savedata/{saveDir}` cuyo nombre empiece por el
     * serial del ROM (los juegos añaden sufijos tipo `USERDATA`, `-GAME`,
     * etc.). Recorre todos los usuarios y devuelve el match más largo
     * (más específico).
     */
    private fun findSaveDir(basePath: String, serial: String): File? {
        val root = savedataRoot(basePath) ?: return null
        val serialNoSep = serial.replace("-", "")
        var best: File? = null
        var bestLen = 0
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val nameNoSep = dir.name.replace("-", "")
            if (nameNoSep.startsWith(serialNoSep, ignoreCase = true)) {
                val matched = minOf(nameNoSep.length, serialNoSep.length)
                if (matched > bestLen) {
                    bestLen = matched
                    best = dir
                }
            }
        }
        return best
    }

    /**
     * `home/{user}/savedata` existente, o null si no hay árbol dev_hdd0.
     */
    private fun savedataRoot(basePath: String): File? {
        val home = File(basePath, "home")
        if (!home.isDirectory) return null
        // Usuario preferido: el de id más bajo presente (00000001 primero).
        val user = home.listFiles()
            ?.filter { it.isDirectory && it.name.all(Char::isDigit) && it.name.length == 8 }
            ?.minByOrNull { it.name }
            ?: return null
        return File(user, "savedata")
    }

    /**
     * Extrae un serial PS3 del nombre del ROM.
     * Formatos: "BLUS-30181 - Game.pkg", "[BLUS30181] Game.pkg",
     * "NPUB30624 Game.pkg", también sin delimitador "BLUS30181 - Game".
     */
    private fun extractSerial(fileName: String): String? {
        val patterns = listOf(
            Regex("""([A-Z]{4}[-_]?\d{5})"""),
        )
        for (pattern in patterns) {
            val cleaned = fileName.substringBefore(" - ")
            val match = pattern.find(cleaned)
            if (match != null) {
                return match.groupValues[1].replace("_", "-")
            }
        }
        return null
    }

    companion object {
        const val DEFAULT_SAVES_PATH =
            "/storage/emulated/0/Android/data/com.armsx3/files/config/dev_hdd0"
        private const val DEFAULT_USER_ID = "00000001"
    }
}
