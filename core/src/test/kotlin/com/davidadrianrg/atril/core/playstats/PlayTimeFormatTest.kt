package com.davidadrianrg.atril.core.playstats

import com.davidadrianrg.atril.core.i18n.I18n
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Formatos de fecha relativa y de tiempo jugado tal y como los pide el
 * producto. «Ahora» es siempre un instante fijo y las cadenas esperadas están
 * escritas a mano en ambos idiomas: si una plantilla o un umbral cambia, la
 * prueba falla (AC1/AC-12).
 */
class PlayTimeFormatTest {

    /** Instante fijo de referencia: 2023-11-14T22:13:20Z. */
    private val now = 1_700_000_000_000L

    private fun <T> withLocale(locale: Locale, block: () -> T): T {
        val previous = I18n.locale
        I18n.locale = locale
        return try {
            block()
        } finally {
            I18n.locale = previous
        }
    }

    private fun spanish(block: () -> Unit) = withLocale(Locale.forLanguageTag("es"), block)

    private fun english(block: () -> Unit) = withLocale(Locale.ENGLISH, block)

    private fun ago(ms: Long): Long = now - ms

    // ── Tiempo jugado ────────────────────────────────────────────────

    @Test
    fun `duration uses hours and minutes in Spanish`() = spanish {
        assertEquals("1 h 23 min", PlayTimeFormat.duration(4_980_000))
        assertEquals("12 min", PlayTimeFormat.duration(720_000))
        assertEquals("<1 min", PlayTimeFormat.duration(59_999))
        assertEquals("<1 min", PlayTimeFormat.duration(0))
        assertEquals("1 h", PlayTimeFormat.duration(3_600_000))
        assertEquals("1 h 20 min", PlayTimeFormat.duration(4_800_000))
    }

    @Test
    fun `duration renders the same values in English`() = english {
        assertEquals("1 h 23 min", PlayTimeFormat.duration(4_980_000))
        assertEquals("12 min", PlayTimeFormat.duration(720_000))
        assertEquals("<1 min", PlayTimeFormat.duration(59_999))
        assertEquals("<1 min", PlayTimeFormat.duration(0))
        assertEquals("1 h", PlayTimeFormat.duration(3_600_000))
        assertEquals("1 h 20 min", PlayTimeFormat.duration(4_800_000))
    }

    @Test
    fun `duration boundaries sit at the minute and the hour`() = spanish {
        assertEquals("<1 min", PlayTimeFormat.duration(59_999))
        assertEquals("1 min", PlayTimeFormat.duration(60_000))
        assertEquals("59 min", PlayTimeFormat.duration(3_599_999))
        assertEquals("1 h", PlayTimeFormat.duration(3_600_000))
        assertEquals("1 h 1 min", PlayTimeFormat.duration(3_660_000))
        // Un tiempo negativo (dato manipulado) cuenta como cero, no rompe el formato.
        assertEquals("<1 min", PlayTimeFormat.duration(-1))
    }

    // ── Fecha relativa en español ────────────────────────────────────

    @Test
    fun `relative date in Spanish uses the product wording`() = spanish {
        assertEquals("Nunca", PlayTimeFormat.relativeDate(null, now))
        assertEquals("ahora mismo", PlayTimeFormat.relativeDate(ago(30_000), now))
        assertEquals("hace 5 min", PlayTimeFormat.relativeDate(ago(300_000), now))
        assertEquals("hace 2 h", PlayTimeFormat.relativeDate(ago(7_200_000), now))
        assertEquals("ayer", PlayTimeFormat.relativeDate(ago(93_600_000), now))
        assertEquals("hace 3 días", PlayTimeFormat.relativeDate(ago(270_000_000), now))
        assertEquals("hace 8 días", PlayTimeFormat.relativeDate(ago(691_200_000), now))
    }

    @Test
    fun `relative date boundaries switch between now, minutes, hours, yesterday and days`() = spanish {
        assertEquals("ahora mismo", PlayTimeFormat.relativeDate(ago(59_999), now))
        assertEquals("hace 1 min", PlayTimeFormat.relativeDate(ago(60_000), now))
        assertEquals("hace 59 min", PlayTimeFormat.relativeDate(ago(3_599_999), now))
        assertEquals("hace 1 h", PlayTimeFormat.relativeDate(ago(3_600_000), now))
        assertEquals("hace 23 h", PlayTimeFormat.relativeDate(ago(86_399_999), now))
        assertEquals("ayer", PlayTimeFormat.relativeDate(ago(86_400_000), now))
        assertEquals("ayer", PlayTimeFormat.relativeDate(ago(172_799_999), now))
        assertEquals("hace 2 días", PlayTimeFormat.relativeDate(ago(172_800_000), now))
    }

    @Test
    fun `a last played in the future reads as just now`() = spanish {
        assertEquals("ahora mismo", PlayTimeFormat.relativeDate(now + 5_000, now))
    }

    // ── Fecha relativa en inglés ─────────────────────────────────────

    @Test
    fun `relative date in English uses the product wording`() = english {
        assertEquals("Never", PlayTimeFormat.relativeDate(null, now))
        assertEquals("just now", PlayTimeFormat.relativeDate(ago(30_000), now))
        assertEquals("5 min ago", PlayTimeFormat.relativeDate(ago(300_000), now))
        assertEquals("2 h ago", PlayTimeFormat.relativeDate(ago(7_200_000), now))
        assertEquals("yesterday", PlayTimeFormat.relativeDate(ago(93_600_000), now))
        assertEquals("3 days ago", PlayTimeFormat.relativeDate(ago(270_000_000), now))
        assertEquals("8 days ago", PlayTimeFormat.relativeDate(ago(691_200_000), now))
    }

    @Test
    fun `relative date boundaries render the English wording too`() = english {
        assertEquals("just now", PlayTimeFormat.relativeDate(ago(59_999), now))
        assertEquals("1 min ago", PlayTimeFormat.relativeDate(ago(60_000), now))
        assertEquals("1 h ago", PlayTimeFormat.relativeDate(ago(3_600_000), now))
        assertEquals("yesterday", PlayTimeFormat.relativeDate(ago(172_799_999), now))
        assertEquals("2 days ago", PlayTimeFormat.relativeDate(ago(172_800_000), now))
    }
}
