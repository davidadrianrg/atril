package com.davidadrianrg.atril.data.emulators

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/** Recuerda el package real de emuladores instalados desde el pack. */
class PackageMapStore(private val file: File) {

    private val moshi by lazy { Moshi.Builder().add(KotlinJsonAdapterFactory()).build() }
    private val adapter by lazy {
        moshi.adapter<Map<String, String>>(
            Types.newParameterizedType(Map::class.java, String::class.java, String::class.java),
        )
    }

    fun all(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return runCatching { adapter.fromJson(file.readText()) }.getOrNull().orEmpty()
    }

    fun get(id: String): String? = all()[id]

    fun set(id: String, packageName: String) {
        if (packageName.isBlank()) return
        val map = all().toMutableMap()
        if (map[id] == packageName) return
        map[id] = packageName
        runCatching { file.writeText(adapter.toJson(map)) }
    }
}
