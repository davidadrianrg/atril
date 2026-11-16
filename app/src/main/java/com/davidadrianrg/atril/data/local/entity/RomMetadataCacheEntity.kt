package com.davidadrianrg.atril.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadatos mínimos de un juego descargado que hoy solo llegan de RomM, para
 * que Play pinte la carta completa sin red (F1).
 *
 * Tabla aparte de `downloaded_roms` a propósito (igual que `rom_presentation`):
 * esa fila se reescribe con REPLACE en cada re-descarga y la escriben varios
 * sitios (worker, escaneo, «marcar como descargado») que no tienen el DTO.
 */
@Entity(tableName = "rom_metadata_cache")
data class RomMetadataCacheEntity(
    @PrimaryKey val romId: Int,
    val name: String,
    val platformSlug: String,
    val summary: String? = null,
    /** Géneros separados por salto de línea (vacío = sin géneros). */
    val genres: String = "",
    /** `first_release_date` de IGDB, tal cual lo da RomM. */
    val releaseDate: Long? = null,
    val rating: Double? = null,
    val igdbId: Int? = null,
    /** Ficheros del juego en RomM, separados por salto de línea (multidisco y zip). */
    val fileNames: String = "",
    /** URL remota de la que se bajó la carátula (detecta cambios en el servidor). */
    val coverSource: String? = null,
    /** Ruta absoluta de la carátula en disco (filesDir/offline-covers). */
    val coverPath: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
