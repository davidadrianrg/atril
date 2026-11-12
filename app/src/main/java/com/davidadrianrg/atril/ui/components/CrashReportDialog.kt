package com.davidadrianrg.atril.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.util.CrashLogger

/** Muestra el último crash guardado y permite copiarlo para enviarlo. */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashLogger.read(context)) }
    val current = crash ?: return
    AlertDialog(
        onDismissRequest = {
            CrashLogger.clear(context)
            crash = null
        },
        title = { Text(tr("crash.title")) },
        text = {
            Box(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    Text(current, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Atril crash", current))
                CrashLogger.clear(context)
                crash = null
            }) { Text(tr("crash.copy")) }
        },
        dismissButton = {
            TextButton(onClick = {
                CrashLogger.clear(context)
                crash = null
            }) { Text(tr("common.close")) }
        },
    )
}
