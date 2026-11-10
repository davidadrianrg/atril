package com.davidadrianrg.atril.core.library

/**
 * Marcas de disco de un nombre de fichero y normalización del nombre base.
 *
 * Es la única implementación de estas reglas: la comparten el agrupador de
 * RomM ([MultiDiscGrouper]) y el agrupador de juegos locales
 * (`com.davidadrianrg.atril.core.localgames.LocalDiscGrouper`), de forma que
 * los dos no puedan divergir.
 *
 * Las expresiones son las mismas que usaba [MultiDiscGrouper] antes de
 * extraerlas, así que su comportamiento no cambia.
 */
object DiscMarks {

    /** `(Disc 2)`, `(Disk 12)`, `(CD 1)`; también con corchetes y mayúsculas. */
    private val DISC_REGEX = Regex(
        """\((?:disc|disk|cd)\s*([0-9]{1,2})[^)]*\)""",
        RegexOption.IGNORE_CASE,
    )

    /** La marca completa con sus separadores, para quitarla del nombre base. */
    private val DISC_IN_NAME = Regex(
        """[\s\-–—]*[\[(]\s*(?:disc|disk|cd)\s*[0-9]{1,2}[^\])]*[\])]""",
        RegexOption.IGNORE_CASE,
    )

    private val WHITESPACE = Regex("\\s+")
    private val TRAILING_SEPARATORS = Regex("""[\s\-–—:]+$""")

    /**
     * Número de disco de [fileName], o `null` si no lleva marca.
     *
     * Solo acepta de 1 a 2 dígitos y la marca entre paréntesis, igual que
     * [MultiDiscGrouper] desde siempre: `Game (Disc 2).rvz` → `2`,
     * `Game (CD1).bin` → `1`, `Game (USA).rvz` → `null`.
     */
    fun discNumber(fileName: String): Int? =
        DISC_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Nombre normalizado para comparar: sin marcas de disco, minúsculas,
     * espacios simples y sin separadores finales.
     */
    fun normalizeName(name: String): String =
        DISC_IN_NAME.replace(name, "")
            .trim()
            .lowercase()
            .replace(WHITESPACE, " ")
            .replace(TRAILING_SEPARATORS, "")
            .trim()
}
