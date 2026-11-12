package com.davidadrianrg.atril.data.emulators

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.util.UUID

/** Emulador añadido a mano por URL de GitHub. */
data class CustomEmulator(
    val id: String,
    val name: String,
    val repoUrl: String,
    val packageName: String? = null,
    val apkFilterRegEx: String = "",
    val includePrereleases: Boolean = false,
)

/** Persistencia JSON de los emuladores manuales. */
class CustomEmulatorStore(private val file: File) {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter<List<CustomEmulator>>(
        Types.newParameterizedType(List::class.java, CustomEmulator::class.java),
    )

    fun all(): List<CustomEmulator> =
        if (file.isFile) runCatching { adapter.fromJson(file.readText()) }.getOrNull().orEmpty()
        else emptyList()

    fun get(id: String): CustomEmulator? = all().firstOrNull { it.id == id }

    fun add(repoUrl: String, name: String, regex: String, prereleases: Boolean): CustomEmulator {
        val clean = repoUrl.trim().removeSuffix("/").removeSuffix(".git")
        val slug = clean.substringAfter("github.com/").trim('/')
        val entry = CustomEmulator(
            id = "custom:$slug",
            name = name.ifBlank { slug.substringAfter('/') },
            repoUrl = "https://github.com/$slug",
            apkFilterRegEx = regex.trim(),
            includePrereleases = prereleases,
        )
        save(all().filterNot { it.id == entry.id } + entry)
        return entry
    }

    fun setPackageName(id: String, packageName: String) {
        save(all().map { if (it.id == id) it.copy(packageName = packageName) else it })
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
    }

    private fun save(list: List<CustomEmulator>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        tmp.writeText(adapter.toJson(list))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
