package com.davidadrianrg.atril.data.backup

/**
 * Codificación de las preferencias genéricas de la copia de seguridad
 * (esquema 2+). Cada valor va etiquetado con su tipo (`s:`, `b:`, `i:`, `l:`,
 * `f:`) para restaurarlo en la clave de DataStore correcta.
 *
 * Quedan fuera:
 * - credenciales y servidor (`server_url`, `local_only`, `api_key`,
 *   `scraper_*`): viajan solo en los campos explícitos y opt-in del backup,
 *   que además reconfiguran el cliente HTTP;
 * - identificadores y estado propio de este dispositivo (ids de sync, última
 *   sincronización, avisos de actualización ya mostrados);
 * - la vigilancia de saves, que arranca un servicio en primer plano y debe
 *   activarse a mano en cada dispositivo;
 * - los fondos elegidos por juego (rutas a ficheros locales).
 */
internal object PreferenceSnapshot {

    private val BLOCKED = setOf(
        "server_url", "local_only", "api_key",
        "client_device_id", "sync_device_id", "sync_device_id_string",
        "last_sync_timestamp", "last_sync_summary", "last_sync_conflicts_json",
        "last_sync_failed_json", "skipped_version", "last_notified_update_version",
        "save_sync_watch_enabled",
        // Rutas a imágenes guardadas en este dispositivo: la copia no lleva
        // los ficheros y el fondo quedaría vacío en vez de usar la captura.
        "game_backgrounds",
    )

    /**
     * Preferencias con el mismo nombre en escritorio (`java.util.prefs`, todo
     * texto) y su etiqueta de tipo en DataStore. Espejo de
     * `DesktopSettingsBackup.SHARED_WITH_ANDROID`.
     */
    private val SHARED_WITH_DESKTOP = mapOf(
        "max_concurrent_downloads" to "i",
        "conflict_policy" to "s",
        "auto_physical_media" to "b",
        "media_download_enabled" to "b",
        "esde_export_enabled" to "b",
        "save_sync_enabled" to "s",
        com.davidadrianrg.atril.core.library.LibraryPreferenceKeys.VIEW_MODE to "s",
        com.davidadrianrg.atril.core.library.LibraryPreferenceKeys.SORT to "s",
        com.davidadrianrg.atril.core.library.LibraryPreferenceKeys.SOURCE to "s",
        com.davidadrianrg.atril.core.library.LibraryPreferenceKeys.CARD_SIZE to "s",
        com.davidadrianrg.atril.core.library.LibraryPreferenceKeys.COVER_STYLE to "s",
    )

    /** `desktopSettings` de una copia de escritorio, etiquetado para [decode]. */
    fun fromDesktop(raw: Map<*, *>): Map<String, String> =
        SHARED_WITH_DESKTOP.mapNotNull { (key, tag) ->
            (raw[key] as? String)?.let { key to "$tag:$it" }
        }.toMap()

    fun isBackedUp(name: String): Boolean = name !in BLOCKED && !name.startsWith("scraper_")

    /** Valores de DataStore (nombre de clave -> valor) a su forma etiquetada. */
    fun encode(values: Map<String, Any?>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        values.forEach { (name, value) ->
            if (!isBackedUp(name)) return@forEach
            when (value) {
                is String -> out[name] = "s:$value"
                is Boolean -> out[name] = "b:$value"
                is Int -> out[name] = "i:$value"
                is Long -> out[name] = "l:$value"
                is Float -> out[name] = "f:$value"
            }
        }
        return out
    }

    /**
     * Inversa de [encode]. Descarta claves bloqueadas, entradas que no sean
     * texto (un JSON manipulado) y valores que no casan con su etiqueta.
     */
    fun decode(raw: Map<*, *>): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        raw.forEach { (key, tagged) ->
            val name = key as? String ?: return@forEach
            val text = tagged as? String ?: return@forEach
            if (!isBackedUp(name) || name.isBlank()) return@forEach
            val value = text.substringAfter(':', "")
            val decoded: Any? = when (text.substringBefore(':', "")) {
                "s" -> value
                "b" -> value.toBooleanStrictOrNull()
                "i" -> value.toIntOrNull()
                "l" -> value.toLongOrNull()
                "f" -> value.toFloatOrNull()
                else -> null
            }
            if (decoded != null) out[name] = decoded
        }
        return out
    }
}
