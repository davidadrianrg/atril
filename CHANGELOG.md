# Changelog

All notable changes to Atril are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

**Launcher improvements (2026-11-10)**

- **Play time and last played** — every launch is timed on Android (from the emulator launch until Atril returns to the foreground) and on Linux (from the launch until the window regains focus). The game detail shows *Last played* and *Play time* with relative dates ("just now", "5 min ago", "2 h ago", "yesterday", "3 days ago") and durations ("1 h 23 min", "12 min", "<1 min"), or "Never" when there is no data. Sessions shorter than 5 seconds are ignored.
- **Favorites** — star toggle in the game detail on Android and Linux, a favorite badge on the game card, and a *Favorites* filter in both libraries, saved per game.
- **Continue playing** — a compact row with the five most recently played games (cover, name and play time) on the Android and desktop Play screens. The row is hidden while there is no play data and selecting a game jumps to it in the carousel.
- **Gamepad legend (Android only)** — a discreet, non-interactive bottom bar on the Play screen with the A/B/X/Y glyphs and the current actions (Play, Back, Favorite, Details). X toggles the favorite of the selected game and Y opens its details.
- English and Spanish strings for every new label, action and time format.

**Library phase 2 (2026-11-10)**

- **Cover flow** — an optional Library view on Android and Linux: covers tilted in 3D with a subtle bottom reflection, D-pad and keyboard navigation, and A/Enter opening the game detail. The grid stays the default, the choice is persisted, and skeleton loading and infinite scroll work in both views.
- **View options panel** — a contextual panel opened from the tune button in the Library bar, R1 on Android and R on Linux (not while the search field has focus). It groups *Sort* (name, last played, year when available), *Source* (all, local, RomM), *View* (grid, cover flow) and *Size* (small, medium, large). Changes apply immediately, the panel stays open and the values persist across sessions. Because the Library lists RomM games, *Source: Local* shows an explicit empty state explaining that local games live in the Play screen.
- **A-Z index** — an A-Z plus # letter bar on the right edge of the Library when the filtered list has more than 40 games, on both platforms. Down from the grid enters it, Left/Right moves, A/Enter jumps and Up returns. It indexes the filtered result (search and filters included) and jumps within the games already loaded; it reserves edge space and fades when idle.
- **Multi-disc local games** — local files with a `(Disc 1)`, `(Disk 2)`, `(CD 3)` or `(Disc 1 of 4)` mark are grouped into one Play entry with an *N discs* badge. The detail lets you pick the disc (disc 1 by default, or the sibling `.m3u` when present) and the choice is remembered per game. Launching uses the chosen disc and keeps the `.m3u` behaviour; RomM games and unmarked similar names are untouched.
- **Pre-launch save pull** — with save sync enabled, launching a RomM game first pulls saves that are new or newer from the server under a hard 8-second cap, with an *Updating saves…* notice. A slow or failing server never blocks or prevents the launch. Games without a RomM id or with save sync disabled keep the previous behaviour.
- English and Spanish strings for every new label, control and notice, with identical keys and placeholders in both bundles.

**Manual downloads (2026-11-10)**

- **Automatic linking when marking a game** — marking a RomM game as downloaded now looks for its file in the platform folder (`<ROMs>/<platform>/<file name>`) and, when it is not there, in a bounded search by exact name: up to six levels deep and 5000 entries, skipping hidden entries and symbolic links. When the file is found its path is saved and the game is playable at once; otherwise the manual mark is kept as before.
- **Locate file** — the detail of a manually marked game shows a new *Locate file* button while it has no path. On Android it opens the system document picker and copies the chosen file into `<ROMs>/<platform>/`, asking before replacing a file with the same name, refusing files over 16 GiB or that do not fit in the free space, and asking you to configure the ROMs folder when none is set; on Linux it uses the native file dialog and saves the chosen path without copying anything.
- **Lookup when playing** — launching a game that is still marked manually retries the search first: if the file has appeared it is linked and the game starts; if it is still missing, Atril says so. On Android the notice offers *Locate file* and opens the document picker without leaving the Play screen; on Linux the notice only explains the problem and the *Locate file* button is in the game detail.
- **APK games on Android** — a `.apk` (or a game from an `android` platform) no longer goes to an emulator: if the app is already installed Atril opens it, otherwise it opens the system installer. On Linux an APK is refused with a clear message.
- English and Spanish strings for every new button, notice and error message.

### Changed

- The Library view, sort, source and card size are now persisted on both platforms and restored on the next launch, using the same preference keys defined in `:core`.
- Android and desktop share one sort implementation with nine criteria, including *Last played*; the toolbar sort menu keeps the full list while the view-options panel offers name, last played and year.
- The chosen disc of a local multidisc group is persisted per game, and removing a grouped local game removes every one of its discs.
- Server-supplied save file names are now validated before any download or write: blank names, absolute paths, path separators, `.`/`..` elements and NUL bytes are rejected, and the resolved target must stay inside the canonical saves directory (symlinks included). A rejected name is skipped without aborting the rest of the sync.
- When a `.cue`, `.gdi` or `.m3u` descriptor and a raw data file share a disc number, the descriptor wins (`.cue` > `.gdi` > `.m3u`), so a multidisc group launches the file an emulator can start.
- Linking a file to a manually marked game turns it into a normal download: the path is what defines the state, so the *Manual* badge, *Remove mark* and *Locate file* disappear and the usual download actions (delete the file, move it to the SD card, sync settings) appear instead. This is intended, not a defect, and it is why no new database column was needed.
- 91 unit tests now cover the phase-2 work: the 73 of the first pass plus 18 regression tests added with the review and security fixes. They span view/sort/size persistence, the letter index with filters, local disc grouping and its false positives, the chosen-disc codec, the 8-second cap, save-name validation and the descriptor tie-break.

