package com.davidadrianrg.atril.core.library

/**
 * Línea de información de una sola fila para el launcher en horizontal (UX1):
 * «título · metadatos · última partida». En horizontal no cabe el pie de
 * varias líneas y, con medio físico, la carta no muestra el título.
 *
 * Pura: recibe los textos ya formateados (metadatos con [GameMetadataLine],
 * fecha relativa con `PlayTimeFormat`) y descarta los que estén vacíos.
 */
object LauncherInfoLine {

    /**
     * @param metadata la línea de [GameMetadataLine.format], o null.
     * @param lastPlayed etiqueta ya traducida («Última partida · ayer»), o null si nunca se jugó.
     * @return los textos en orden, sin blancos; el título siempre va primero.
     */
    fun parts(title: String, metadata: String?, lastPlayed: String?): List<String> =
        listOf(title, metadata, lastPlayed).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }

    fun format(title: String, metadata: String?, lastPlayed: String?): String =
        parts(title, metadata, lastPlayed).joinToString(GameMetadataLine.SEPARATOR)
}
