package com.davidadrianrg.atril.launch

import android.content.Context
import com.davidadrianrg.atril.core.i18n.tr
import java.io.File
import kotlinx.coroutines.CancellationException

/** Enlaza (o intenta enlazar) un fichero local a un juego de RomM. */
interface RomFileLinker {
    /**
     * Busca el fichero de [romId] bajo [romsRoots] y, si aparece, persiste la
     * ruta. Devuelve la ruta absoluta o `null` sin escribir nada.
     */
    suspend fun locateAndAttach(
        romId: Int,
        romsRoots: List<String>,
        platformSlug: String,
        fileName: String,
    ): String?
}

/**
 * Datos inmutables necesarios para lanzar un juego. El indicador `needsLocate`
 * de [GameLaunchPlan.Failed] se deriva de [romId]: solo los juegos de RomM
 * ofrecen el selector de fichero.
 */
data class GameLaunchRequest(
    val romId: Int,
    val platformSlug: String,
    val platformLabel: String,
    val localPath: String,
    val fileName: String,
    val knownFileNames: List<String> = emptyList(),
    val overrideFamilyId: String? = null,
    val saveEmulatorId: String? = null,
)

/** Plan de lanzamiento unificado: emulador, APK o fallo con mensaje. */
sealed interface GameLaunchPlan {
    data class Ready(val plan: LaunchPlan.Ready) : GameLaunchPlan

    data class Apk(val apkFile: File) : GameLaunchPlan

    data class Failed(val message: String, val needsLocate: Boolean = false) : GameLaunchPlan
}

/**
 * Resolución perezosa + lanzamiento: si la ruta guardada falta o ya no existe,
 * busca el fichero bajo las raíces de ROMs y persiste la nueva ruta antes de
 * preparar el plan. Es el único camino que usan la Biblioteca y Jugar.
 */
class RomLaunchService(
    private val linker: RomFileLinker,
    private val launcher: GameLauncher,
    private val romsRoots: suspend () -> List<String>,
) {

    /** Prepara [request]; nunca lanza (los fallos de E/S vuelven como plan). */
    suspend fun prepare(request: GameLaunchRequest): GameLaunchPlan {
        var path = request.localPath
        val missing = path.isBlank() || !runCatching { File(path).exists() }.getOrDefault(false)
        if (request.romId > 0 && missing) {
            val linked = try {
                linker.locateAndAttach(
                    romId = request.romId,
                    romsRoots = romsRoots(),
                    platformSlug = request.platformSlug,
                    fileName = request.fileName,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!linked.isNullOrBlank()) path = linked
        }
        if (path.isBlank()) {
            return GameLaunchPlan.Failed(
                tr("launch.error.manual_not_found"),
                needsLocate = request.romId > 0,
            )
        }
        return try {
            launcher.prepare(request.copy(localPath = path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GameLaunchPlan.Failed(
                tr("launch.error.failed", request.platformLabel, e.javaClass.simpleName),
                needsLocate = request.romId > 0,
            )
        }
    }

    /** Arranca un plan ya preparado (emulador o APK); null = aceptado. */
    fun start(activityContext: Context, plan: GameLaunchPlan): String? =
        launcher.start(activityContext, plan)
}
