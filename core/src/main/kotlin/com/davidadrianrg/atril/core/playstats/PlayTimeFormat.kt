package com.davidadrianrg.atril.core.playstats

import com.davidadrianrg.atril.core.i18n.tr

/**
 * Formatos de fecha relativa y de tiempo jugado, compartidos por Android y
 * escritorio. Son funciones puras que reciben «ahora» por parámetro y pintan
 * con las plantillas de [com.davidadrianrg.atril.core.i18n.I18n], de modo que
 * la salida sigue el idioma activo y las pruebas son deterministas.
 */
object PlayTimeFormat {

    /**
     * «Nunca», «ahora mismo», «hace 5 min», «hace 2 h», «ayer» o «hace 3 días».
     * Una última partida en el futuro (reloj desajustado) se trata como ahora.
     */
    fun relativeDate(lastPlayedAt: Long?, nowMs: Long): String {
        if (lastPlayedAt == null) return tr("game.info.never_played")
        val elapsed = (nowMs - lastPlayedAt).coerceAtLeast(0L)
        return when {
            elapsed < MINUTE_MS -> tr("time.relative.now")
            elapsed < HOUR_MS -> tr("time.relative.minutes", elapsed / MINUTE_MS)
            elapsed < DAY_MS -> tr("time.relative.hours", elapsed / HOUR_MS)
            elapsed < YESTERDAY_WINDOW_MS -> tr("time.relative.yesterday")
            else -> tr("time.relative.days", elapsed / DAY_MS)
        }
    }

    /** «<1 min», «12 min», «1 h» o «1 h 23 min». Un tiempo negativo cuenta como cero. */
    fun duration(totalMs: Long): String {
        val played = totalMs.coerceAtLeast(0L)
        return when {
            played < MINUTE_MS -> tr("time.duration.under_minute")
            played < HOUR_MS -> tr("time.duration.minutes", played / MINUTE_MS)
            else -> {
                val hours = played / HOUR_MS
                val minutes = (played % HOUR_MS) / MINUTE_MS
                if (minutes == 0L) {
                    tr("time.duration.hours", hours)
                } else {
                    tr("time.duration.hours_minutes", hours, minutes)
                }
            }
        }
    }

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    /** Hasta 48 h se sigue diciendo «ayer»; a partir de ahí, días. */
    private const val YESTERDAY_WINDOW_MS = 172_800_000L
}
