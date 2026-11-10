# Changelog

All notable changes to Atril are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Feature package delivered on 2026-11-10 (launcher improvements).

### Added

- **Play time and last played** — every launch is timed on Android (from the emulator launch until Atril returns to the foreground) and on Linux (from the launch until the window regains focus). The game detail shows *Last played* and *Play time* with relative dates ("just now", "5 min ago", "2 h ago", "yesterday", "3 days ago") and durations ("1 h 23 min", "12 min", "<1 min"), or "Never" when there is no data. Sessions shorter than 5 seconds are ignored.
- **Favorites** — star toggle in the game detail on Android and Linux, a favorite badge on the game card, and a *Favorites* filter in both libraries, saved per game.
- **Continue playing** — a compact row with the five most recently played games (cover, name and play time) on the Android and desktop Play screens. The row is hidden while there is no play data and selecting a game jumps to it in the carousel.
- **Gamepad legend (Android only)** — a discreet, non-interactive bottom bar on the Play screen with the A/B/X/Y glyphs and the current actions (Play, Back, Favorite, Details). X toggles the favorite of the selected game and Y opens its details.
- English and Spanish strings for every new label, action and time format.

### Notes

- Play time and favorites are persisted locally per game in a shared JSON store (app files directory on Android, `~/.config/atril` on Linux). Nothing is sent to the RomM server: there is no server-side play time or favorites sync.
- The gamepad legend is Android only; the desktop app keeps its existing key hints.
