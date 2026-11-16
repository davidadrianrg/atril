package com.davidadrianrg.atril.core.localgames

import com.davidadrianrg.atril.core.launch.LaunchPlatforms

/**
 * Plataformas que se ofrecen al dar de alta un juego local.
 *
 * Las de RomM (si hay servidor) mandan: conservan su slug y su nombre. A ellas
 * se suma un catálogo integrado para que el modo «solo local» —sin RomM, con
 * la tabla de plataformas vacía— siga pudiendo añadir juegos. Una entrada del
 * catálogo se omite si alguna plataforma de RomM ya cubre su slug canónico.
 */
object LocalPlatformChoices {

    /** Slug canónico (el de [LaunchPlatforms]) → nombre visible. */
    val BUILTIN: List<Pair<String, String>> = listOf(
        "3do" to "3DO",
        "3ds" to "Nintendo 3DS",
        "32x" to "Sega 32X",
        "arcade" to "Arcade",
        "atari2600" to "Atari 2600",
        "atari5200" to "Atari 5200",
        "atari7800" to "Atari 7800",
        "cdi" to "Philips CD-i",
        "dreamcast" to "Sega Dreamcast",
        "gb" to "Game Boy",
        "gba" to "Game Boy Advance",
        "gbc" to "Game Boy Color",
        "gc" to "Nintendo GameCube",
        "genesis" to "Sega Mega Drive / Genesis",
        "gg" to "Sega Game Gear",
        "jaguar" to "Atari Jaguar",
        "lynx" to "Atari Lynx",
        "msx" to "MSX",
        "n64" to "Nintendo 64",
        "nds" to "Nintendo DS",
        "neogeo" to "Neo Geo",
        "nes" to "Nintendo Entertainment System",
        "ngp" to "Neo Geo Pocket",
        "ngpc" to "Neo Geo Pocket Color",
        "pcfx" to "PC-FX",
        "pokemini" to "Pokémon Mini",
        "ps2" to "PlayStation 2",
        "ps3" to "PlayStation 3",
        "psp" to "PlayStation Portable",
        "psx" to "PlayStation",
        "saturn" to "Sega Saturn",
        "scd" to "Sega CD / Mega-CD",
        "sg1000" to "Sega SG-1000",
        "sms" to "Sega Master System",
        "snes" to "Super Nintendo",
        "switch" to "Nintendo Switch",
        "tg16" to "TurboGrafx-16 / PC Engine",
        "tgcd" to "TurboGrafx-CD / PC Engine CD",
        "virtualboy" to "Virtual Boy",
        "wii" to "Nintendo Wii",
        "wiiu" to "Nintendo Wii U",
        "wonderswan" to "WonderSwan",
        "wsc" to "WonderSwan Color",
        "xbox" to "Xbox",
        "xbox360" to "Xbox 360",
    )

    /**
     * Lista final (slug, nombre) ordenada por nombre: primero cuentan las de
     * RomM ([known]); el catálogo integrado solo rellena huecos.
     */
    fun build(known: List<Pair<String, String>>): List<Pair<String, String>> {
        val fromServer = known.filter { it.first.isNotBlank() }.distinctBy { it.first }
        val covered = fromServer.map { LaunchPlatforms.canonical(it.first) }.toSet()
        val builtin = BUILTIN.filter { LaunchPlatforms.canonical(it.first) !in covered }
        return (fromServer + builtin).sortedBy { it.second.lowercase() }
    }

    /**
     * Como [LocalGamePaths.guessPlatform], pero tolerante a alias: una carpeta
     * `megadrive` encuentra el slug `genesis-slash-megadrive` de RomM.
     */
    fun guess(path: String, slugs: Collection<String>): String? {
        LocalGamePaths.guessPlatform(path, slugs)?.let { return it }
        val byCanonical = slugs.associateBy { LaunchPlatforms.canonical(it) }
        return path.split('/').dropLast(1).asReversed()
            .firstNotNullOfOrNull { byCanonical[LaunchPlatforms.canonical(it)] }
    }
}
