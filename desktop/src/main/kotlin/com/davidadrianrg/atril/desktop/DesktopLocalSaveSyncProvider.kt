package com.davidadrianrg.atril.desktop

import com.davidadrianrg.atril.core.localgames.LocalGame
import com.davidadrianrg.atril.core.sync.localsave.LocalSaveSync
import com.davidadrianrg.atril.core.sync.platform.SaveHandlerRegistry
import java.io.File

/**
 * Adapta los juegos locales del escritorio a [LocalSaveSync] (misma idea que
 * el provider de Android: mismos handlers, mismas rutas de saves).
 */
class DesktopLocalSaveSyncProvider(
    private val state: DesktopAppState,
) : LocalSaveSync.Provider {

    override fun games(): List<LocalSaveSync.GameSnapshot> =
        state.localGames.games.value.mapNotNull(::snapshot)

    private fun snapshot(game: LocalGame): LocalSaveSync.GameSnapshot? {
        val path = game.path
        if (path.startsWith("package:")) return null
        val romFile = File(path)
        val platform = state.library.platform(game.platformSlug)
        val emulatorId = platform.emulatorId
            ?: SaveHandlerRegistry.getDefaultEmulator(game.platformSlug).id
        val savesBasePath = platform.savesPathOverride?.takeIf { it.isNotBlank() }
            ?: SaveHandlerRegistry.getDefaultSavesPath(emulatorId, game.platformSlug, "")

        return LocalSaveSync.GameSnapshot(
            id = game.id,
            title = game.title,
            platformSlug = game.platformSlug,
            romFileName = romFile.name.ifBlank { game.title },
            emulatorId = emulatorId,
            savesBasePath = savesBasePath,
            romId = game.romId ?: 0,
            romLocalPath = path,
        )
    }

    override fun backup(gameId: String, fileName: String, file: File) {
        runCatching {
            if (!file.exists()) return
            val dir = File(DesktopConfig.configDir, "webdav-backups/$gameId").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            file.copyTo(File(dir, "$fileName.$stamp"), overwrite = false)
            val group = dir.listFiles { f -> f.name.startsWith("$fileName.") }?.sortedBy { it.name } ?: return
            while (group.size > 5) group.first().delete()
        }
    }

    override fun notify(message: String) {
        // Los mensajes van en el Result; DesktopNotifier es para avisos del sistema.
    }
}
