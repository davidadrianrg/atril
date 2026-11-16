package com.davidadrianrg.atril.data.repository

import com.davidadrianrg.atril.BuildConfig
import com.davidadrianrg.atril.core.library.PhysicalMedia
import com.davidadrianrg.atril.core.media.ImageFileDownloader
import com.davidadrianrg.atril.data.local.dao.RomMetadataCacheDao
import com.davidadrianrg.atril.data.local.entity.DownloadedRomEntity
import com.davidadrianrg.atril.data.local.entity.RomMetadataCacheEntity
import com.davidadrianrg.atril.domain.model.IgdbMetadata
import com.davidadrianrg.atril.domain.model.Rom
import com.davidadrianrg.atril.domain.model.RomFile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Caché persistente de metadatos y carátulas de los juegos descargados (F1).
 *
 * Play publica primero lo que hay en Room; sin servidor, esta caché aporta el
 * nombre, los metadatos de la línea de información y la carátula en disco, así
 * que la carta sale completa sin red. Con servidor, se refresca en segundo
 * plano y la carátula solo se vuelve a bajar si cambia su URL de origen o el
 * fichero ha desaparecido.
 *
 * Decisión: tabla Room (`rom_metadata_cache`, migración 8 → 9) y no un JSON
 * aparte: la fuente de verdad de lo descargado ya es Room, se borra en la
 * misma base que `downloaded_roms` y Play la observa como un `Flow` más.
 */
