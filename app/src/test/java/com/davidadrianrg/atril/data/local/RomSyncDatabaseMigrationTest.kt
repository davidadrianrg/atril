package com.davidadrianrg.atril.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.davidadrianrg.atril.data.local.entity.RomMetadataCacheEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Migración 8 → 9 (F1) sobre una base real creada con el esquema exportado
 * `schemas/…/8.json`: los datos existentes sobreviven y Room valida que la
 * tabla nueva coincide con la entidad (si no, abrir la base lanza).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RomSyncDatabaseMigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun schema(version: Int): JSONObject {
        val file = File("schemas/com.davidadrianrg.atril.data.local.RomSyncDatabase/$version.json")
            .takeIf { it.isFile }
            ?: File("app/schemas/com.davidadrianrg.atril.data.local.RomSyncDatabase/$version.json")
        return JSONObject(file.readText()).getJSONObject("database")
    }

    /** Crea la base en [version] ejecutando el SQL exportado por Room, como MigrationTestHelper. */
    private fun createDatabase(version: Int, seed: (SupportSQLiteDatabase) -> Unit) {
        val db = schema(version)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db2: SupportSQLiteDatabase) {
                    val entities = db.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db2.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: continue
                        for (j in 0 until indices.length()) {
                            db2.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                        }
                    }
                    val setup = db.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db2.execSQL(setup.getString(i))
                }

                override fun onUpgrade(db2: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            seed(helper.writableDatabase)
        }
    }

    @Test
    fun `migration 8 to 9 keeps downloaded games and adds the offline cache`() = runBlocking {
        createDatabase(8) { db ->
            db.execSQL(
                "INSERT INTO downloaded_roms (romId, name, fileName, platformId, platformSlug, localPath, " +
                    "fileSizeBytes, fileHash, downloadedAt, savesPathOverride, excludedFromSync) " +
                    "VALUES (7, 'Chrono', 'chrono.sfc', 3, 'snes', '/roms/snes/chrono.sfc', 4, NULL, 1, NULL, 0)",
            )
            db.execSQL("INSERT INTO rom_presentation (romId, displayMode, updatedAt) VALUES (7, 'physical', 1)")
        }

        val room = Room.databaseBuilder(context, RomSyncDatabase::class.java, dbName)
            .addMigrations(*RomSyncDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val kept = room.romDao().getAllDownloadedRoms()
            assertEquals(listOf(7), kept.map { it.romId })
            assertEquals("/roms/snes/chrono.sfc", kept.single().localPath)
            assertEquals("physical", room.romPresentationDao().get(7)?.displayMode)

            val cacheDao = room.romMetadataCacheDao()
            assertTrue(cacheDao.getAll().isEmpty())
            cacheDao.upsertAll(
                listOf(
                    RomMetadataCacheEntity(
                        romId = 7, name = "Chrono Trigger", platformSlug = "snes",
                        genres = "RPG", releaseDate = 1L, rating = 9.0, coverPath = "/c/7.png",
                    ),
                ),
            )
            assertEquals("Chrono Trigger", cacheDao.getAll().single().name)
            cacheDao.deleteAll(listOf(7))
            assertTrue(cacheDao.getAll().isEmpty())
        } finally {
            room.close()
        }
    }

    @Test
    fun `migration 8 to 9 creates exactly the table Room expects`() {
        val expected = (0 until schema(9).getJSONArray("entities").length())
            .map { schema(9).getJSONArray("entities").getJSONObject(it) }
            .single { it.getString("tableName") == "rom_metadata_cache" }
            .getString("createSql")
            .replace("\${TABLE_NAME}", "rom_metadata_cache")

        createDatabase(8) { db ->
            RomSyncDatabase.MIGRATION_8_9.migrate(db)
            db.query("SELECT sql FROM sqlite_master WHERE name = 'rom_metadata_cache'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(expected.removePrefix("CREATE TABLE IF NOT EXISTS "), c.getString(0).removePrefix("CREATE TABLE "))
            }
        }
    }
}
