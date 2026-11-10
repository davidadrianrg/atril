package com.davidadrianrg.atril.ui.components

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.davidadrianrg.atril.core.i18n.tr
import com.davidadrianrg.atril.data.repository.LocateOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "LocateFilePicker"

/** Documento pendiente de confirmar para reemplazar un fichero existente. */
private data class PendingReplace(val uri: Uri, val fileName: String)

/**
 * Selector de fichero compartido por la Biblioteca y Jugar.
 *
 * Devuelve una lambda que abre el selector de documentos con tipo comodín; al elegir ejecuta
 * [onPicked] y traduce [LocateOutcome] a mensajes. Si el destino ya existe
 * pide confirmación y reintenta con `overwrite = true`.
 */
@Composable
internal fun rememberLocateFilePicker(
    onPicked: suspend (uri: Uri, overwrite: Boolean) -> LocateOutcome,
    onMessage: (String) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentPicked by rememberUpdatedState(onPicked)
    val currentMessage by rememberUpdatedState(onMessage)
    var pending by remember { mutableStateOf<PendingReplace?>(null) }

    suspend fun runImport(uri: Uri, overwrite: Boolean) {
        val outcome = try {
            currentPicked(uri, overwrite)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // El selector nunca debe tumbar la pantalla por un fallo de E/S.
            Log.w(TAG, "Locate file failed (${e.javaClass.simpleName})")
            currentMessage(tr("library.file_copy_failed"))
            return
        }
        when (outcome) {
            LocateOutcome.Linked -> currentMessage(tr("library.file_linked"))
            is LocateOutcome.AlreadyExists ->
                pending = PendingReplace(uri, outcome.fileName)
            LocateOutcome.InvalidFile, LocateOutcome.CopyFailed ->
                currentMessage(tr("library.file_copy_failed"))
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { runImport(uri, false) }
    }

    pending?.let { replace ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(tr("library.file_replace")) },
            text = { Text(tr("library.file_exists", replace.fileName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        scope.launch { runImport(replace.uri, true) }
                    },
                ) { Text(tr("library.file_replace")) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(tr("common.cancel")) }
            },
        )
    }

    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}
