package com.davidadrianrg.atril.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.data.report.DeviceReportCollector
import com.davidadrianrg.atril.data.report.IssueReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reporte de problema: texto libre del usuario + diagnóstico opcional. Con
 * backend configurado crea la issue directamente; si no, abre GitHub con el
 * cuerpo ya rellenado para que el usuario pulse "Submit new issue".
 */
@Composable
fun ReportIssueDialog(rommConfigured: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun diagnostics(): String = if (includeDiagnostics) {
        DeviceReportCollector.collect(context, rommConfigured)
    } else {
        ""
    }

    fun openGitHub() {
        val url = IssueReporter.prefilledIssueUrl(message, diagnostics())
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(tr("report.title")) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
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
                TextButton(
                    onClick = {
                        if (preview == null) {
                            scope.launch {
                                preview = withContext(Dispatchers.IO) { diagnostics() }
                            }
                        } else {
                            preview = null
                        }
                    },
                    enabled = includeDiagnostics && !sending,
                ) {
                    Text(if (preview == null) tr("report.preview") else tr("report.hide_preview"))
                }
                preview?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Text(
                    tr("report.privacy"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (!IssueReporter.hasEndpoint) {
                    Text(
                        tr("report.manual_note"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = message.isNotBlank() && !sending,
                onClick = {
                    if (!IssueReporter.hasEndpoint) {
                        openGitHub()
                        return@TextButton
                    }
                    scope.launch {
                        sending = true
                        error = null
                        val diag = withContext(Dispatchers.IO) { diagnostics() }
                        IssueReporter.send(message, diag)
                            .onSuccess { url ->
                                sending = false
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                                onDismiss()
                            }
                            .onFailure {
                                sending = false
                                error = tr("report.error", it.message ?: "")
                            }
                    }
                },
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(if (sending) tr("report.sending") else tr("report.send"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text(tr("common.close")) }
        },
    )
}
