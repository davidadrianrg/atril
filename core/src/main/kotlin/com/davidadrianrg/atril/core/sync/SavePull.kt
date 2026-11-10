package com.davidadrianrg.atril.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Puerto del pull de saves previo al lanzamiento.
 *
 * Devuelve cuántos saves se han escrito en disco para [romId] (`0` si no había
 * nada pendiente). Puede lanzar `IOException`/`HttpException`; [SavePull] las
 * clasifica y nunca las propaga al lanzamiento. Debe ser cooperativo con la
 * cancelación.
 */
interface SavePullClient {

    /** Descarga los saves nuevos o más recientes de [romId] sin subir nada. */
    suspend fun pullNewSaves(romId: Int): Int
}

/**
 * Resultado del pull previo al lanzamiento.
 *
 * Todos los valores significan "continúa con el lanzamiento": el pull es una
 * mejora, nunca un requisito.
 */
sealed interface SavePullResult {

    /** Se descargaron [count] saves (siempre > 0). */
    data class Pulled(val count: Int) : SavePullResult

    /** El cliente no tenía nada que bajar. */
    data object NothingToPull : SavePullResult

    /** No había cliente (sync desactivado o RomM sin configurar) o el id no es de RomM. */
    data object Skipped : SavePullResult

    /** Se agotó el tope de tiempo; el pull se canceló, el lanzamiento no. */
    data object TimedOut : SavePullResult

    /** El cliente falló (red, HTTP, integridad…); [reason] es solo para diagnóstico y no se registra tal cual. */
    data class Failed(val reason: String?) : SavePullResult
}

/**
 * Pull de saves con un tope duro de [DEFAULT_TIMEOUT_MS] antes de lanzar un
 * juego.
 *
 * Garantías:
 * - [beforeLaunch] nunca lanza por un fallo del cliente ni por el timeout.
 * - El tope cubre TODO el pull (listar y descargar), no cada operación.
 * - El timeout cancela solo el pull: la corrutina del lanzamiento sigue viva.
 * - Si el llamador cancela de verdad su corrutina, la cancelación se propaga
 *   (no se traga la cancelación externa).
 */
object SavePull {

    /** Tope por defecto del pull completo antes de lanzar. */
    const val DEFAULT_TIMEOUT_MS = 8_000L

    /**
     * Ejecuta el pull de [romId] con [client] dentro de [timeoutMs].
     *
     * @param client cliente a usar; `null` (sync apagado o sin servidor) → [SavePullResult.Skipped].
     * @param romId id de RomM; `<= 0` (juegos locales) → [SavePullResult.Skipped].
     * @param timeoutMs tope del pull completo.
     * @param log destino de los avisos de fallo (el timeout no se registra como error).
     * @return el resultado; siempre significa "continúa con el lanzamiento".
     * @throws CancellationException solo si la corrutina del llamador se canceló.
     */
    suspend fun beforeLaunch(
        client: SavePullClient?,
        romId: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        log: (String) -> Unit = {},
    ): SavePullResult {
        if (client == null || romId <= 0) return SavePullResult.Skipped

        val outcome = try {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(0L)) {
                try {
                    val count = client.pullNewSaves(romId)
                    if (count > 0) SavePullResult.Pulled(count) else SavePullResult.NothingToPull
                } catch (e: CancellationException) {
                    // Propaga el timeout y las cancelaciones reales; solo las
                    // clasifica el catch exterior.
                    throw e
                } catch (t: Throwable) {
                    SavePullResult.Failed(t.message)
                }
            }
        } catch (e: CancellationException) {
            // Si el llamador sigue activo, la cancelación vino del cliente y el
            // lanzamiento debe continuar; si no, es una cancelación real.
            currentCoroutineContext().ensureActive()
            log("Pre-launch save pull cancelled by the client for rom $romId (${e.javaClass.simpleName})")
            SavePullResult.Failed(e.message)
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            log("Pre-launch save pull failed for rom $romId (${t.javaClass.simpleName})")
            SavePullResult.Failed(t.message)
        }

        return outcome ?: SavePullResult.TimedOut
    }
}
