package com.davidadrianrg.atril.data.media

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Descarga una imagen elegida por el usuario y la deja en el almacenamiento. */
object MediaCache {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /** Guarda `url` en `<filesDir>/<dirName>/<fileName>` y devuelve la ruta. */
    suspend fun save(context: Context, url: String, dirName: String, fileName: String): String? =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, dirName).apply { mkdirs() }
            val target = File(dir, fileName)
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val bytes = response.body?.bytes() ?: return@withContext null
                    if (bytes.isEmpty()) return@withContext null
                    target.outputStream().use { it.write(bytes) }
                }
                target.absolutePath
            } catch (e: IOException) {
                null
            }
        }

    /** Igual que [save] pero con un nombre derivado de la URL (para portadas). */
    suspend fun saveTemp(context: Context, url: String): File? {
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "media.jpg" }
        val path = save(context, url, "media-picks", name) ?: return null
        return File(path)
    }
}
