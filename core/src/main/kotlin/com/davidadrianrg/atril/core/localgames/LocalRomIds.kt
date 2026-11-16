package com.davidadrianrg.atril.core.localgames

/**
 * Ids numéricos estables para los juegos locales (B4).
 *
 * Antes el id salía de `id.hashCode()` en cada pantalla: dos juegos con el
 * mismo hash compartían id, se duplicaba la `key` de las listas de Compose
 * (crash) y se mezclaban favoritos y tiempo jugado. Ahora se asigna una vez y
 * se guarda en [LocalGame.romId].
 *
 * Compatibilidad: el valor inicial es el mismo hash de siempre, así los
 * juegos existentes conservan sus estadísticas. Solo si ya está ocupado se
 * busca el siguiente libre. Siempre negativo: nunca choca con un id de RomM.
 */
object LocalRomIds {

    /** El id que usaban las versiones anteriores (punto de partida). */
    fun legacy(id: String): Int = -((id.hashCode().toLong() and 0x7FFFFFFF).toInt().coerceAtLeast(1))

    /** Id de [game] en la estantería. */
    fun of(game: LocalGame): Int = game.romId ?: legacy(game.id)

    /**
     * Devuelve [games] con un [LocalGame.romId] único en todos. Los ya
     * asignados se respetan; los que faltan (o un duplicado heredado) se
     * asignan por orden de alta, así el juego más antiguo conserva el id que
     * ya tenía. Si no hay nada que cambiar devuelve la misma lista.
     */
    fun assign(games: List<LocalGame>): List<LocalGame> {
        val used = HashSet<Int>()
        val result = HashMap<String, Int>()
        val ordered = games.sortedWith(
            compareBy<LocalGame>({ it.romId == null }, { it.addedAt }, { it.id }),
        )
        for (game in ordered) {
            val persisted = game.romId?.takeIf { it < 0 && it !in used }
            val id = persisted ?: nextFree(legacy(game.id), used)
            used += id
            result[game.id] = id
        }
        if (games.all { it.romId == result[it.id] }) return games
        return games.map { it.copy(romId = result[it.id]) }
    }

    private fun nextFree(start: Int, used: Set<Int>): Int {
        var candidate = start
        while (candidate in used) {
            candidate = if (candidate <= -Int.MAX_VALUE) -1 else candidate - 1
        }
        return candidate
    }
}
