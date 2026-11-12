package com.davidadrianrg.atril.data.emulators

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/** Caché de últimas versiones detectadas por el chequeo en segundo plano. */
class EmulatorUpdatesStore(private val file: File) {

    private val moshi by lazy { Moshi.Builder().add(KotlinJsonAdapterFactory()).build() }
    private val adapter by lazy {
        moshi.adapter<Map<String, String>>(
            Types.newParameterizedType(Map::class.java, String::class.java, String::class.java),
        )
    }

    fun read(): Map<String, String> =
        if (file.isFile) runCatching { adapter.fromJson(file.readText()) }.getOrNull().orEmpty()
        else emptyMap()

    fun save(updates: Map<String, String>) {
        file.parentFile?.mkdirs()
        file.writeText(adapter.toJson(updates))
    }
}