class OfflineShelfCache(
    private val dao: RomMetadataCacheDao,
    private val coverDir: File,
    private val coverFetcher: CoverFetcher = httpCoverFetcher(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Descarga [url] a [target]; true si el fichero quedó escrito y es una imagen. */
    fun interface CoverFetcher {
        fun fetch(url: String, target: File): Boolean
    }

    /** Serializa refrescos y limpiezas: dos pasadas no bajan la misma carátula. */
    private val mutex = Mutex()

    val entries: Flow<Map<Int, RomMetadataCacheEntity>> =
        dao.observeAll().map { rows -> rows.associateBy { it.romId } }.distinctUntilChanged()

    suspend fun snapshot(): Map<Int, RomMetadataCacheEntity> = withContext(Dispatchers.IO) {
        dao.getAll().associateBy { it.romId }
    }

    /**
     * Refresco en el ámbito propio de la caché: no se cancela si Play relanza
     * la hidratación (p. ej. al terminar una descarga) a mitad de una carátula.
     */
    fun rememberAsync(roms: Collection<Rom>): Job = scope.launch { remember(roms) }

    /** Guarda los metadatos de [roms] (datos del servidor) y baja las carátulas que falten. */
    suspend fun remember(roms: Collection<Rom>) {
        if (roms.isEmpty()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val existing = snapshot()
                val permits = Semaphore(COVER_PARALLELISM)
                val changed = coroutineScope {
                    roms.distinctBy { it.id }.map { rom ->
                        async { permits.withPermit { merge(rom, existing[rom.id]) } }
                    }.awaitAll()
                }.filterNotNull()
                if (changed.isNotEmpty()) dao.upsertAll(changed)
            }
        }
    }

    /** Olvida lo que ya no está descargado (fila y carátula). */
    suspend fun retain(romIds: Set<Int>) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val stale = dao.getAll().filter { it.romId !in romIds }
                if (stale.isEmpty()) return@withContext
                stale.forEach { row -> row.coverPath?.let { File(it).delete() } }
                dao.deleteAll(stale.map { it.romId })
            }
        }
    }

    /** Fila nueva o actualizada; null si no ha cambiado nada (no se reescribe). */
    private fun merge(rom: Rom, old: RomMetadataCacheEntity?): RomMetadataCacheEntity? {
        var coverSource = old?.coverSource
        var coverPath = old?.coverPath?.takeIf { File(it).isFile }
        val source = remoteCoverUrl(rom)
        if (source != null && (source != coverSource || coverPath == null)) {
            val target = File(coverDir, "${rom.id}.${PhysicalMedia.cacheExtension(source)}")
            if (runCatching { coverFetcher.fetch(source, target) }.getOrDefault(false) && target.isFile) {
                if (coverPath != null && coverPath != target.absolutePath) File(coverPath).delete()
                coverSource = source
                coverPath = target.absolutePath
            }
            // Si falla se conserva la carátula anterior: mejor una vieja que ninguna.
        }
        if (coverPath == null) coverSource = null
        val fresh = RomMetadataCacheEntity(
            romId = rom.id,
            name = rom.name,
            platformSlug = rom.platformSlug,
            summary = rom.summary,
            genres = rom.genres.joinToString(SEPARATOR),
            releaseDate = rom.igdbMetadata?.firstReleaseDate,
            rating = rom.igdbMetadata?.totalRating,
            igdbId = rom.igdbId,
            fileNames = rom.files.map { it.filename }.joinToString(SEPARATOR),
            coverSource = coverSource,
            coverPath = coverPath,
            updatedAt = old?.updatedAt ?: 0L,
        )
        return if (old != null && fresh == old) null else fresh.copy(updatedAt = clock())
    }

    companion object {
        const val COVER_PARALLELISM = 4
        private const val SEPARATOR = "\n"

        /** Carátula que pinta Play en modo carta: la grande (asset local de RomM) y si no la pequeña. */
        fun remoteCoverUrl(rom: Rom): String? =
            listOfNotNull(rom.coverUrlLarge, rom.coverUrlSmall)
                .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }

        /**
         * Rom mínimo para un juego descargado sin respuesta del servidor:
         * Room aporta la identidad y el fichero; la caché, lo que pinta la carta.
         */
        fun offlineRom(entity: DownloadedRomEntity, cached: RomMetadataCacheEntity?): Rom {
            val cover = cached?.coverPath?.let { "file://$it" }
            val metadata = if (cached?.releaseDate != null || cached?.rating != null) {
                IgdbMetadata(totalRating = cached.rating, firstReleaseDate = cached.releaseDate)
            } else {
                null
            }
            return Rom(
                id = entity.romId,
                name = cached?.name?.takeIf { it.isNotBlank() } ?: entity.name,
                fileName = entity.fileName,
                fileSizeBytes = entity.fileSizeBytes,
                platformId = entity.platformId,
                platformSlug = entity.platformSlug,
                coverUrlSmall = cover,
                coverUrlLarge = cover,
                files = cached?.fileNames.orEmpty().split(SEPARATOR).filter { it.isNotBlank() }
                    .map { RomFile(it, 0L) },
                isMulti = false,
                revision = null,
                regions = emptyList(),
                genres = cached?.genres.orEmpty().split(SEPARATOR).filter { it.isNotBlank() },
                summary = cached?.summary,
                igdbId = cached?.igdbId,
                igdbMetadata = metadata,
            )
        }

        /**
         * Sustituye la carátula remota por la cacheada cuando es la misma (o
         * no hay remota): Coil la lee de disco y no vuelve a la red. Si el
         * servidor cambió la carátula se muestra la nueva hasta que se cachee.
         */
        fun Rom.withOfflineCover(
            cached: RomMetadataCacheEntity?,
            exists: (String) -> Boolean = { File(it).isFile },
        ): Rom {
            val path = cached?.coverPath ?: return this
            val remote = remoteCoverUrl(this)
            val alreadyLocal = coverUrlLarge == "file://$path"
            if (alreadyLocal) return this
            if (remote != null && remote != cached.coverSource) return this
            if (remote == null && (coverUrlLarge != null || coverUrlSmall != null)) return this
            if (!exists(path)) return this
            val local = "file://$path"
            return copy(coverUrlLarge = local, coverUrlSmall = local)
        }

        fun httpCoverFetcher(): CoverFetcher {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", "Atril-Android/${BuildConfig.VERSION_NAME}")
                            .header("Accept", "image/*")
                            .build(),
                    )
                }
                .build()
            // Sin Authorization: url_cover puede apuntar a un tercero (IGDB) y
            // los assets de RomM se sirven sin clave (Coil ya los pide así).
            return CoverFetcher { url, target -> ImageFileDownloader.download(client, url, target).isSuccess }
        }
    }
}
