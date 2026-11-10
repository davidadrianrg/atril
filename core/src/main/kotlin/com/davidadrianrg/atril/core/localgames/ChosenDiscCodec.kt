package com.davidadrianrg.atril.core.localgames

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Códec de una línea del mapa `clave de grupo → elección de disco` que
 * persisten Android (DataStore) y el escritorio (library.properties).
 *
 * Formato: pares `clave=valor` separados por `;`, con clave y valor
 * URL-encoded en UTF-8 y las claves ordenadas. Así `/`, `|`, espacios, `=` y
 * `;` dentro de una clave o de un id son inequívocos, y dos escrituras del
 * mismo mapa producen exactamente los mismos bytes.
 *
 * [decode] nunca lanza: las entradas malformadas se ignoran.
 */
object ChosenDiscCodec {

    private const val ENTRY_SEPARATOR = ';'
    private const val KEY_VALUE_SEPARATOR = '='
    private const val ENCODING = "UTF-8"

    /** Serializa [choices] de forma determinista (claves ordenadas). */
    fun encode(choices: Map<String, String>): String =
        choices.entries
            .sortedBy { it.key }
            .joinToString(ENTRY_SEPARATOR.toString()) { (key, value) ->
                encodePart(key) + KEY_VALUE_SEPARATOR + encodePart(value)
            }

    /**
     * Lee el mapa persistido en [raw]. `null`, vacío o malformado da un mapa
     * vacío (o solo con las entradas válidas), nunca una excepción.
     */
    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val choices = LinkedHashMap<String, String>()
        for (entry in raw.split(ENTRY_SEPARATOR)) {
            val separator = entry.indexOf(KEY_VALUE_SEPARATOR)
            if (separator <= 0) continue
            val key = decodePart(entry.substring(0, separator)) ?: continue
            val value = decodePart(entry.substring(separator + 1)) ?: continue
            if (key.isBlank() || value.isBlank()) continue
            choices[key] = value
        }
        return choices
    }

    private fun encodePart(text: String): String = URLEncoder.encode(text, ENCODING)

    private fun decodePart(text: String): String? = runCatching { URLDecoder.decode(text, ENCODING) }.getOrNull()
}
