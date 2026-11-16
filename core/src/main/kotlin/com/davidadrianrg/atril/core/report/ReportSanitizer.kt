package com.davidadrianrg.atril.core.report

/**
 * Limpia el texto técnico (trazas, mensajes de excepción) antes de que acabe
 * en una issue pública: los mensajes de OkHttp o de E/S suelen llevar el host
 * del servidor RomM, IPs de la LAN o rutas con el nombre del usuario.
 */
object ReportSanitizer {

    private val url = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"'<>()\]]+""")
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""")
    private val email = Regex("""\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b""")

    /**
     * Rutas absolutas de usuario. Se conserva el prefijo del sistema (útil
     * para diagnosticar) y se oculta el resto. Los nombres de juego llevan
     * espacios, así que la ruta llega hasta `:`, comillas, paréntesis o fin de
     * línea: mejor ocultar de más que dejar medio nombre a la vista.
     */
    private val userPath = Regex(
        """(/storage/emulated/\d+|/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|/sdcard|/mnt/media_rw/[^/\s]+|/home/[^/\s]+|/Users/[^/\s]+|/run/media/[^/\s]+)(/[^\n\r:"'()]*)?""",
    )

    fun scrub(text: String): String = text
        .replace(url, "<url>")
        .replace(email, "<email>")
        .replace(ipv4, "<ip>")
        .replace(userPath) { match ->
            val root = match.groupValues[1]
            val prefix = when {
                root.startsWith("/home/") -> "/home/<user>"
                root.startsWith("/Users/") -> "/Users/<user>"
                root.startsWith("/run/media/") -> "/run/media/<user>"
                else -> root
            }
            if (match.groupValues[2].isEmpty()) prefix else "$prefix/<path>"
        }
}
