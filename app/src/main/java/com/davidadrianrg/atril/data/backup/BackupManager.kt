package com.davidadrianrg.atril.data.backup

import android.content.Context
import com.davidadrianrg.atril.data.emulators.CustomEmulatorStore
import com.davidadrianrg.atril.data.local.SettingsDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.flow.first

/** Resultado de importar una copia: la UI distingue cada caso. */
enum class BackupImportResult { OK, INVALID, UNSUPPORTED_VERSION }

/** Copia de seguridad legible de los ajustes y emuladores manuales. */
class BackupManager(
    private val context: Context,
    private val settings: SettingsDataStore = SettingsDataStore.get(context),
    /** Se llama tras importar credenciales para que el cliente HTTP use las nuevas. */
    private val onServerChanged: (serverUrl: String, apiKey: String) -> Unit = { _, _ -> },
) {
    companion object {
        /** Versión del esquema que escribe esta build; se aceptan las <= a ella. */
        const val SCHEMA_VERSION = 2
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    suspend fun export(includeSecrets: Boolean): String {
        val custom = CustomEmulatorStore(File(context.filesDir, "custom_emulators.json")).all()
        val payload = linkedMapOf<String, Any?>(
            "version" to SCHEMA_VERSION,
            "theme" to settings.theme.first(),
            "launcherBackground" to settings.launcherBackground.first(),
            "launcherBlur" to settings.launcherBlur.first(),
            "language" to settings.language.first(),
            "settings" to settings.exportPreferences(),
            "customEmulators" to custom.map {
                mapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "repoUrl" to it.repoUrl,
                    "packageName" to it.packageName,
                    "apkFilterRegEx" to it.apkFilterRegEx,
                    "includePrereleases" to it.includePrereleases,
                )
            },
        )
        if (includeSecrets) {
            val cfg = settings.settings.first()
            payload["serverUrl"] = cfg.serverUrl
            payload["apiKey"] = settings.getApiKeyBlocking()
            val creds = settings.scraperCredentials.first()
            payload["scraper"] = mapOf(
                "steamGridDbKey" to creds.steamGridDbKey,
                "ssUser" to creds.ssUser,
                "ssPassword" to creds.ssPassword,
            )
        }
        return mapAdapter.toJson(payload)
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun import(json: String): BackupImportResult = runCatching {
        val data = mapAdapter.fromJson(json) ?: return BackupImportResult.INVALID
        // Un JSON sin versión no es una copia de Atril; una versión mayor
        // viene de una build más nueva y podría interpretarse mal.
        val version = (data["version"] as? Number)?.toInt() ?: return BackupImportResult.INVALID
        if (version > SCHEMA_VERSION) return BackupImportResult.UNSUPPORTED_VERSION
        // Snapshot genérico (versión 2+): ajustes de sync, descargas, biblioteca,
        // overrides de emulador/core, vínculos y fondos por juego.
        @Suppress("UNCHECKED_CAST")
        (data["settings"] as? Map<String, String>)?.let { settings.importPreferences(it) }
        (data["theme"] as? String)?.let { settings.setTheme(it) }
        (data["launcherBackground"] as? String)?.let { settings.setLauncherBackground(it) }
        (data["launcherBlur"] as? Number)?.let { settings.setLauncherBlur(it.toFloat()) }
        (data["language"] as? String)?.let { settings.setLanguage(it) }
        val newUrl = (data["serverUrl"] as? String)?.takeIf { it.isNotBlank() }
        val newKey = (data["apiKey"] as? String)?.takeIf { it.isNotBlank() }
        newUrl?.let { settings.setServerUrl(it) }
        newKey?.let { settings.setApiKey(it) }
        if (newUrl != null || newKey != null) {
            if (newUrl != null && newKey != null) settings.setLocalOnly(false)
            val cfg = settings.settings.first()
            if (cfg.serverUrl.isNotBlank() && cfg.apiKey.isNotBlank()) {
                onServerChanged(cfg.serverUrl, cfg.apiKey)
            }
        }
        (data["scraper"] as? Map<String, Any?>)?.let { scraper ->
            settings.setScraperCredentials(
                com.davidadrianrg.atril.core.localgames.ScraperCredentials(
                    steamGridDbKey = scraper["steamGridDbKey"] as? String ?: "",
                    ssUser = scraper["ssUser"] as? String ?: "",
                    ssPassword = scraper["ssPassword"] as? String ?: "",
                ),
            )
        }
        (data["customEmulators"] as? List<Map<String, Any?>>)?.let { list ->
            val store = CustomEmulatorStore(File(context.filesDir, "custom_emulators.json"))
            for (item in list) {
                val url = item["repoUrl"] as? String ?: continue
                store.add(
                    repoUrl = url,
                    name = item["name"] as? String ?: "",
                    regex = item["apkFilterRegEx"] as? String ?: "",
                    prereleases = item["includePrereleases"] as? Boolean ?: false,
                )
                (item["packageName"] as? String)?.let { pkg ->
                    val id = "custom:" + url.substringAfter("github.com/").trim('/')
                    store.setPackageName(id, pkg)
                }
            }
        }
        BackupImportResult.OK
    }.getOrDefault(BackupImportResult.INVALID)
}
