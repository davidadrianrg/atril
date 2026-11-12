package com.davidadrianrg.atril.core.emulators

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** Un salto intermedio del scraping HTML de Obtainium. */
data class PackIntermediateLink(
    val customLinkFilterRegex: String,
    val skipSort: Boolean,
    val reverseSort: Boolean,
    val sortByLastLinkSegment: Boolean,
)

/** Ajustes de Obtainium que afectan a la selección de release/APK. */
data class PackSettings(
    val includePrereleases: Boolean,
    val apkFilterRegEx: String,
    val versionExtractionRegEx: String,
    val releaseTitleAsVersion: Boolean,
    val preferredApkIndex: Int,
    val autoApkFilterByArch: Boolean,
    val about: String,
    val customLinkFilterRegex: String = "",
    val intermediateLink: List<PackIntermediateLink> = emptyList(),
    val versionExtractWholePage: Boolean = false,
    val matchGroupToUse: String = "",
    val skipSort: Boolean = false,
    val reverseSort: Boolean = false,
    val sortByLastLinkSegment: Boolean = false,
    val matchLinksOutsideATags: Boolean = false,
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

    /** true si la fuente es un repositorio de GitHub (API de releases). */
    val isGitHub: Boolean get() = repoUrl.contains("github.com/")

    /** true si la fuente es un endpoint JSON (p. ej. Eden). */
    val isJsonSource: Boolean get() = !isGitHub && repoUrl.substringBefore('?').endsWith(".json")

    /** URL directa a un APK (sin scraping). */
    val isDirectApk: Boolean get() = !isGitHub && repoUrl.substringBefore('?').endsWith(".apk")

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
    @Json(name = "customLinkFilterRegex") val customLinkFilterRegex: String = "",
    @Json(name = "intermediateLink") val intermediateLink: List<PackIntermediateLinkDto> = emptyList(),
    @Json(name = "versionExtractWholePage") val versionExtractWholePage: Boolean = false,
    @Json(name = "matchGroupToUse") val matchGroupToUse: String = "",
    @Json(name = "skipSort") val skipSort: Boolean = false,
    @Json(name = "reverseSort") val reverseSort: Boolean = false,
    @Json(name = "sortByLastLinkSegment") val sortByLastLinkSegment: Boolean = false,
    @Json(name = "matchLinksOutsideATags") val matchLinksOutsideATags: Boolean = false,
)

internal data class PackIntermediateLinkDto(
    @Json(name = "customLinkFilterRegex") val customLinkFilterRegex: String = "",
    @Json(name = "skipSort") val skipSort: Boolean = false,
    @Json(name = "reverseSort") val reverseSort: Boolean = false,
    @Json(name = "sortByLastLinkSegment") val sortByLastLinkSegment: Boolean = false,
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
                    customLinkFilterRegex = settings.customLinkFilterRegex,
                    intermediateLink = settings.intermediateLink.map {
                        PackIntermediateLink(
                            customLinkFilterRegex = it.customLinkFilterRegex,
                            skipSort = it.skipSort,
                            reverseSort = it.reverseSort,
                            sortByLastLinkSegment = it.sortByLastLinkSegment,
                        )
                    },
                    versionExtractWholePage = settings.versionExtractWholePage,
                    matchGroupToUse = settings.matchGroupToUse,
                    skipSort = settings.skipSort,
                    reverseSort = settings.reverseSort,
                    sortByLastLinkSegment = settings.sortByLastLinkSegment,
                    matchLinksOutsideATags = settings.matchLinksOutsideATags,
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
