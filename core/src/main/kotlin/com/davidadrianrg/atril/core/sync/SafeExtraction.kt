package com.davidadrianrg.atril.core.sync

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * Extrae una entrada de archivo dentro de [base] sin seguir enlaces simbólicos.
 *
 * `SaveNames.resolveArchiveEntry` ya canonicaliza la ruta, pero entre esa
 * comprobación y el `mkdirs()`/`FileOutputStream` un componente intermedio
 * podía convertirse en un symlink que apuntase fuera de la base (RES-06).
 * Aquí se crean los directorios componente a componente, se rechaza cualquier
 * symlink y se abre el fichero con `NOFOLLOW_LINKS`, de modo que
 * la escritura no puede acabar fuera de [base].
 */
object SafeExtraction {

    /**
     * @return `false` si la entrada es insegura y debe saltarse.
     * @throws IOException si la escritura falla por un motivo de E/S.
     */
    fun extractEntry(
        base: File,
        entryPath: String,
        isDirectory: Boolean,
        input: InputStream?,
    ): Boolean {
        val canonicalBase = runCatching { base.canonicalFile }.getOrNull() ?: return false
        val target = SaveNames.resolveArchiveEntry(canonicalBase, entryPath) ?: return false
        if (!isStrictlyInside(canonicalBase, target)) return false

        if (isDirectory) return ensureDirectory(canonicalBase, target)

        val source = input ?: return false
        if (!ensureParentDirectories(canonicalBase, target)) return false

        // Re-resolver justo antes de abrir: si un padre se sustituyó por un
        // symlink después de la primera comprobación, aquí se detecta.
        val fresh = SaveNames.resolveArchiveEntry(canonicalBase, entryPath) ?: return false
        if (!isStrictlyInside(canonicalBase, fresh)) return false

        Files.newOutputStream(
            fresh.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { out -> source.copyTo(out) }
        return true
    }

    private fun ensureDirectory(base: File, dir: File): Boolean {
        if (!ensureParentDirectories(base, dir)) return false
        if (dir.exists()) {
            return dir.isDirectory && !Files.isSymbolicLink(dir.toPath())
        }
        return dir.mkdir() && isStrictlyInsideOrEqual(base, dir.canonicalFile)
    }

    private fun ensureParentDirectories(base: File, target: File): Boolean {
        val parent = target.parentFile ?: return false
        if (!isStrictlyInsideOrEqual(base, parent)) return false
        if (parent == base) return true
        val relative = runCatching { parent.toRelativeString(base) }.getOrNull() ?: return false
        var current = base
        for (part in relative.split(File.separatorChar).filter { it.isNotEmpty() }) {
            current = File(current, part)
            if (current.exists()) {
                if (!current.isDirectory || Files.isSymbolicLink(current.toPath())) return false
            } else if (!current.mkdir()) {
                return false
            }
            val canonical = runCatching { current.canonicalFile }.getOrNull() ?: return false
            if (!isStrictlyInsideOrEqual(base, canonical)) return false
        }
        return true
    }

    private fun isStrictlyInside(base: File, target: File): Boolean =
        target.path != base.path && isStrictlyInsideOrEqual(base, target)

    private fun isStrictlyInsideOrEqual(base: File, target: File): Boolean {
        val prefix = base.path + File.separator
        return target.path == base.path || target.path.startsWith(prefix)
    }
}
