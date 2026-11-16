package com.davidadrianrg.atril.core.playstats

/**
 * Filtros rápidos y buscador del launcher (Play), compartidos por Android y
 * escritorio para que ambos muestren exactamente los mismos juegos.
 */
object PlayQuickFilter {
    const val FAVORITES = "favorites"
    const val RECENT = "recent"

    /** Pulsar la píldora activa la apaga; otra la sustituye. */
    fun toggle(current: String?, chosen: String): String? = if (current == chosen) null else chosen

    fun matches(filter: String?, stats: GamePlayStats): Boolean = when (filter) {
        FAVORITES -> stats.favorite
        RECENT -> stats.hasPlayData
        else -> true
    }

    /** Clave i18n del vacío: explica por qué no hay juegos con este filtro. */
    fun emptyMessageKey(filter: String?): String = when (filter) {
        FAVORITES -> "play.empty.favorites"
        RECENT -> "play.empty.recent"
        else -> "play.empty.platform"
    }

    /**
     * Resultados del buscador: todo si la consulta está vacía; si no, los que
     * contienen cada palabra (sin mayúsculas ni acentos), los que empiezan por
     * la consulta primero.
     */
    fun <T> search(items: List<T>, query: String, name: (T) -> String): List<T> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return items
        val q = words.joinToString(" ")
        return items
            .map { it to normalize(name(it)) }
            .filter { (_, n) -> words.all { n.contains(it) } }
            .sortedBy { (_, n) -> if (n.startsWith(q)) 0 else 1 }
            .map { it.first }
    }

    private fun normalize(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
