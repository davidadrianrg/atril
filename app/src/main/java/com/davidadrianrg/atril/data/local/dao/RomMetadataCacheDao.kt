package com.davidadrianrg.atril.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.davidadrianrg.atril.data.local.entity.RomMetadataCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RomMetadataCacheDao {

    @Query("SELECT * FROM rom_metadata_cache")
    fun observeAll(): Flow<List<RomMetadataCacheEntity>>

    @Query("SELECT * FROM rom_metadata_cache")
    suspend fun getAll(): List<RomMetadataCacheEntity>

    /** Un solo REPLACE por lote: Play recibe una emisión, no una por juego. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<RomMetadataCacheEntity>)

    @Query("DELETE FROM rom_metadata_cache WHERE romId IN (:romIds)")
    suspend fun deleteAll(romIds: List<Int>)
}
