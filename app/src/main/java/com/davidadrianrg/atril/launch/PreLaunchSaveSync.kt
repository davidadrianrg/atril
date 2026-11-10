package com.davidadrianrg.atril.launch

import com.davidadrianrg.atril.core.sync.SavePull
import com.davidadrianrg.atril.core.sync.SavePullClient
import com.davidadrianrg.atril.core.sync.SavePullResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Único punto de entrada de Android para "bajar saves antes de lanzar".
 *
 * No tiene imports de Android a propósito: es Kotlin puro y se prueba en JVM.
 * El lanzamiento siempre continúa; el pull es best-effort y está acotado por
 * [SavePull.DEFAULT_TIMEOUT_MS].
 *
 * @param saveSyncEnabled ajuste de sync de saves; apagado → no se hace nada.
 * @param clientFactory crea el cliente del pull (uno por lanzamiento).
 * @param serverConfigured RomM conectado (servidor + clave); si no, no se hace nada.
 */
class PreLaunchSaveSync(
    private val saveSyncEnabled: Flow<Boolean>,
    private val clientFactory: () -> SavePullClient,
    private val serverConfigured: suspend () -> Boolean = { true },
) {

    /**
     * Baja los saves nuevos de [romId] antes de lanzar, avisando una sola vez
     * con [onUpdating] si de verdad se va a intentar.
     *
     * @return el resultado del pull; nunca lanza por un fallo del cliente.
     * @throws kotlinx.coroutines.CancellationException solo si el llamador se cancela.
     */
    suspend fun run(romId: Int, onUpdating: () -> Unit): SavePullResult {
        if (romId <= 0) return SavePullResult.Skipped
        // Los ajustes se leen fuera del tope de 8 s: si DataStore falla o se
        // cuelga, el lanzamiento debe continuar igualmente (nunca abortarlo).
        val enabled = try {
            saveSyncEnabled.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SavePullResult.Skipped
        }
        if (!enabled) return SavePullResult.Skipped
        val configured = try {
            serverConfigured()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SavePullResult.Skipped
        }
        if (!configured) return SavePullResult.Skipped
        onUpdating()
        return SavePull.beforeLaunch(clientFactory(), romId)
    }
}
