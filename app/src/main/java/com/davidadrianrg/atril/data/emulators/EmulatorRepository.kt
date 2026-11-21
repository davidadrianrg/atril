package com.davidadrianrg.atril.data.emulators

import android.content.Context
import android.content.pm.PackageManager
import com.davidadrianrg.atril.core.emulators.EmulatorEntry
import com.davidadrianrg.atril.core.emulators.GhRelease
import com.davidadrianrg.atril.core.emulators.PackParser
import com.davidadrianrg.atril.core.emulators.ReleaseSelector
import com.davidadrianrg.atril.core.emulators.SelectedRelease
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Catálogo de emuladores del pack de Obtainium + consulta de releases de GitHub
 * y descarga del APK. No instala: eso lo hace [com.davidadrianrg.atril.data.update.ApkInstallHelper].
 */
class EmulatorRepository(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
    private val packUrl: String = PACK_URL,
) {

    private val moshi by lazy { Moshi.Builder().add(KotlinJsonAdapterFactory()).build() }
    private val releasesAdapter by lazy {
        moshi.adapter<List<GhRelease>>(
            Types.newParameterizedType(List::class.java, GhRelease::class.java),
        )
    }

    private val packFile = File(context.filesDir, "emulator_pack.json")
    private val packEtagFile = File(context.filesDir, "emulator_pack.etag")
    private val customStore = CustomEmulatorStore(File(context.filesDir, "custom_emulators.json"))
    private val packageMap = PackageMapStore(File(context.filesDir, "package_names.json"))
    private val htmlResolver = HtmlReleaseResolver(client)

    /** Catálogo cacheado; si no hay caché o [force], se descarga del repositorio. */
    suspend fun loadPack(force: Boolean = false): List<EmulatorEntry> = withContext(Dispatchers.IO) {
        val pack = if (!force && packFile.isFile) {
            val cached = runCatching { PackParser.parse(packFile.readText()) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) cached else downloadPack()
        } else {
            downloadPack()
        }
        pack + customEntries()
    }

    private fun downloadPack(): List<EmulatorEntry> {
        // El ETag solo vale si la caché que describe se puede usar: con una
        // caché corrupta un 304 dejaría el catálogo vacío para siempre.
        val cached = packFile.takeIf { it.isFile }
            ?.let { runCatching { PackParser.parse(it.readText()) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
        val etag = packEtagFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        val request = Request.Builder()
            .url(packUrl)
            .header("User-Agent", "Atril")
            .apply { if (etag != null && cached != null) header("If-None-Match", etag) }
            .build()
        client.newCall(request).execute().use { response ->
            // 304: el pack no cambió; no se gasta ancho de banda ni cuota.
            if (response.code == 304 && cached != null) return cached
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            return PackParser.parse(body).also { entries ->
                if (entries.isNotEmpty()) {
                    packFile.writeText(body)
                    // Sin ETag nuevo, el anterior describiría otro contenido.
                    val newTag = response.header("ETag")
                    if (newTag.isNullOrBlank()) packEtagFile.delete() else packEtagFile.writeText(newTag)
                }
            }
        }
    }

    /** Emuladores añadidos a mano, normalizados al mismo modelo que el pack. */
    fun customEntries(): List<EmulatorEntry> = customStore.all().map { custom ->
        EmulatorEntry(
            id = custom.id,
            name = custom.name,
            author = custom.repoUrl.substringAfter("github.com/").substringBefore('/'),
            repoUrl = custom.repoUrl,
            categories = listOf("Emulator"),
            settings = com.davidadrianrg.atril.core.emulators.PackSettings(
                includePrereleases = custom.includePrereleases,
                apkFilterRegEx = custom.apkFilterRegEx,
                versionExtractionRegEx = "",
                releaseTitleAsVersion = false,
                preferredApkIndex = 0,
                autoApkFilterByArch = true,
                about = custom.repoUrl,
            ),
        )
    }

    fun addCustom(repoUrl: String, name: String, regex: String, prereleases: Boolean): EmulatorEntry {
        customStore.add(repoUrl, name, regex, prereleases)
        return customEntries().last { it.repoUrl == repoUrl.trim().removeSuffix("/").removeSuffix(".git") }
    }

    /** Nombre de paquete real de la entrada (custom: el aprendido del APK). */
    fun packageNameOf(entry: EmulatorEntry): String =
        packageMap.get(entry.id)
            ?: customStore.get(entry.id)?.packageName
            ?: KNOWN_PACKAGES[entry.name]
            ?: entry.id

    /**
     * Paquete que debe declarar el APK descargado, o null si no se conoce con
     * certeza. El id del pack no siempre es el paquete real (variantes,
     * manuales, `custom:owner/repo.x`), así que solo se exige cuando se
     * aprendió de un APK, viene del usuario/tabla conocida o ya hay una app
     * instalada con ese id (actualizar a otro paquete crearía un duplicado).
     */
    fun expectedPackageOf(entry: EmulatorEntry): String? = resolveExpectedPackage(
        learned = packageMap.get(entry.id) ?: customStore.get(entry.id)?.packageName,
        known = KNOWN_PACKAGES[entry.name],
        id = entry.id,
        isInstalled = { installedVersionOf(it) != null },
    )

    /** Tras descargar un APK de un emulador manual, aprende su package name. */
    fun learnPackageName(entry: EmulatorEntry, apkFile: File): String? {
        val info = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.path,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.path, 0)
            }
        }.getOrNull() ?: return null
        val pkg = info.packageName
        packageMap.set(entry.id, pkg)
        if (customStore.get(entry.id) != null) customStore.setPackageName(entry.id, pkg)
        return pkg
    }

    fun installedVersion(entry: EmulatorEntry): String? = installedVersionOf(packageNameOf(entry))

    fun installedVersionOf(packageName: String): String? = try {
        val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Última release utilizable, o null si el repo no tiene APKs. Los fallos de
     * red y el límite anónimo de la API de GitHub se propagan como excepción
     * ([GitHubRateLimitException] en el segundo caso): la UI tiene que poder
     * distinguir «no hay release» de «no se pudo comprobar».
     */
    suspend fun latestRelease(
        entry: EmulatorEntry,
        dualScreen: Boolean = false,
    ): SelectedRelease? = withContext(Dispatchers.IO) {
        if (!entry.isGitHub) return@withContext htmlResolver.resolve(entry)
        val url = "https://api.github.com/repos/${entry.owner}/${entry.repo}/releases?per_page=20"
        val json = getText(url)
        val releases = runCatching { releasesAdapter.fromJson(json) }.getOrNull() ?: return@withContext null
        ReleaseSelector.select(releases, entry.settings, deviceAbi(), dualScreen)
    }

    /**
     * APKs entre los que el usuario puede elegir en la release a instalar
     * (como Obtainium). Null = sin ambigüedad (una sola opción o fuente HTML):
     * se puede instalar automático. Devuelve la release elegible y TODOS sus
     * candidatos ordenados por preferencia.
     */
    data class ReleaseOptions(val release: SelectedRelease, val alternatives: List<SelectedRelease>)

    suspend fun releaseOptions(
        entry: EmulatorEntry,
        dualScreen: Boolean = false,
    ): ReleaseOptions? = withContext(Dispatchers.IO) {
        if (!entry.isGitHub) return@withContext null
        val url = "https://api.github.com/repos/${entry.owner}/${entry.repo}/releases?per_page=20"
        val json = runCatching { getText(url) }.getOrNull() ?: return@withContext null
        val releases = runCatching { releasesAdapter.fromJson(json) }.getOrNull() ?: return@withContext null
        val usable = releases.filter { !it.draft && (entry.settings.includePrereleases || !it.prerelease) }
        for (release in usable) {
            val assets = ReleaseSelector.options(release, entry.settings, deviceAbi(), dualScreen)
            if (assets.isEmpty()) continue
            val chosen = assets.first()
            val selected = assets.map { asset ->
                SelectedRelease(
                    version = ReleaseSelector.versionOf(release, entry.settings),
                    assetName = asset.name,
                    downloadUrl = asset.downloadUrl,
                    sizeBytes = asset.size,
                    tagName = release.tagName,
                )
            }
            return@withContext ReleaseOptions(
                release = SelectedRelease(
                    version = ReleaseSelector.versionOf(release, entry.settings),
                    assetName = chosen.name,
                    downloadUrl = chosen.downloadUrl,
                    sizeBytes = chosen.size,
                    tagName = release.tagName,
                ),
                alternatives = selected,
            )
        }
        null
    }

    suspend fun download(
        release: SelectedRelease,
        expectedPackage: String? = null,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "emulator-apks").apply { mkdirs() }
        val target = File(dir, safeAssetName(release.assetName))
        val request = Request.Builder().url(release.downloadUrl).build()
        val call = client.newCall(request)
        // Cancelar la corrutina (el usuario quita el emulador de la cola)
        // corta también la lectura bloqueante de OkHttp.
        val cancelHook = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        } finally {
            cancelHook?.dispose()
        }
        if (expectedPackage != null) {
            val info = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageArchiveInfo(
                        target.path,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageArchiveInfo(target.path, 0)
                }
            }.getOrNull()
            if (info == null) {
                target.delete()
                error(com.davidadrianrg.atril.core.i18n.tr("emulators.apk_invalid"))
            }
            if (info.packageName != expectedPackage) {
                target.delete()
                error(com.davidadrianrg.atril.core.i18n.tr("emulators.apk_mismatch", info.packageName, expectedPackage))
            }
        }
        target
    }

    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Atril")
            .build()
        client.newCall(request).execute().use { response ->
            if ((response.code == 403 || response.code == 429) &&
                response.header("X-RateLimit-Remaining") == "0"
            ) {
                throw GitHubRateLimitException()
            }
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun deviceAbi(): String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    companion object {
        /** Packages que el pack no expone como id de paquete. */
        private val KNOWN_PACKAGES = mapOf(
            "RetroArch (AArch64)" to "com.retroarch.aarch64",
        )

        internal fun resolveExpectedPackage(
            learned: String?,
            known: String?,
            id: String,
            isInstalled: (String) -> Boolean,
        ): String? {
            learned?.takeIf { it.isNotBlank() }?.let { return it }
            known?.let { return it }
            val looksLikePackage = !id.startsWith("custom:") &&
                id.matches(Regex("""[A-Za-z][\w]*(\.[A-Za-z_][\w]*)+"""))
            return id.takeIf { looksLikePackage && isInstalled(it) }
        }

        const val PACK_URL =
            "https://raw.githubusercontent.com/RJNY/Obtainium-Emulation-Pack/main/obtainium-emulation-pack-latest.json"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * El nombre del APK sale de la URL o del asset remoto: nunca debe poder
         * salir del directorio de caché ni ser un nombre oculto/vacío.
         */
        internal fun safeAssetName(raw: String): String {
            val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                .trimStart('.')
            return cleaned.ifBlank { "emulator.apk" }
        }
    }
}

/** La API anónima de GitHub (60 peticiones/hora) se ha agotado. */
class GitHubRateLimitException : IllegalStateException(
    com.davidadrianrg.atril.core.i18n.tr("emulators.rate_limited"),
)
