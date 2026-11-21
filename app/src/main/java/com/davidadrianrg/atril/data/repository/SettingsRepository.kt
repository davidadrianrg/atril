package com.davidadrianrg.atril.data.repository

import com.davidadrianrg.atril.data.local.SettingsDataStore
import com.davidadrianrg.atril.data.local.ServerConfig
import com.davidadrianrg.atril.core.library.CoverStyle
import com.davidadrianrg.atril.core.library.LibraryCardSize
import com.davidadrianrg.atril.core.library.LibrarySort
import com.davidadrianrg.atril.core.library.LibrarySource
import com.davidadrianrg.atril.core.library.LibraryViewMode
import com.davidadrianrg.atril.core.library.LibraryViewPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Wrapper around SettingsDataStore for clean access from ViewModels.
 */
class SettingsRepository(private val dataStore: SettingsDataStore) {
    val settings: Flow<ServerConfig> = dataStore.settings
    val syncWifiOnly: Flow<Boolean> = dataStore.syncWifiOnly
    val syncChargingOnly: Flow<Boolean> = dataStore.syncChargingOnly
    val skippedVersion: Flow<String> = dataStore.skippedVersion
    val conflictPolicy: Flow<String> = dataStore.conflictPolicy

    suspend fun setLocalOnly(enabled: Boolean) = dataStore.setLocalOnly(enabled)
    suspend fun clientDeviceId(): String = dataStore.clientDeviceId()
    suspend fun disconnectRomM() = dataStore.disconnectRomM()
    suspend fun setServerUrl(url: String) = dataStore.setServerUrl(url)
    suspend fun setApiKey(key: String) = dataStore.setApiKey(key)
    suspend fun setRomsRootPath(path: String) = dataStore.setRomsRootPath(path)
    suspend fun setSecondaryRomsPath(path: String) = dataStore.setSecondaryRomsPath(path)
    suspend fun setDualRomsPathsEnabled(enabled: Boolean) = dataStore.setDualRomsPathsEnabled(enabled)
    suspend fun setMaxConcurrentDownloads(max: Int) = dataStore.setMaxConcurrentDownloads(max)
    suspend fun setWifiOnlyDownloads(enabled: Boolean) = dataStore.setWifiOnlyDownloads(enabled)
    suspend fun setSyncWifiOnly(enabled: Boolean) = dataStore.setSyncWifiOnly(enabled)
    suspend fun setSyncChargingOnly(enabled: Boolean) = dataStore.setSyncChargingOnly(enabled)
    suspend fun setSkippedVersion(version: String) = dataStore.setSkippedVersion(version)
    suspend fun setConflictPolicy(policyId: String) = dataStore.setConflictPolicy(policyId)
    suspend fun setRetroArchBasePath(path: String) = dataStore.setRetroArchBasePath(path)
    fun retroArchCore(platformSlug: String): Flow<String?> = dataStore.retroArchCore(platformSlug)
    suspend fun setRetroArchCore(platformSlug: String, core: String?) = dataStore.setRetroArchCore(platformSlug, core)
    fun getRetroArchCoreBlocking(platformSlug: String): String? = dataStore.getRetroArchCoreBlocking(platformSlug)
    fun getInstalledLinksBlocking(): Map<String, String> = dataStore.getInstalledLinksBlocking()
    suspend fun setInstalledLink(romId: Int, packageName: String?) = dataStore.setInstalledLink(romId, packageName)
    fun getGameBackgroundBlocking(romId: Int): String? = dataStore.getGameBackgroundBlocking(romId)
    suspend fun setGameBackground(romId: Int, path: String?) = dataStore.setGameBackground(romId, path)
    suspend fun setLanguage(tag: String) = dataStore.setLanguage(tag)
    suspend fun setTheme(id: String) = dataStore.setTheme(id)
    val theme: Flow<String> = dataStore.theme
    val companionMode: Flow<Boolean> = dataStore.companionMode
    val webdavUrl: Flow<String> = dataStore.webdavUrl
    val webdavUser: Flow<String> = dataStore.webdavUser
    val webdavPass: Flow<String> = dataStore.webdavPass
    val webdavEnabled: Flow<Boolean> = dataStore.webdavEnabled
    val webdavLastSync: Flow<String> = dataStore.webdavLastSync
    val launcherBackground: Flow<String> = dataStore.launcherBackground
    val launcherBlur: Flow<Float> = dataStore.launcherBlur
    val scraperCredentials = dataStore.scraperCredentials
    suspend fun setScraperCredentials(c: com.davidadrianrg.atril.core.localgames.ScraperCredentials) =
        dataStore.setScraperCredentials(c)

    suspend fun setLauncherBackground(mode: String) = dataStore.setLauncherBackground(mode)

    suspend fun setCompanionMode(enabled: Boolean) = dataStore.setCompanionMode(enabled)

    fun getCompanionModeBlocking(): Boolean = dataStore.getCompanionModeBlocking()

