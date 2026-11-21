package com.davidadrianrg.atril.core.sync.webdav

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Cliente WebDAV mínimo (PUT / GET / PROPFIND / MKCOL) para sincronizar los
 * saves de los juegos locales con un servidor configurable (Nextcloud, knot,
 * Hugo…). Puramente JVM, sin dependencias Android, para usarlo también en
 * escritorio.
 *
 * Diseñado para volúmenes pequeños (saves): buffers simples y sin reintentos
 * sofisticados — el llamador decide la política (el sync de saves ya retraza
 * con WorkManager).
 */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
) {
    /** Base normalizada con `/` final: `https://host/remote.php/dav/files/user/AtrilSaves/`. */
    private val base = baseUrl.trim().trimEnd('/') + "/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** `PROPFIND` profundidad 0 sobre la base: 207 = carpeta accesible. */
    fun testConnection(): Result<String> = runCatching {
        val response = propfind(base, depth = 0)
        response.use {
            check(it.code == 207 || it.code == 200) { "HTTP ${it.code}" }
            it.header("Server") ?: "OK"
        }
    }

    /**
     * Lista los ficheros de una carpeta remota (PROPFIND depth 1).
     * Devuelve vacío si la carpeta no existe todavía (404): el sync la crea.
     */
    fun listFiles(dirPath: String): Result<List<WebDavEntry>> = runCatching {
        val url = base + dirPath.trimStart('/').trimEnd('/') + "/"
        val response = propfind(url, depth = 1)
        response.use {
            when (it.code) {
                207, 200 -> parseMultistatus(it.body?.bytes() ?: ByteArray(0), url)
                404 -> emptyList()
                else -> error("HTTP ${it.code}")
            }
        }
    }

    /** Sube (PUT) un fichero; crea las carpetas intermedias si faltan. */
    fun uploadFile(path: String, bytes: ByteArray): Result<Unit> = runCatching {
        mkcolRecursive(path.substringBeforeLast('/')).getOrThrow()
        val url = base + path.trimStart('/')
        val request = Request.Builder()
            .url(url)
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .header("Authorization", authHeader())
            .header("Overwrite", "T")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.code in 200..299) { "HTTP ${response.code} al subir $path" }
            Unit
        }
    }

    /** Descarga (GET) un fichero remoto. Falla con el código HTTP si no está. */
    fun downloadFile(path: String): Result<ByteArray> = runCatching {
        val url = base + path.trimStart('/')
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", authHeader())
            .build()
        client.newCall(request).execute().use { response ->
            check(response.code == 200) { "HTTP ${response.code} al descargar $path" }
            response.body?.bytes() ?: error("Respuesta vacía de $path")
        }
    }

    /** Borra (DELETE) un fichero remoto; 404 se tolera (idempotente). */
    fun deleteFile(path: String): Result<Unit> = runCatching {
        val url = base + path.trimStart('/')
        val request = Request.Builder()
            .url(url)
            .delete()
            .header("Authorization", authHeader())
            .build()
        client.newCall(request).execute().use { response ->
            check(response.code == 204 || response.code == 200 || response.code == 404) {
                "HTTP ${response.code} al borrar $path"
            }
            Unit
        }
    }

    private fun authHeader(): String {
        val token = okhttp3.Credentials.basic(username, password)
        return token
    }

    private fun propfind(url: String, depth: Int): okhttp3.Response {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .header("Depth", depth.toString())
            .header("Prefer", "return-minimal")
            .method(
                "PROPFIND",
                """
                <d:propfind xmlns:d="DAV:">
                    <d:prop>
                        <d:getcontentlength/>
                        <d:getlastmodified/>
                        <d:resourcetype/>
                    </d:prop>
                </d:propfind>
                """.trimIndent().toRequestBody("text/xml; charset=utf-8".toMediaType()),
            )
            .build()
        return client.newCall(request).execute()
    }

    /**
     * Crea carpetas intermedias de [dirPath] con MKCOL de una en una
     * (el estándar no crea en cascada). Un 405/301 significa que ya existe.
     */
    fun mkcolRecursive(dirPath: String): Result<Unit> = runCatching {
        val segments = dirPath.trimStart('/').trimEnd('/').split('/')
            .filter { it.isNotBlank() }
        var current = ""
        for (segment in segments) {
            current += "/$segment"
            val url = base + current.trimStart('/')
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader())
                .method("MKCOL", null)
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    201, 405, 301, 501 -> Unit // creada / ya existía
                    else -> error("HTTP ${response.code} creando carpeta $current")
                }
            }
        }
    }

    /** Comprueba credenciales y URL antes de guardarlas (botón «Probar»). */
    fun validate(): Result<String> = runCatching {
        testConnection().getOrThrow()
        "OK"
    }

    companion object {
        fun fromConfig(url: String, username: String, password: String): WebDavClient =
            WebDavClient(url, username, password)
    }
}

/** Fichero de una carpeta WebDAV, tal como lo devuelve PROPFIND depth 1. */
data class WebDavEntry(
    val name: String,
    val sizeBytes: Long,
    /** Fecha HTTP de `getlastmodified`, tal cual (p. ej. `Mon, 16 Nov 2026 22:00:00 GMT`). */
    val lastModifiedHttp: String?,
) {
    val isDirectory: Boolean get() = sizeBytes <= 0L && lastModifiedHttp == null
}

/**
 * Parseo minimalista del `multistatus` (207) con el parser XML estándar de la
 * plataforma (Android y JVM lo traen de serie): sin dependencias extra.
 */
internal fun parseMultistatus(xml: ByteArray, requestUrl: String): List<WebDavEntry> {
    val doc = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setNamespaceAware(false)
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    } catch (_: Exception) {
        return emptyList()
    }
    val entries = mutableListOf<WebDavEntry>()
    val requestHref = requestUrl.trimEnd('/')
    val responses = doc.getElementsByTagName("response")
    for (i in 0 until responses.length) {
        val node = responses.item(i) ?: continue
        val href = node.textContentOf("href")?.takeIf { it.isNotBlank() } ?: continue
        if (href.trimEnd('/') == requestHref) continue
        val isDir = node.descendantText("resourcetype")?.contains("collection", ignoreCase = true) == true
        entries += WebDavEntry(
            name = java.net.URLDecoder.decode(href.trimEnd('/').substringAfterLast('/'), "UTF-8"),
            sizeBytes = node.descendantText("getcontentlength")?.toLongOrNull() ?: 0L,
            lastModifiedHttp = node.descendantText("getlastmodified")?.takeIf { !isDir },
        )
    }
    return entries.filter { !it.isDirectory }
}

private fun org.w3c.dom.Node.textContentOf(tag: String): String? {
    val children = childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        if (child.nodeType == org.w3c.dom.Node.ELEMENT_NODE && child.nodeName.lowercase().endsWith(tag.lowercase())) {
            return child.textContent
        }
    }
    return null
}

private fun org.w3c.dom.Node.descendantText(tag: String): String? {
    val children = childNodes
    for (i in 0 until children.length) {
        val child = children.item(i) ?: continue
        if (child.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
            if (child.nodeName.lowercase().endsWith(tag.lowercase())) return child.textContent
            descendantText(tag)?.let { return it }
        }
    }
    return null
}

/** IOException util para errores de red claros. */
class WebDavException(message: String, cause: Throwable? = null) : IOException(message, cause)
