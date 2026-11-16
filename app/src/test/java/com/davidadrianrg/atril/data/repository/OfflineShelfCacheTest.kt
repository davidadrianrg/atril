package com.davidadrianrg.atril.data.repository

import com.davidadrianrg.atril.data.local.dao.RomMetadataCacheDao
import com.davidadrianrg.atril.data.local.entity.DownloadedRomEntity
import com.davidadrianrg.atril.data.local.entity.RomMetadataCacheEntity
import com.davidadrianrg.atril.data.repository.OfflineShelfCache.Companion.withOfflineCover
import com.davidadrianrg.atril.domain.model.IgdbMetadata
import com.davidadrianrg.atril.domain.model.Rom
import com.davidadrianrg.atril.domain.model.RomFile
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** DAO en memoria con la misma semántica (REPLACE por lote, Flow observable). */
class FakeRomMetadataCacheDao : RomMetadataCacheDao {
    val rows = MutableStateFlow<Map<Int, RomMetadataCacheEntity>>(emptyMap())
    var upserts = 0

    override fun observeAll(): Flow<List<RomMetadataCacheEntity>> =
        kotlinx.coroutines.flow.flow { rows.collect { emit(it.values.toList()) } }

    override suspend fun getAll(): List<RomMetadataCacheEntity> = rows.value.values.toList()

    override suspend fun upsertAll(entities: List<RomMetadataCacheEntity>) {
        upserts++
        rows.value = rows.value + entities.associateBy { it.romId }
    }

    override suspend fun deleteAll(romIds: List<Int>) {
        rows.value = rows.value - romIds.toSet()
    }
}

/** Fetcher sin red: escribe una imagen PNG mínima y apunta cada URL pedida. */
class FakeCoverFetcher(var succeed: Boolean = true) : OfflineShelfCache.CoverFetcher {
    val calls = CopyOnWriteArrayList<String>()
    override fun fetch(url: String, target: File): Boolean {
        calls += url
        if (!succeed) return false
        target.parentFile?.mkdirs()
        target.writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + url.toByteArray())
        return true
    }
}

class OfflineShelfCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dao = FakeRomMetadataCacheDao()
    private val fetcher = FakeCoverFetcher()

    private fun cache() = OfflineShelfCache(dao, File(tmp.root, "covers"), fetcher, clock = { 1234L })

    private fun rom(
        id: Int = 7,
        cover: String? = "http://romm/assets/7/cover/big.png?ts=1",
        name: String = "Chrono Trigger",
    ) = Rom(
        id = id,
        name = name,
        fileName = "chrono.sfc",
        fileSizeBytes = 4L,
        platformId = 3,
        platformSlug = "snes",
        coverUrlSmall = null,
        coverUrlLarge = cover,
        files = listOf(RomFile("chrono.sfc", 4L)),
        isMulti = false,
        revision = null,
        regions = emptyList(),
        genres = listOf("RPG", "Adventure"),
        summary = "Time travel",
        igdbId = 99,
        igdbMetadata = IgdbMetadata(totalRating = 92.5, firstReleaseDate = 795_484_800L),
    )

    @Test
    fun `remember stores metadata and downloads the cover to disk`() = runBlocking {
        cache().remember(listOf(rom()))

        val row = dao.rows.value.getValue(7)
        assertEquals("Chrono Trigger", row.name)
        assertEquals("snes", row.platformSlug)
        assertEquals("RPG\nAdventure", row.genres)
        assertEquals(795_484_800L, row.releaseDate)
        assertEquals(92.5, row.rating!!, 0.0)
        assertEquals(99, row.igdbId)
        assertEquals("chrono.sfc", row.fileNames)
        assertEquals("http://romm/assets/7/cover/big.png?ts=1", row.coverSource)
        assertEquals(File(tmp.root, "covers/7.png").absolutePath, row.coverPath)
        assertTrue(File(row.coverPath!!).isFile)
    }

    @Test
    fun `an already cached cover is not downloaded again and unchanged rows are not rewritten`() = runBlocking {
        val cache = cache()
        cache.remember(listOf(rom()))
        cache.remember(listOf(rom()))

        assertEquals(1, fetcher.calls.size)
        assertEquals(1, dao.upserts)
    }

    @Test
    fun `a new cover url on the server replaces the cached file`() = runBlocking {
        val cache = cache()
        cache.remember(listOf(rom(cover = "http://romm/a.jpg")))
        val oldPath = dao.rows.value.getValue(7).coverPath!!
        cache.remember(listOf(rom(cover = "http://romm/b.png")))

        val row = dao.rows.value.getValue(7)
        assertEquals("http://romm/b.png", row.coverSource)
        assertFalse(File(oldPath).exists())
        assertTrue(File(row.coverPath!!).isFile)
        assertEquals(2, fetcher.calls.size)
    }

    @Test
    fun `a deleted cover file is downloaded again`() = runBlocking {
        val cache = cache()
        cache.remember(listOf(rom()))
        File(dao.rows.value.getValue(7).coverPath!!).delete()
        cache.remember(listOf(rom()))

        assertEquals(2, fetcher.calls.size)
        assertTrue(File(dao.rows.value.getValue(7).coverPath!!).isFile)
    }

    @Test
    fun `a failed download keeps the previous cover`() = runBlocking {
        val cache = cache()
        cache.remember(listOf(rom(cover = "http://romm/a.png")))
        fetcher.succeed = false
        cache.remember(listOf(rom(cover = "http://romm/b.png")))

        val row = dao.rows.value.getValue(7)
        assertEquals("http://romm/a.png", row.coverSource)
        assertTrue(File(row.coverPath!!).isFile)
    }

    @Test
    fun `without a remote cover only metadata is stored`() = runBlocking {
        cache().remember(listOf(rom(cover = null)))
        val row = dao.rows.value.getValue(7)
        assertNull(row.coverPath)
        assertNull(row.coverSource)
        assertTrue(fetcher.calls.isEmpty())
    }

    @Test
    fun `retain drops rows and cover files of games no longer downloaded`() = runBlocking {
        val cache = cache()
        cache.remember(listOf(rom(id = 1, cover = "http://romm/1.png"), rom(id = 2, cover = "http://romm/2.png")))
        val gone = dao.rows.value.getValue(2).coverPath!!
        cache.retain(setOf(1))

        assertEquals(setOf(1), dao.rows.value.keys)
        assertFalse(File(gone).exists())
        assertEquals(setOf(1), cache.entries.first().keys)
    }

    @Test
    fun `offlineRom builds a complete card from Room and the cache`() {
        val entity = DownloadedRomEntity(7, "chrono", "chrono.sfc", 3, "snes", "/roms/snes/chrono.sfc", 4L)
        val cached = RomMetadataCacheEntity(
            romId = 7, name = "Chrono Trigger", platformSlug = "snes", summary = "Time travel",
            genres = "RPG\nAdventure", releaseDate = 1L, rating = 90.0, igdbId = 99,
            fileNames = "a.cue\na.bin", coverSource = "http://x", coverPath = "/data/covers/7.png",
        )
        val rom = OfflineShelfCache.offlineRom(entity, cached)

        assertEquals("Chrono Trigger", rom.name)
        assertEquals("file:///data/covers/7.png", rom.coverUrlLarge)
        assertEquals(listOf("RPG", "Adventure"), rom.genres)
        assertEquals(1L, rom.igdbMetadata?.firstReleaseDate)
        assertEquals(listOf("a.cue", "a.bin"), rom.files.map { it.filename })
        assertEquals("chrono.sfc", rom.fileName)
        assertEquals(99, rom.igdbId)

        val bare = OfflineShelfCache.offlineRom(entity, null)
        assertEquals("chrono", bare.name)
        assertNull(bare.coverUrlLarge)
        assertNull(bare.igdbMetadata)
    }

    @Test
    fun `withOfflineCover uses the disk copy only when it matches the remote cover`() {
        val cached = RomMetadataCacheEntity(
            romId = 7, name = "x", platformSlug = "snes",
            coverSource = "http://romm/a.png", coverPath = "/c/7.png",
        )
        val same = rom(cover = "http://romm/a.png").withOfflineCover(cached) { true }
        assertEquals("file:///c/7.png", same.coverUrlLarge)
        assertEquals("file:///c/7.png", same.coverUrlSmall)

        val changed = rom(cover = "http://romm/b.png")
        assertSame(changed, changed.withOfflineCover(cached) { true })

        val noRemote = rom(cover = null).withOfflineCover(cached) { true }
        assertEquals("file:///c/7.png", noRemote.coverUrlLarge)

        val missingFile = rom(cover = "http://romm/a.png")
        assertSame(missingFile, missingFile.withOfflineCover(cached) { false })
        assertSame(missingFile, missingFile.withOfflineCover(null))
    }
}
