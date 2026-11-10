package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.library.LibraryPreferenceKeys
import com.davidadrianrg.atril.core.localgames.ChosenDiscCodec
import java.io.File
import java.util.Properties

/**
 * Registro de ROMs descargados en desktop y config por plataforma.
 * Formato Properties (cero dependencias): ~/.config/atril/library.properties
 *
 *   rom.<id>.name = ...
 *   rom.<id>.file = ...
 *   rom.<id>.platform = ...
 *   platform.<slug>.emulator = ...
 *   platform.<slug>.savesPath = ...
 *   platform.<slug>.launchEmulator / launchExe / launchArgs = ...  (lanzar juegos)
 *   library_local_discs = ...  (mapa grupo -> disco elegido, ver [ChosenDiscCodec])
 */
class DesktopLibrary(private val file: File) {

    private val props = Properties()

    init {
        if (file.isFile) runCatching { file.inputStream().use { props.load(it) } }
    }

    @Synchronized
    private fun persist() {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "Atril desktop library") }
    }

    data class RomEntry(
        val romId: Int,
        val name: String,
        val fileName: String,
        val platformSlug: String,
        val localPath: String? = null,
        val savesPathOverride: String? = null,
        val excludedFromSync: Boolean = false,
    )

    data class PlatformEntry(
        val slug: String,
        val emulatorId: String? = null,
        val savesPathOverride: String? = null,
        val romsFolderOverride: String? = null,
        /** Emulador de escritorio con el que se lanza (id de DesktopEmulatorCatalog). */
        val launchEmulatorId: String? = null,
        /** Ejecutable propio (vacío = el del catálogo). */
        val launchExecutable: String? = null,
        /** Plantilla de argumentos propia (vacío = la del catálogo). */
        val launchArgs: String? = null,
    )

    fun roms(): List<RomEntry> {
        val ids = props.stringPropertyNames()
            .filter { it.startsWith("rom.") && it.endsWith(".name") }
            .mapNotNull { it.substringAfter("rom.").substringBefore('.').toIntOrNull() }
        return ids.mapNotNull { id -> readRom(id) }
    }

    fun rom(id: Int): RomEntry? = readRom(id)

    @Synchronized
    fun upsertRom(entry: RomEntry) {
        val p = "rom.${entry.romId}"
        props["$p.name"] = entry.name
        props["$p.file"] = entry.fileName
        props["$p.platform"] = entry.platformSlug
        props["$p.path"] = entry.localPath ?: ""
        props["$p.savesPath"] = entry.savesPathOverride ?: ""
        props["$p.excluded"] = entry.excludedFromSync.toString()
        persist()
    }

    @Synchronized
    fun removeRom(romId: Int) {
        val prefix = "rom.$romId."
        props.stringPropertyNames().filter { it.startsWith(prefix) }.forEach { props.remove(it) }
        persist()
    }

    fun platform(slug: String): PlatformEntry {
        val emu = props.getProperty("platform.$slug.emulator")
        val saves = props.getProperty("platform.$slug.savesPath")?.takeIf { it.isNotBlank() }
        val folder = props.getProperty("platform.$slug.romsFolder")?.takeIf { it.isNotBlank() }
        fun prop(key: String) = props.getProperty("platform.$slug.$key")?.takeIf { it.isNotBlank() }
        return PlatformEntry(
            slug, emu?.takeIf { it.isNotBlank() }, saves, folder,
            launchEmulatorId = prop("launchEmulator"),
            launchExecutable = prop("launchExe"),
            launchArgs = prop("launchArgs"),
        )
    }

    @Synchronized
    fun upsertPlatform(entry: PlatformEntry) {
        props["platform.${entry.slug}.emulator"] = entry.emulatorId ?: ""
        props["platform.${entry.slug}.savesPath"] = entry.savesPathOverride ?: ""
        props["platform.${entry.slug}.romsFolder"] = entry.romsFolderOverride ?: ""
        props["platform.${entry.slug}.launchEmulator"] = entry.launchEmulatorId ?: ""
        props["platform.${entry.slug}.launchExe"] = entry.launchExecutable ?: ""
        props["platform.${entry.slug}.launchArgs"] = entry.launchArgs ?: ""
        persist()
    }

    /**
     * Disco elegido por grupo local (mapa `clave de grupo -> id de disco`).
     * Se lee con [ChosenDiscCodec]: una propiedad corrupta da un mapa vacío.
     */
    @Synchronized
    fun localDiscChoices(): Map<String, String> =
        ChosenDiscCodec.decode(props.getProperty(KEY_LOCAL_DISCS))

    /** Fija (o borra con `null`) el disco elegido de un grupo local. */
    @Synchronized
    fun setLocalDiscChoice(groupKey: String, choiceId: String?) {
        val choices = ChosenDiscCodec.decode(props.getProperty(KEY_LOCAL_DISCS)).toMutableMap()
        if (choiceId.isNullOrBlank()) choices.remove(groupKey) else choices[groupKey] = choiceId
        props[KEY_LOCAL_DISCS] = ChosenDiscCodec.encode(choices)
        persist()
    }

    private fun readRom(id: Int): RomEntry? {
        val name = props.getProperty("rom.$id.name") ?: return null
        return RomEntry(
            romId = id,
            name = name,
            fileName = props.getProperty("rom.$id.file") ?: name,
            platformSlug = props.getProperty("rom.$id.platform") ?: "unknown",
            localPath = props.getProperty("rom.$id.path")?.takeIf { it.isNotBlank() },
            savesPathOverride = props.getProperty("rom.$id.savesPath")?.takeIf { it.isNotBlank() },
            excludedFromSync = props.getProperty("rom.$id.excluded") == "true",
        )
    }

    companion object {
        /** Clave canónica compartida con Android (`LibraryPreferenceKeys.LOCAL_DISCS`). */
        private const val KEY_LOCAL_DISCS = LibraryPreferenceKeys.LOCAL_DISCS
    }
}