    suspend fun setWebDav(url: String, user: String, pass: String) {
        dataStore.setWebDavUrl(url); dataStore.setWebDavUser(user); dataStore.setWebDavPass(pass)
    }
    suspend fun setWebDavEnabled(enabled: Boolean) = dataStore.setWebDavEnabled(enabled)
    suspend fun setWebDavLastSync(summary: String) = dataStore.setWebDavLastSync(summary)
    fun getWebDavUrlBlocking(): String = dataStore.getWebDavUrlBlocking()
    fun getWebDavUserBlocking(): String = dataStore.getWebDavUserBlocking()
    fun getWebDavPassBlocking(): String = dataStore.getWebDavPassBlocking()
    fun getWebDavEnabledBlocking(): Boolean = dataStore.getWebDavEnabledBlocking()

    suspend fun setLauncherBlur(radiusDp: Float) = dataStore.setLauncherBlur(radiusDp)
    suspend fun setSaveSyncEnabled(enabled: Boolean) = dataStore.setSaveSyncEnabled(enabled)
    suspend fun setSaveSyncWatchEnabled(enabled: Boolean) = dataStore.setSaveSyncWatchEnabled(enabled)

    val retroArchBasePath: Flow<String> = dataStore.retroArchBasePath
    /** Vista, orden, origen, tamaño y estilo de carátula de la biblioteca, ya validados. */
    val libraryViewPreferences: Flow<LibraryViewPreferences> = dataStore.libraryViewPreferences
    suspend fun setLibraryViewMode(mode: LibraryViewMode) = dataStore.setLibraryViewMode(mode)
    suspend fun setLibrarySort(sort: LibrarySort) = dataStore.setLibrarySort(sort)
    suspend fun setLibrarySource(source: LibrarySource) = dataStore.setLibrarySource(source)
    suspend fun setLibraryCardSize(size: LibraryCardSize) = dataStore.setLibraryCardSize(size)
    suspend fun setLibraryCoverStyle(style: CoverStyle) = dataStore.setLibraryCoverStyle(style)
    /** Grupo local -> disco elegido (ya decodificado). */
    val localDiscChoices: Flow<Map<String, String>> = dataStore.localDiscChoices
    suspend fun setLocalDiscChoice(groupKey: String, choiceId: String?) =
        dataStore.setLocalDiscChoice(groupKey, choiceId)
    val launcherDefaultDisplay: Flow<String> = dataStore.launcherDefaultDisplay
    val launchEmulatorOverrides: Flow<Map<String, String>> = dataStore.launchEmulatorOverrides
    suspend fun setLauncherDefaultDisplay(mode: String) = dataStore.setLauncherDefaultDisplay(mode)
    suspend fun setLaunchEmulatorOverride(platformSlug: String, familyId: String?) =
        dataStore.setLaunchEmulatorOverride(platformSlug, familyId)
    val language: Flow<String> = dataStore.language
    val saveSyncEnabled: Flow<Boolean> = dataStore.saveSyncEnabled
    val saveSyncIntervalMinutes: Flow<Int> = dataStore.saveSyncIntervalMinutes
    val saveSyncWatchEnabled: Flow<Boolean> = dataStore.saveSyncWatchEnabled
    val lastSyncTimestamp: Flow<Long> = dataStore.lastSyncTimestamp
    val lastSyncSummary: Flow<String> = dataStore.lastSyncSummary
    val lastSyncConflictsJson: Flow<String> = dataStore.lastSyncConflictsJson
    val lastSyncFailedJson: Flow<String> = dataStore.lastSyncFailedJson
    val esdeDataDir: Flow<String> = dataStore.esdeDataDir
    val mediaDownloadPath: Flow<String> = dataStore.mediaDownloadPath
    val esdeExportEnabled: Flow<Boolean> = dataStore.esdeExportEnabled
    val mediaDownloadEnabled: Flow<Boolean> = dataStore.mediaDownloadEnabled

    suspend fun setLastSync(timestamp: Long, summary: String) =
        dataStore.setLastSync(timestamp, summary)
    suspend fun setLastSyncConflicts(json: String) =
        dataStore.setLastSyncConflictsJson(json)
    suspend fun setLastSyncFailed(json: String) =
        dataStore.setLastSyncFailedJson(json)
    suspend fun setSaveSyncIntervalMinutes(minutes: Int) =
        dataStore.setSaveSyncIntervalMinutes(minutes)
    suspend fun setEsdeDataDir(path: String) = dataStore.setEsdeDataDir(path)
    val autoPhysicalMedia: Flow<Boolean> = dataStore.autoPhysicalMedia
    suspend fun setAutoPhysicalMedia(enabled: Boolean) = dataStore.setAutoPhysicalMedia(enabled)
    suspend fun setMediaDownloadPath(path: String) = dataStore.setMediaDownloadPath(path)
    suspend fun setEsdeExportEnabled(enabled: Boolean) = dataStore.setEsdeExportEnabled(enabled)
    suspend fun setMediaDownloadEnabled(enabled: Boolean) =
        dataStore.setMediaDownloadEnabled(enabled)
}
