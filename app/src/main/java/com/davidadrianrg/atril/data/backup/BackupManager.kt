package com.davidadrianrg.atril.data.backup

import android.content.Context
import com.davidadrianrg.atril.data.emulators.CustomEmulatorStore
import com.davidadrianrg.atril.data.local.SettingsDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.flow.first

/** Copia de seguridad legible de los ajustes y emuladores manuales. */
class BackupManager(
    private val context: Context,
    private val settings: SettingsDataStore = SettingsDataStore(context),
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    suspend fun export(includeSecrets: Boolean): String {
        val custom = CustomEmulatorStore(File(context.filesDir, "custom_emulators.json")).all()
        val payload = linkedMapOf<String, Any?>(
            "version" to 1,
            "theme" to settings.theme.first(),
            "launcherBackground" to settings.launcherBackground.first(),
            "launcherBlur" to settings.launcherBlur.first(),
            "language" to settings.language.first(),
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
    suspend fun import(json: String): Boolean = runCatching {
        val data = mapAdapter.fromJson(json) ?: return false
        (data["theme"] as? String)?.let { settings.setTheme(it) }
        (data["launcherBackground"] as? String)?.let { settings.setLauncherBackground(it) }
        (data["launcherBlur"] as? Number)?.let { settings.setLauncherBlur(it.toFloat()) }
        (data["language"] as? String)?.let { settings.setLanguage(it) }
        (data["serverUrl"] as? String)?.takeIf { it.isNotBlank() }?.let { settings.setServerUrl(it) }
        (data["apiKey"] as? String)?.takeIf { it.isNotBlank() }?.let { settings.setApiKey(it) }
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
        true
    }.getOrDefault(false)
}
