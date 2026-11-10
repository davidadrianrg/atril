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

### Changed

- The Library view, sort, source and card size are now persisted on both platforms and restored on the next launch, using the same preference keys defined in `:core`.
- Android and desktop share one sort implementation with nine criteria, including *Last played*; the toolbar sort menu keeps the full list while the view-options panel offers name, last played and year.
- The chosen disc of a local multidisc group is persisted per game, and removing a grouped local game removes every one of its discs.
- Server-supplied save file names are now validated before any download or write: blank names, absolute paths, path separators, `.`/`..` elements and NUL bytes are rejected, and the resolved target must stay inside the canonical saves directory (symlinks included). A rejected name is skipped without aborting the rest of the sync.
- When a `.cue`, `.gdi` or `.m3u` descriptor and a raw data file share a disc number, the descriptor wins (`.cue` > `.gdi` > `.m3u`), so a multidisc group launches the file an emulator can start.
- 91 unit tests now cover the phase-2 work: the 73 of the first pass plus 18 regression tests added with the review and security fixes. They span view/sort/size persistence, the letter index with filters, local disc grouping and its false positives, the chosen-disc codec, the 8-second cap, save-name validation and the descriptor tie-break.

### Notes

**Launcher improvements**

- Play time and favorites are persisted locally per game in a shared JSON store (app files directory on Android, `~/.config/atril` on Linux). Nothing is sent to the RomM server: there is no server-side play time or favorites sync.
- The gamepad legend is Android only; the desktop app keeps its existing key hints.

**Library phase 2**

- The pre-launch pull only downloads saves that are missing or newer and never uploads or resolves conflicts; the normal save sync and its conflict UI remain authoritative.
- The letter bar indexes the games already loaded, not the whole server library: with paginated RomM results, letters beyond the loaded pages have no target until more pages load.
- The Android emulator is not used in this environment, so the new views and focus behaviour are validated by compilation and JVM unit tests rather than instrumented UI tests.