### Fixed

**Save sync security hardening (2026-11-10)**

- Save archives use one shared member rule for zip and tar alike: absolute paths, `..` components, NUL bytes, backslashes and Windows drive letters are rejected, and every resolved target must stay inside the canonical saves folder. A bad zip entry is skipped without aborting the rest of the sync.
- PS3 restores no longer take the destination folder name from the archive on trust: the name (first archive entry, existing local folder or serial) must be a plain, safe name and the folder must resolve strictly inside `…/savedata`. The whole archive is validated first, then unpacked into a hidden staging folder inside `savedata`, and only once the tree is complete is the existing folder cleared and the staged contents moved in; a failure while unpacking leaves the existing save untouched.
- Android and PS2 tar restores on rooted devices are validated and unpacked as the app user into an app-owned staging folder; root is used only afterwards, on the validated tree, to keep the owner and swap the folder in. Archives with absolute or traversal names, links, devices, truncated members or more than one top-level folder are rejected before any privileged step.
- The server-provided `platform_slug` used in save and gamelist paths must match `^[a-z0-9][a-z0-9._-]{0,63}$`. Invalid values are dropped at the RomM data ingress (stored as empty or `unknown`, never a parse failure) and rejected again where a save path is built, so values persisted before this fix cannot escape the saves folder. Valid slugs are used verbatim.
- Sync log lines no longer carry raw exception messages or server-chosen save names: names lose control characters and are length-capped, and failures log a fixed classification plus the exception class name.
- Deliberate behavior changes: a rejected or corrupt tar restore leaves the existing save untouched instead of deleting it first; a PS3 archive containing any unsafe entry is rejected as a whole instead of partially extracted; zip entries containing a backslash are skipped.
- 61 new regression tests cover these rules with real temporary trees and no root (598 JVM unit tests in total, all green).

**Download and save-path security hardening (2026-11-10)**

- ROM download zips go through a single extractor: `ZipStreamExtractor` (`:core/download`) serves both the Android `DownloadWorker` and the desktop `DownloadEngine`, and resolves every entry with `SaveNames.resolveArchiveEntry`. Entries with `..`, an absolute path, a NUL byte, a backslash, a Windows drive letter or a sibling folder that merely shares the destination prefix are skipped without aborting the archive, and a failed or cancelled extraction removes the files it had created. The "files extracted" count reflects only the files actually written.
- Filesystem-derived names interpolated into a `tar` command run with root (the Android app folder, the PS2 memory card and serial folder) are validated with `RootCommandNames` (`:core/sync`): ASCII charset `^[A-Za-z0-9][A-Za-z0-9._ -]{0,199}$`, first character alphanumeric, and canonical containment under the configured base before the command is built. `RootShell.sq` remains the escaping barrier, and names that arrive inside an archive keep using `SaveNames.isSafeSaveFileName`.
- Save restores no longer delete a destination with an unvalidated `deleteRecursively`: one `SafeDelete.clearDirectoryContents(base, target)` (`:core/util`) replaces the seven deletion sites in the save handlers (the six un-canonicalized ones plus the private PS3 helper, which already canonicalized) and the `TarExtractor` staging cleanup. It empties the target but never deletes it, never follows symbolic links at any depth, deletes a link as an entry without destroying its target (even when the target is inside the base), descends only into real directories after revalidating their canonical containment inside the base, and deletes only children that stay inside it. The restore result is unchanged: previous saves are still replaced, PS2 keeps its merge mode and handlers keep their existing `mkdirs` or staging step.
- `PathMapper.getPlatformDir` sanitizes the platform slug with the single `PlatformSlug` rule after the existing trim and lowercase, so a hostile value never reaches the `File` constructor. A missing or blank slug keeps the `unknown` sentinel and an invalid non-empty one falls back to it; the Android `DownloadWorker` rejects an invalid non-empty slug with the existing invalid-platform message before creating any folder.
- 40 new regression tests cover these rules with real temporary trees, zip streams and symlinks where the filesystem supports them. The full suite is 638 JVM unit tests: 636 green and the 2 pre-existing real-server skips.
- No new dependency, plugin, schema column, preference key or i18n string.

### Notes

**Launcher improvements**

- Play time and favorites are persisted locally per game in a shared JSON store (app files directory on Android, `~/.config/atril` on Linux). Nothing is sent to the RomM server: there is no server-side play time or favorites sync.
- The gamepad legend is Android only; the desktop app keeps its existing key hints.

**Library phase 2**

- The pre-launch pull only downloads saves that are missing or newer and never uploads or resolves conflicts; the normal save sync and its conflict UI remain authoritative.
- The letter bar indexes the games already loaded, not the whole server library: with paginated RomM results, letters beyond the loaded pages have no target until more pages load.
- The Android emulator is not used in this environment, so the new views and focus behaviour are validated by compilation and JVM unit tests rather than instrumented UI tests.

**Manual downloads**

- The lookup only reads the filesystem: it never creates, moves or deletes files. On Android *Locate file* always copies into the primary ROMs folder, and a document that already lives under a configured ROMs folder is linked in place instead of duplicated. The copy writes a `.part` file and renames it at the end, and it rejects a target folder or partial file that is a symbolic link, as well as a destination outside the configured ROMs folder.
- There is no schema change, no new preference and no new dependency, and the regular RomM download flow (queue, parallel downloads, hash verification, resume) and local games are untouched.
- APK games are an Android feature only: on Linux the file is detected and refused before the emulator planner, so the error is explicit instead of "no emulator configured".
