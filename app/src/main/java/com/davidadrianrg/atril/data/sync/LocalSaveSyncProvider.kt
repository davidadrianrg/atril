package com.davidadrianrg.atril.data.sync

import android.content.Context
import com.davidadrianrg.atril.core.localgames.LocalGame
import com.davidadrianrg.atril.core.localgames.LocalGamesRepository
import com.davidadrianrg.atril.core.sync.localsave.LocalSaveSync
import com.davidadrianrg.atril.core.sync.platform.SaveHandlerRegistry
import com.davidadrianrg.atril.data.local.RomSyncDatabase
import com.davidadrianrg.atril.data.local.SettingsDataStore
import com.davidadrianrg.atril.data.local.entity.PlatformEntity
import java.io.File

/**
 * Adapta los juegos locales a [LocalSaveSync]: descubre los saves con el
 * handler de su plataforma (los mismos que RomM: Dolphin/PS3 → zip,
 * RetroArch/Eden → fichero) y resuelve la ruta de saves con el mismo orden
 * que el sync de RomM (por juego → por plataforma → por defecto del sistema).
 *
 * Los `package:` (apps Android enlazadas) no llevan saves descubribles y se
 * omiten silenciosamente, igual que los juegos de plataformas sin handler.
 */
class LocalSaveSyncProvider(
    private val context: Context,
    private val database: RomSyncDatabase,
    private val settingsDataStore: SettingsDataStore,
    private val localGames: LocalGamesRepository,
) : LocalSaveSync.Provider {

    override fun games(): List<LocalSaveSync.GameSnapshot> {
        val games = localGames.games.value
        if (games.isEmpty()) return emptyList()
        val platforms = runCatching { database.platformDao().getAllPlatformsBlocking() }
            .getOrDefault(emptyList())
            .associateBy { it.slug }
        val retroBase = settingsDataStore.getRetroArchBasePathBlocking()

        return games.mapNotNull { game -> snapshot(game, platforms[game.platformSlug], retroBase) }
    }

    private fun snapshot(
        game: LocalGame,
        platform: PlatformEntity?,
        retroBase: String,
    ): LocalSaveSync.GameSnapshot? {
        val path = game.path
        if (path.startsWith("package:")) return null

        val romFileName = path.substringAfterLast('/').ifBlank { game.title }
        val emulatorId = platform?.emulatorId
            ?: SaveHandlerRegistry.getDefaultEmulator(game.platformSlug).id
        val savesBasePath = platform?.savesPathOverride?.takeIf { it.isNotBlank() }
            ?: AndroidSavePathResolver.resolveDefault(
                emulatorId = emulatorId,
                platformSlug = game.platformSlug,
                retroArchBase = retroBase,
                context = context,
            )

        return LocalSaveSync.GameSnapshot(
            id = game.id,
            title = game.title,
            platformSlug = game.platformSlug,
            romFileName = romFileName,
            emulatorId = emulatorId,
            savesBasePath = savesBasePath,
            romId = game.romId ?: 0,
            romLocalPath = path,
        )
    }

    // ── Hooks del orquestador ───────────────────────────────────────────

    /** Copia del save perdedor antes de pisarlo: nunca se pierde una versión. */
    override fun backup(gameId: String, fileName: String, file: File) {
        runCatching {
            if (!file.exists()) return
            val dir = File(context.filesDir, "webdav-backups/$gameId").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            file.copyTo(File(dir, "$fileName.$stamp"), overwrite = false)
            // Acotado: guarda solo los 5 últimos por fichero.
            val group = dir.listFiles { f -> f.name.startsWith("$fileName.") }
                ?.sortedBy { it.name } ?: return
            while (group.size > 5) group.first().delete()
        }
    }

    override fun notify(message: String) {
        // Los mensajes de conflicto/restauración van en el Result del sync;
        // aquí no hay canal de notificación (la UI los enseña al terminarlo).
    }
}
