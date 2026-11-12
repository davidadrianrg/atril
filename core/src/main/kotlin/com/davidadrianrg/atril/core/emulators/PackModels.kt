package com.davidadrianrg.atril.core.emulators

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** Ajustes de Obtainium que afectan a la selección de release/APK. */
data class PackSettings(
    val includePrereleases: Boolean,
    val apkFilterRegEx: String,
    val versionExtractionRegEx: String,
    val releaseTitleAsVersion: Boolean,
    val preferredApkIndex: Int,
    val autoApkFilterByArch: Boolean,
    val about: String,
)

/** Una app del pack de Obtainium, ya normalizada. */
data class EmulatorEntry(
    val id: String,
    val name: String,
    val author: String,
    val repoUrl: String,
    val categories: List<String>,
    val settings: PackSettings,
) {
    val owner: String get() = repoUrl.trimEnd('/').substringAfter("github.com/").substringBefore('/')
    val repo: String get() = repoUrl.trimEnd('/').substringAfter("github.com/").substringAfter('/').substringBefore('/')

    /** Variante pensada para dispositivos de doble pantalla. */
    val isDualScreen: Boolean
        get() = name.contains("(ds)", ignoreCase = true) ||
            name.contains("dual", ignoreCase = true) ||
            settings.about.contains("dual-screen", ignoreCase = true) ||
            settings.about.contains("dual screen", ignoreCase = true)
}

internal data class PackFileDto(
    @Json(name = "apps") val apps: List<PackAppDto> = emptyList(),
)

internal data class PackAppDto(
    @Json(name = "id") val id: String,
    @Json(name = "url") val url: String,
    @Json(name = "author") val author: String? = null,
    @Json(name = "name") val name: String,
    @Json(name = "additionalSettings") val additionalSettings: String? = null,
    @Json(name = "categories") val categories: List<String>? = null,
)

internal data class PackSettingsDto(
    @Json(name = "includePrereleases") val includePrereleases: Boolean = false,
    @Json(name = "apkFilterRegEx") val apkFilterRegEx: String = "",
    @Json(name = "versionExtractionRegEx") val versionExtractionRegEx: String = "",
    @Json(name = "releaseTitleAsVersion") val releaseTitleAsVersion: Boolean = false,
    @Json(name = "preferredApkIndex") val preferredApkIndex: Int = 0,
    @Json(name = "autoApkFilterByArch") val autoApkFilterByArch: Boolean = true,
    @Json(name = "about") val about: String = "",
)

/**
 * Parser del JSON de Obtainium (obtainium-emulation-pack-latest.json).
 * `additionalSettings` llega como string JSON anidado; se tolera cualquier
 * valor ausente o inválido para no perder el resto del catálogo.
 */
object PackParser {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val fileAdapter = moshi.adapter(PackFileDto::class.java)
    private val settingsAdapter = moshi.adapter(PackSettingsDto::class.java)
    private val listAdapter = moshi.adapter<List<PackAppDto>>(
        Types.newParameterizedType(List::class.java, PackAppDto::class.java),
    )

    fun parse(json: String): List<EmulatorEntry> {
        val apps = runCatching { fileAdapter.fromJson(json)?.apps }
            .getOrNull()
            ?: runCatching { listAdapter.fromJson(json) }
                .getOrNull()
            ?: return emptyList()

        return apps.mapNotNull { app ->
            val settings = runCatching {
                app.additionalSettings?.let { settingsAdapter.fromJson(it) }
            }.getOrNull() ?: PackSettingsDto()
            if (!app.url.contains("github.com/")) return@mapNotNull null
            EmulatorEntry(
                id = app.id,
                name = app.name,
                author = app.author.orEmpty(),
                repoUrl = app.url,
                categories = app.categories ?: listOf("Emulator"),
                settings = PackSettings(
                    includePrereleases = settings.includePrereleases,
                    apkFilterRegEx = settings.apkFilterRegEx,
                    versionExtractionRegEx = settings.versionExtractionRegEx,
                    releaseTitleAsVersion = settings.releaseTitleAsVersion,
                    preferredApkIndex = settings.preferredApkIndex,
                    autoApkFilterByArch = settings.autoApkFilterByArch,
                    about = settings.about,
                ),
            )
        }
    }

    /**
     * El pack incluye variantes single y dual screen con el mismo id (p. ej.
     * Cemu y Cemu (DS)). Devuelve una entrada por id, eligiendo la variante.
     */
    fun forProfile(entries: List<EmulatorEntry>, dualScreen: Boolean): List<EmulatorEntry> {
        val byId = entries.groupBy { it.id }
        return byId.map { (_, variants) ->
            val dual = variants.filter { it.isDualScreen }
            val single = variants.filterNot { it.isDualScreen }
            when {
                dualScreen && dual.isNotEmpty() -> dual.first()
                !dualScreen && single.isNotEmpty() -> single.first()
                else -> variants.first()
            }
        }.sortedBy { it.name.lowercase() }
    }
}
