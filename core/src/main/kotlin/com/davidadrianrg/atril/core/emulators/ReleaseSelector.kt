package com.davidadrianrg.atril.core.emulators

import com.squareup.moshi.Json

data class GhAsset(
    @Json(name = "name") val name: String,
    @Json(name = "browser_download_url") val downloadUrl: String,
    @Json(name = "size") val size: Long = 0,
)

data class GhRelease(
    @Json(name = "tag_name") val tagName: String = "",
    @Json(name = "name") val name: String? = null,
    @Json(name = "prerelease") val prerelease: Boolean = false,
    @Json(name = "draft") val draft: Boolean = false,
    @Json(name = "published_at") val publishedAt: String? = null,
    @Json(name = "assets") val assets: List<GhAsset> = emptyList(),
)

/** Release + APK elegidos para una entrada del pack. */
data class SelectedRelease(
    val version: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val tagName: String,
)

/**
 * Réplica acotada de la selección de releases de Obtainium: descarta drafts y
 * (salvo que el pack lo pida) prereleases, filtra assets por la regex del pack,
 * prefiere la arquitectura del dispositivo y extrae la versión de la etiqueta.
 * Es puro y total para poder probarlo sin red.
 */
object ReleaseSelector {

    fun select(
        releases: List<GhRelease>,
        settings: PackSettings,
        deviceAbi: String = "arm64-v8a",
        dualScreen: Boolean = false,
    ): SelectedRelease? {
        val usable = releases.filter { !it.draft && (settings.includePrereleases || !it.prerelease) }
        for (release in usable) {
            val asset = pickAsset(release, settings, deviceAbi, dualScreen) ?: continue
            return SelectedRelease(
                version = versionOf(release, settings),
                assetName = asset.name,
                downloadUrl = asset.downloadUrl,
                sizeBytes = asset.size,
                tagName = release.tagName,
            )
        }
        return null
    }

    fun versionOf(release: GhRelease, settings: PackSettings): String {
        val raw = if (settings.releaseTitleAsVersion) {
            release.name?.takeIf { it.isNotBlank() } ?: release.tagName
        } else {
            release.tagName
        }
        val regex = settings.versionExtractionRegEx
        if (regex.isBlank()) return raw.trim()
        return runCatching {
            val match = Regex(regex).find(raw) ?: return@runCatching raw
            match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: match.value
        }.getOrDefault(raw).trim()
    }

    fun pickAsset(
        release: GhRelease,
        settings: PackSettings,
        deviceAbi: String,
        dualScreen: Boolean = false,
    ): GhAsset? {
        val regex = runCatching { settings.apkFilterRegEx.takeIf { it.isNotBlank() }?.let(::Regex) }.getOrNull()
        val apks = release.assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) &&
                (regex == null || regex.containsMatchIn(asset.name))
        }
        if (apks.isEmpty()) return null
        // Variante de pantalla: si la release publica builds "dual", se elige
        // según el perfil; el resto de assets queda como respaldo.
        val dualAssets = apks.filter { it.name.contains("dual", ignoreCase = true) }
        val variantOrdered = if (dualAssets.isEmpty()) {
            apks
        } else if (dualScreen) {
            dualAssets + apks.filterNot { it in dualAssets }
        } else {
            apks.filterNot { it in dualAssets } + dualAssets
        }
        val ordered = if (settings.autoApkFilterByArch) {
            val abiTokens = abiTokens(deviceAbi)
            val preferred = variantOrdered.filter { asset -> abiTokens.any { asset.name.contains(it, ignoreCase = true) } }
            val universal = variantOrdered.filter { asset ->
                asset.name.contains("universal", ignoreCase = true) &&
                    abiTokens.none { asset.name.contains(it, ignoreCase = true) }
            }
            preferred + universal + variantOrdered.filterNot { it in preferred || it in universal }
        } else {
            variantOrdered
        }
        return ordered.getOrNull(settings.preferredApkIndex.coerceAtLeast(0)) ?: ordered.first()
    }

    private fun abiTokens(abi: String): List<String> = when (abi) {
        "arm64-v8a" -> listOf("arm64-v8a", "arm64", "aarch64")
        "armeabi-v7a" -> listOf("armeabi-v7a", "armv7", "arm")
        "x86_64" -> listOf("x86_64", "x64")
        else -> listOf(abi)
    }
}
