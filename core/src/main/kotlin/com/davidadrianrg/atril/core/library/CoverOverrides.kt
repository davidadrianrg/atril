package com.davidadrianrg.atril.core.library

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * Carátulas elegidas a mano por el usuario, por romId. Guarda la ruta del
 * fichero local (se copia la imagen a un directorio de la app); cuando hay
 * override, tiene prioridad sobre las URL de RomM y sobre el caché offline.
 */
class CoverOverrides(private val file: File) {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java),
    )

    fun get(romId: Int): String? =
        runCatching { load()[romId.toString()] }.getOrNull()?.takeIf { File(it).isFile }

    fun set(romId: Int, path: String?) {
        val map = load().toMutableMap()
        if (path.isNullOrBlank()) map.remove(romId.toString()) else map[romId.toString()] = path
        runCatching { file.parentFile?.mkdirs(); file.writeText(adapter.toJson(map)) }
    }

    private fun load(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return runCatching { adapter.fromJson(file.readText()) }.getOrNull().orEmpty()
    }
}
