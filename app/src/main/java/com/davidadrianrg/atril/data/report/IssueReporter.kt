package com.davidadrianrg.atril.data.report

import android.net.Uri
import com.davidadrianrg.atril.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Crea la issue en GitHub directamente o abre una prellenada si no hay API. */
object IssueReporter {

    private const val REPO = "davidadrianrg/atril"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val hasEndpoint: Boolean get() = BuildConfig.REPORT_URL.isNotBlank()

    /** URL de GitHub con el reporte ya escrito (fallback sin backend). */
    fun prefilledIssueUrl(message: String, diagnostics: String): String {
        val body = buildString {
            appendLine(message.trim())
            if (diagnostics.isNotBlank()) {
                appendLine()
                appendLine("---")
                appendLine(diagnostics.trim())
            }
        }.take(7000)
        return Uri.parse("https://github.com/$REPO/issues/new")
            .buildUpon()
            .appendQueryParameter("title", "[Report] " + message.trim().lineSequence().first().take(80))
            .appendQueryParameter("body", body)
            .appendQueryParameter("labels", "user-report")
            .build()
            .toString()
    }

    /**
     * Envía el reporte al worker configurado. Devuelve la URL de la issue
     * creada o un error legible.
     */
    suspend fun send(message: String, diagnostics: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("message", message.trim())
                .put("diagnostics", diagnostics)
                .put("appVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                .toString()
            val request = Request.Builder()
                .url(BuildConfig.REPORT_URL)
                .header("X-Atril-Key", BuildConfig.REPORT_KEY)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(200)}")
                val url = runCatching { JSONObject(text).optString("url") }.getOrDefault("")
                if (url.isBlank()) error("Respuesta sin URL")
                url
            }
        }
    }
}
