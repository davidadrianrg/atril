package com.davidadrianrg.atril.data.emulators

import com.davidadrianrg.atril.core.emulators.EmulatorEntry
import com.davidadrianrg.atril.core.emulators.PackSettings
import com.davidadrianrg.atril.core.emulators.SelectedRelease
import com.davidadrianrg.atril.core.emulators.VersionCompare
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fuentes que no son la API de GitHub: páginas HTML con enlaces a APKs y
 * endpoints JSON propios (Eden). Réplica acotada del comportamiento de
 * Obtainium para las entradas del pack que no viven en GitHub.
 */
class HtmlReleaseResolver(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun resolve(entry: EmulatorEntry): SelectedRelease? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                entry.isDirectApk -> directApk(entry)
                entry.isJsonSource -> jsonSource(entry)
                else -> htmlSource(entry)
            }
        }.getOrNull()
    }

    private fun directApk(entry: EmulatorEntry): SelectedRelease {
        val name = assetName(entry.repoUrl)
        return SelectedRelease(
            version = "latest",
            assetName = name,
            downloadUrl = entry.repoUrl,
            sizeBytes = 0,
            tagName = "latest",
        )
    }

    private fun jsonSource(entry: EmulatorEntry): SelectedRelease? {
        val body = fetch(entry.repoUrl)
        val urls = Regex("""https?://[^\s"'<>\\]+""")
            .findAll(body)
            .map { it.value.trimEnd(',', ')', ']', '"') }
            .filter { it.substringBefore('?').endsWith(".apk", ignoreCase = true) }
            .toList()
        var candidates = filter(urls, entry.settings.customLinkFilterRegex, entry.settings.matchLinksOutsideATags)
        if (candidates.isEmpty()) candidates = urls
        if (candidates.isEmpty()) return null
        // Eden publica standard/optimized/legacy/chromeos: preferir standard.
        val chosen = candidates.firstOrNull { it.contains("standard", ignoreCase = true) }
            ?: candidates.first()
        val version = extractVersion(entry, chosen, body).ifBlank { "latest" }
        return SelectedRelease(
            version = version,
            assetName = assetName(chosen),
            downloadUrl = chosen,
            sizeBytes = 0,
            tagName = version,
        )
    }

    private fun htmlSource(entry: EmulatorEntry): SelectedRelease? {
        var current = entry.repoUrl
        var page = fetch(current)
        for (step in entry.settings.intermediateLink) {
            val links = filter(
                extractLinks(page, current, entry.settings.matchLinksOutsideATags),
                step.customLinkFilterRegex,
                entry.settings.matchLinksOutsideATags,
            )
            val next = pick(links, step.skipSort, step.reverseSort, step.sortByLastLinkSegment) ?: return null
            current = next
            page = fetch(current)
        }
        var candidates = filter(
            extractLinks(page, current, entry.settings.matchLinksOutsideATags),
            entry.settings.customLinkFilterRegex,
            entry.settings.matchLinksOutsideATags,
        )
        if (candidates.isEmpty() && entry.settings.customLinkFilterRegex.isBlank()) {
            candidates = extractLinks(page, current, true)
                .filter { it.substringBefore('?').endsWith(".apk", ignoreCase = true) }
        }
        if (candidates.isEmpty()) return null
        val chosen = pick(
            candidates,
            entry.settings.skipSort,
            entry.settings.reverseSort,
            entry.settings.sortByLastLinkSegment,
        ) ?: return null
        val version = extractVersion(entry, chosen, page).ifBlank { "latest" }
        return SelectedRelease(
            version = version,
            assetName = assetName(chosen),
            downloadUrl = chosen,
            sizeBytes = 0,
            tagName = version,
        )
    }

    /** Elige un enlace; por defecto el de versión más alta. */
    private fun pick(
        links: List<String>,
        skipSort: Boolean,
        reverseSort: Boolean,
        byLastSegment: Boolean,
    ): String? {
        if (links.isEmpty()) return null
        val sorted = if (skipSort) links else links.sortedWith { a, b ->
            val x = versionKey(a, byLastSegment)
            val y = versionKey(b, byLastSegment)
            when {
                VersionCompare.isNewer(x, y) -> -1
                VersionCompare.isNewer(y, x) -> 1
                else -> 0
            }
        }
        return if (reverseSort) sorted.last() else sorted.first()
    }

    private fun versionKey(url: String, byLastSegment: Boolean): String {
        val path = url.substringBefore('?').trimEnd('/')
        return if (byLastSegment) path.substringAfterLast('/') else path
    }

    private fun extractLinks(html: String, base: String, outsideATags: Boolean): List<String> {
        val found = LinkedHashSet<String>()
        Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { found += it.groupValues[1] }
        if (outsideATags) {
            Regex("""https?://[^\s"'<>\\]+""")
                .findAll(html)
                .forEach { found += it.value }
        }
        return found.mapNotNull { raw ->
            val text = raw.trim()
            if (text.startsWith("http://") || text.startsWith("https://")) {
                text
            } else if (text.startsWith("/") || !text.contains("://")) {
                runCatching { URI(base).resolve(text).toString() }.getOrNull()
            } else {
                null
            }
        }.map { it.substringBefore('#') }.filter { it.isNotBlank() }
    }

    private fun filter(links: List<String>, regex: String, matchText: Boolean): List<String> {
        if (regex.isBlank()) return links
        val rx = runCatching { Regex(regex) }.getOrNull() ?: return links
        return links.filter { link -> rx.containsMatchIn(link) }
    }

    private fun extractVersion(entry: EmulatorEntry, link: String, page: String): String {
        val settings: PackSettings = entry.settings
        val pattern = settings.versionExtractionRegEx.trim()
        if (pattern.isEmpty()) return ""
        val source = if (settings.versionExtractWholePage) page else link
        val match = runCatching { Regex(pattern).find(source) }.getOrNull() ?: return ""
        val group = settings.matchGroupToUse.trim()
        val raw = if (group.isBlank()) {
            match.value
        } else {
            Regex("""\$(\d+)""").replace(group) { token ->
                val index = token.groupValues[1].toIntOrNull() ?: -1
                if (index in 0 until match.groups.size) match.groups[index]?.value.orEmpty() else ""
            }
        }
        return raw.trim().removePrefix("v").removePrefix("V").trim('_', '-', '.', '/')
    }

    private fun assetName(url: String): String =
        url.substringBefore('?').substringAfterLast('/').ifBlank { "emulator.apk" }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Obtainium/1.0")
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
