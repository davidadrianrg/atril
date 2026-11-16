package com.davidadrianrg.atril.core.media

import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Descarga una imagen a un fichero de forma atómica.
 *
 * Se escribe en `<target>.part` y solo se renombra al final, así un corte a
 * mitad nunca deja una imagen truncada con el nombre definitivo. Se rechaza
 * lo que no es una imagen (p. ej. la página HTML de un proxy cautivo con
 * HTTP 200), lo vacío y lo que pasa de [maxBytes].
 *
 * Lo comparten la caché del medio físico y la caché offline de carátulas.
 */
object ImageFileDownloader {

    const val DEFAULT_MAX_BYTES: Long = 20L * 1024 * 1024

    fun download(
        client: OkHttpClient,
        url: String,
        target: File,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): Result<File> {
        val part = File(target.parentFile, "${target.name}.part")
        return runCatching {
            target.parentFile?.mkdirs()
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty body")
                val type = body.contentType()
                if (type != null && type.type != "image") throw IOException("not an image ($type)")
                if (body.contentLength() > maxBytes) throw IOException("image too large")
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) throw IOException("image too large")
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (part.length() == 0L) throw IOException("empty image")
            if (!looksLikeImage(part)) throw IOException("not an image (signature)")
            target.delete()
            if (!part.renameTo(target)) throw IOException("rename failed")
            target
        }.onFailure { part.delete() }
    }

    /** Firma mínima de cabecera: PNG, JPEG, GIF, WebP o BMP. */
    fun looksLikeImage(file: File): Boolean = runCatching {
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        looksLikeImage(head.copyOf(maxOf(read, 0)))
    }.getOrDefault(false)

    fun looksLikeImage(head: ByteArray): Boolean {
        fun at(i: Int) = head.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        fun ascii(i: Int, s: String) = s.indices.all { at(i + it) == s[it].code }
        return when {
            at(0) == 0x89 && ascii(1, "PNG") -> true
            at(0) == 0xFF && at(1) == 0xD8 -> true
            ascii(0, "GIF8") -> true
            ascii(0, "RIFF") && ascii(8, "WEBP") -> true
            ascii(0, "BM") -> true
            else -> false
        }
    }
}
