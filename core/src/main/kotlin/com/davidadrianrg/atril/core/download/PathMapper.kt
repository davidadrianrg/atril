package com.davidadrianrg.atril.core.download

import com.davidadrianrg.atril.core.remote.PlatformSlug
import java.io.File

/**
 * Maps RomM platform slugs to ES-DE compatible local folder structure.
 *
 * RomM Slug → Local path:
 *   snes      → /ROMs/snes/
 *   megadrive → /ROMs/megadrive/
 *
 * The root path (e.g. /storage/emulated/0/ROMs) is provided by the user
 * via DataStore configuration.
 */
object PathMapper {

    /**
     * Folder that receives a missing or empty slug.
     *
     * The historic sentinel is kept so folders already created as
     * `<root>/unknown` keep resolving to the same place.
     */
    const val FALLBACK_SLUG: String = "unknown"

    /**
     * Effective folder name for a platform slug.
     *
     * Always normalizes (lowercase and trim) before validating so folders
     * created with that normalization keep being found, then applies the single
     * [PlatformSlug] rule: a valid slug is returned verbatim and an invalid one
     * yields `null` (the download engine rejects it). A missing or empty slug is
     * the [FALLBACK_SLUG] sentinel, not an invalid value.
     *
     * Total: it never throws and never touches the filesystem.
     *
     * @param rawSlug platform slug, possibly null or untrusted.
     * @return the folder name, or `null` when the value fails [PlatformSlug].
     */
    fun platformDirName(rawSlug: String?): String? {
        val normalizedSlug = (rawSlug ?: "").lowercase().trim()
        if (normalizedSlug.isEmpty()) return FALLBACK_SLUG
        return PlatformSlug.sanitize(normalizedSlug)
    }

    /**
     * Returns the full local directory for a platform slug.
     * Does NOT create the directory — caller is responsible.
     *
     * A hostile slug never reaches the [File] constructor: it is sanitized by
     * [platformDirName] and, when invalid, [FALLBACK_SLUG] keeps the path inside
     * [romsRootPath].
     */
    fun getPlatformDir(romsRootPath: String, platformSlug: String): File {
        val dirName = platformDirName(platformSlug) ?: FALLBACK_SLUG
        return File(romsRootPath, dirName)
    }

    /**
     * Returns the full local file path for a ROM.
     */
    fun getRomFile(romsRootPath: String, platformSlug: String, fileName: String): File {
        return File(getPlatformDir(romsRootPath, platformSlug), fileName)
    }

    /**
     * Ensures the platform directory exists. Returns true if created or already exists.
     */
    fun ensurePlatformDir(romsRootPath: String, platformSlug: String): Boolean {
        val dir = getPlatformDir(romsRootPath, platformSlug)
        if (!dir.exists()) {
            return dir.mkdirs()
        }
        return true
    }
}
