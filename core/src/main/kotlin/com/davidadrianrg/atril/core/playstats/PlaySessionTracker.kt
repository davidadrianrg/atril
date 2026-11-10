package com.davidadrianrg.atril.core.playstats

/**
 * Cronómetro de una partida en curso. Puro y con reloj inyectable: no toca
 * disco ni Android, así que las pruebas fijan el tiempo.
 *
 * Solo hay una sesión abierta a la vez: un lanzamiento nuevo mientras hay otra
 * abierta descarta la anterior (manda el último). Una sesión que no llega a
 * [MIN_SESSION_MS] se descarta (abrir y volver enseguida no es «jugar»), y
 * cerrar sin haber abierto no es un error, es un no-op.
 */
class PlaySessionTracker(private val clock: () -> Long = System::currentTimeMillis) {

    /** Partida cerrada lo bastante larga como para contarla. */
    data class CompletedSession(val romId: Int, val startedAt: Long, val endedAt: Long) {
        val durationMs: Long get() = endedAt - startedAt
    }

    private var runningRomId: Int? = null
    private var startedAt = 0L

    /** Abre una sesión para [romId]; si había otra abierta, se descarta. */
    @Synchronized
    fun begin(romId: Int, nowMs: Long = clock()) {
        runningRomId = romId
        startedAt = nowMs
    }

    /**
     * Cierra la sesión abierta. Devuelve la partida completada o null si no
     * había ninguna abierta o si duró menos de [MIN_SESSION_MS].
     */
    @Synchronized
    fun end(nowMs: Long = clock()): CompletedSession? {
        val romId = runningRomId ?: return null
        runningRomId = null
        val session = CompletedSession(romId, startedAt, nowMs)
        return session.takeIf { it.durationMs >= MIN_SESSION_MS }
    }

    /** true mientras hay una partida en curso. */
    @Synchronized
    fun isRunning(): Boolean = runningRomId != null

    companion object {
        /** Duración mínima para contar una partida: 5 s. */
        const val MIN_SESSION_MS: Long = 5000L
    }
}
