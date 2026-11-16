package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.library.LibraryPreferenceKeys
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.prefs.Preferences

/** Diálogo nativo para elegir dónde guardar ([save]) o qué copia abrir. */
internal suspend fun pickBackupFile(save: Boolean): java.io.File? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dialog = java.awt.FileDialog(
            null as java.awt.Frame?,
            com.davidadrianrg.atril.core.i18n.tr("backup.title"),
            if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD,
        )
        if (save) dialog.file = "atril-backup.json"
        dialog.isVisible = true
        val name = dialog.file
        val dir = dialog.directory
        if (name == null || dir == null) null else java.io.File(dir, name)
    }

/** Resultado de importar una copia (mismos casos que en Android). */
enum class DesktopBackupResult { OK, INVALID, UNSUPPORTED_VERSION }

/** Acceso mínimo a las preferencias; en tests se sustituye por un mapa. */
interface PrefsAccess {
    fun keys(): List<String>
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** [PrefsAccess] sobre el nodo `java.util.prefs` de [DesktopConfig]. */
class JavaPrefsAccess(
    private val node: Preferences = Preferences.userNodeForPackage(DesktopConfig::class.java),
) : PrefsAccess {
    override fun keys(): List<String> = node.keys().toList()
    override fun get(key: String): String? = node.get(key, null)
    override fun put(key: String, value: String) = node.put(key, value)
}

/**
 * Copia de seguridad de los ajustes del escritorio, con el mismo formato que
 * la de Android (`version`, `theme`, `language`, credenciales opt-in en
 * `serverUrl`/`apiKey`/`scraper`) para poder llevar ajustes de una a otra.
 *
 * - `desktopSettings`: todas las preferencias del nodo salvo credenciales e
 *   identificadores/estado de este equipo. `java.util.prefs` guarda texto, así
 *   que se copian tal cual.
 * - Al importar una copia de Android (`settings`, valores etiquetados) solo
 *   se aplican las preferencias que ambas plataformas comparten con el mismo
 *   nombre y significado; las rutas de Android no valen aquí.
 */
class DesktopSettingsBackup(
    private val prefs: PrefsAccess = JavaPrefsAccess(),
    /** Se llama si la importación cambió servidor o clave, para reconectar. */
    private val onServerChanged: () -> Unit = {},
) {
    companion object {
        const val SCHEMA_VERSION = 2

        private val BLOCKED = setOf(
            "server_url", "api_key", "client_device_id", "device_id",
            "last_sync_at", "last_sync_summary", "skipped_version",
            // Rutas a imágenes de este equipo: la copia no lleva los ficheros.
            "game_backgrounds",
        )

        /** Claves con el mismo nombre y tipo en Android (DataStore) y aquí. */
        internal val SHARED_WITH_ANDROID = setOf(
            "max_concurrent_downloads", "conflict_policy", "auto_physical_media",
            "media_download_enabled", "esde_export_enabled", "save_sync_enabled",
            LibraryPreferenceKeys.VIEW_MODE, LibraryPreferenceKeys.SORT,
            LibraryPreferenceKeys.SOURCE, LibraryPreferenceKeys.CARD_SIZE,
            LibraryPreferenceKeys.COVER_STYLE,
        )

        internal fun isBackedUp(key: String): Boolean = key !in BLOCKED && !key.startsWith("scraper_")
    }

    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    fun export(includeSecrets: Boolean): String {
        val payload = linkedMapOf<String, Any?>(
            "version" to SCHEMA_VERSION,
            "platform" to "desktop",
            "theme" to prefs.get("theme").orEmpty(),
            "language" to prefs.get("language").orEmpty(),
            // Mismo campo que la copia de Android.
            "launcherBackground" to (prefs.get("launcher_background") ?: "blur"),
            "desktopSettings" to prefs.keys().filter(::isBackedUp).sorted()
                .mapNotNull { key -> prefs.get(key)?.let { key to it } }.toMap(),
        )
        if (includeSecrets) {
            payload["serverUrl"] = prefs.get("server_url").orEmpty()
            payload["apiKey"] = prefs.get("api_key").orEmpty()
            payload["scraper"] = mapOf(
                "steamGridDbKey" to prefs.get("scraper_sgdb_key").orEmpty(),
                "ssUser" to prefs.get("scraper_ss_user").orEmpty(),
                "ssPassword" to prefs.get("scraper_ss_password").orEmpty(),
            )
        }
        return adapter.toJson(payload)
    }

    fun import(json: String): DesktopBackupResult {
        val data = runCatching { adapter.fromJson(json) }.getOrNull() ?: return DesktopBackupResult.INVALID
        val version = (data["version"] as? Number)?.toInt() ?: return DesktopBackupResult.INVALID
        if (version > SCHEMA_VERSION) return DesktopBackupResult.UNSUPPORTED_VERSION

        (data["desktopSettings"] as? Map<*, *>)?.forEach { (k, v) ->
            val key = k as? String ?: return@forEach
            val value = v as? String ?: return@forEach
            if (isBackedUp(key)) prefs.put(key, value)
        }
        (data["settings"] as? Map<*, *>)?.forEach { (k, v) ->
            val key = k as? String ?: return@forEach
            if (key !in SHARED_WITH_ANDROID) return@forEach
            // Valores etiquetados de Android: `s:`, `b:`, `i:`, `l:`, `f:`.
            val tagged = v as? String ?: return@forEach
            val tag = tagged.substringBefore(':', "")
            val value = tagged.substringAfter(':', "")
            val valid = when (tag) {
                "s" -> true
                "b" -> value.toBooleanStrictOrNull() != null
                "i" -> value.toIntOrNull() != null
                else -> false
            }
            if (valid) prefs.put(key, value)
        }
        (data["theme"] as? String)?.let { prefs.put("theme", it) }
        (data["language"] as? String)?.let { prefs.put("language", it) }
        (data["launcherBackground"] as? String)
            ?.takeIf { it in com.davidadrianrg.atril.core.media.LauncherBackdrop.MODES }
            ?.let { prefs.put("launcher_background", it) }

        val url = (data["serverUrl"] as? String)?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        val key = (data["apiKey"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        url?.let { prefs.put("server_url", it) }
        key?.let { prefs.put("api_key", it) }
        (data["scraper"] as? Map<*, *>)?.let { s ->
            prefs.put("scraper_sgdb_key", (s["steamGridDbKey"] as? String).orEmpty().trim())
            prefs.put("scraper_ss_user", (s["ssUser"] as? String).orEmpty().trim())
            prefs.put("scraper_ss_password", (s["ssPassword"] as? String).orEmpty().trim())
        }
        if (url != null || key != null) onServerChanged()
        return DesktopBackupResult.OK
    }
}
