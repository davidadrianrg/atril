package com.davidadrianrg.atril.core.playstats

/**
 * Cronómetro de una partida en curso. Puro y con reloj inyectable: no toca
 * disco ni Android salvo a través del [journal] opcional, así que las pruebas
 * fijan el tiempo.
 *
 * Solo hay una sesión abierta a la vez: un lanzamiento nuevo mientras hay otra
 * abierta descarta la anterior (manda el último). Una sesión que no llega a
 * [MIN_SESSION_MS] se descarta (abrir y volver enseguida no es «jugar»), y
 * cerrar sin haber abierto no es un error, es un no-op.
 *
 * Con [journal] la sesión abierta sobrevive a la muerte del proceso: al
 * construir el tracker se recupera y el siguiente [end] la cierra. Como la
 * hora real de fin se desconoce si el dispositivo se apagó o Atril no se
 * reabrió en días, ninguna sesión cuenta más de [MAX_SESSION_MS].
 */
class PlaySessionTracker(
    private val journal: PlaySessionJournal? = null,
    /** Último parámetro: los tests lo pasan como lambda final (`PlaySessionTracker { now }`). */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Partida cerrada lo bastante larga como para contarla. */
    data class CompletedSession(val romId: Int, val startedAt: Long, val endedAt: Long) {
        val durationMs: Long get() = endedAt - startedAt
    }

    private var runningRomId: Int? = null
    private var startedAt = 0L

    init {
        journal?.read()?.let { open ->
            runningRomId = open.romId
            startedAt = open.startedAt
        }
    }

    /** Abre una sesión para [romId]; si había otra abierta, se descarta. */
    @Synchronized
    fun begin(romId: Int, nowMs: Long = clock()) {
        runningRomId = romId
        startedAt = nowMs
        journal?.write(PlaySessionJournal.OpenSession(romId, nowMs))
    }

    /**
     * Cierra la sesión abierta. Devuelve la partida completada o null si no
     * había ninguna abierta o si duró menos de [MIN_SESSION_MS]. Una sesión
     * más larga que [MAX_SESSION_MS] se recorta a ese máximo.
     */
    @Synchronized
    fun end(nowMs: Long = clock()): CompletedSession? {
        val romId = runningRomId ?: return null
        runningRomId = null
        journal?.clear()
        val endedAt = minOf(nowMs, startedAt + MAX_SESSION_MS)
        val session = CompletedSession(romId, startedAt, endedAt)
        return session.takeIf { it.durationMs >= MIN_SESSION_MS }
    }

    /** true mientras hay una partida en curso. */
    @Synchronized
    fun isRunning(): Boolean = runningRomId != null

    companion object {
        /** Duración mínima para contar una partida: 5 s. */
        const val MIN_SESSION_MS: Long = 5000L

        /**
         * Duración máxima atribuible a una sesión: 3 h. Evita inflar el tiempo
         * jugado cuando Atril no vuelve a primer plano (dispositivo dormido con
         * el emulador delante, proceso muerto y app reabierta días después).
         */
        const val MAX_SESSION_MS: Long = 3L * 60 * 60 * 1000
    }
}
