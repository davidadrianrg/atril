package com.davidadrianrg.atril.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.core.report.GitHubIssueUrl
import java.awt.Desktop
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Datos técnicos del escritorio para un reporte. Nunca incluye credenciales ni
 * rutas personales: solo sistema, versión y estado de la conexión.
 */
internal fun desktopReportDiagnostics(): String {
    val runtime = Runtime.getRuntime()
    return buildString {
        appendLine("### Device")
        appendLine("- App: Atril (desktop) ${DesktopConfig.appVersion}")
        appendLine("- OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        appendLine("- Java: ${System.getProperty("java.version")}")
        appendLine("- Locale: ${Locale.getDefault().toLanguageTag()}")
        appendLine("- Theme: ${DesktopConfig.themeId}")
        appendLine("- RomM configured: ${DesktopConfig.serverUrl.isNotBlank()}")
        appendLine("- Free memory: ${runtime.freeMemory() / 1024 / 1024} MB")
    }
}

/** Envía el reporte al worker si hay endpoint; si no, devuelve la URL de GitHub. */
internal object DesktopIssueReporter {

    private val client = OkHttpClient()
    private val mapAdapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )

    private val endpoint: String?
        get() = System.getenv("ATRIL_REPORT_URL")?.takeIf { it.isNotBlank() }

    private val key: String?
        get() = System.getenv("ATRIL_REPORT_KEY")?.takeIf { it.isNotBlank() }

    val hasEndpoint: Boolean get() = endpoint != null

    suspend fun send(message: String, diagnostics: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = endpoint ?: error("no endpoint")
                val payload: Map<String, Any?> = mapOf(
                    "message" to message.trim(),
                    "diagnostics" to diagnostics,
                    "appVersion" to "desktop ${DesktopConfig.appVersion}",
                )
                val json = mapAdapter.toJson(payload)
                val request = Request.Builder()
                    .url(target)
                    .apply { key?.let { header("X-Atril-Key", it) } }
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(200)}")
                    val data: Map<String, Any?>? = runCatching { mapAdapter.fromJson(text) }.getOrNull()
                    val url = data?.get("url") as? String
                    if (url.isNullOrBlank()) error("Respuesta sin URL")
                    url
                }
            }
        }
}

private fun openBrowser(url: String): Boolean =
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            true
        } else {
            false
        }
    }.getOrDefault(false)

/**
 * Reporte de problema en escritorio: texto libre del usuario + diagnóstico
 * opcional. Con endpoint crea la issue; si no, abre GitHub con el cuerpo
 * ya rellenado.
 */
@Composable
internal fun DesktopReportDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun diagnostics(): String = if (includeDiagnostics) desktopReportDiagnostics() else ""

    fun openGitHub() {
        val url = GitHubIssueUrl.prefilled(message, diagnostics())
        if (!openBrowser(url)) error = url
        else onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(tr("report.title")) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(tr("report.intro"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(4000) },
                    label = { Text(tr("report.message")) },
                    placeholder = { Text(tr("report.message_hint")) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !sending,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeDiagnostics,
                        onCheckedChange = {
                            includeDiagnostics = it
                            preview = null
                        },
                        enabled = !sending,
                    )
                    Text(tr("report.include"), style = MaterialTheme.typography.bodySmall)
                }
                if (!DesktopIssueReporter.hasEndpoint) {
                    Text(
                        tr("report.manual_note"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = message.isNotBlank() && !sending,
                onClick = {
                    if (!DesktopIssueReporter.hasEndpoint) {
                        openGitHub()
                        return@TextButton
                    }
                    scope.launch {
                        sending = true
                        error = null
                        val diag = withContext(Dispatchers.IO) { diagnostics() }
                        DesktopIssueReporter.send(message, diag)
                            .onSuccess { url ->
                                sending = false
                                if (!openBrowser(url)) error = url else onDismiss()
                            }
                            .onFailure {
                                sending = false
                                error = tr("report.error", it.message ?: "")
                            }
                    }
                },
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                }
                Text(if (sending) tr("report.sending") else tr("report.send"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text(tr("common.close")) }
        },
    )
}
