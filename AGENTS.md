# Atril — agent guide

Atril is a Kotlin/Gradle monorepo with two launchers that share their business
logic: an Android app and a Compose Desktop app, both integrating with a RomM
server. This file is for AI agents working in the repository. User-facing
documentation is in `README.md`; the branch workflow is also summarised in
`CLAUDE.md`.

## Repository layout

- `:core` (`core/src/main/kotlin/com/davidadrianrg/atril/core/`) — pure JVM, with
  no Android or Compose imports. Owns the RomM client (`remote/`), the Device
  Sync Protocol (`sync/`), downloads and path mapping (`download/`), play stats
  (`playstats/`), Library logic (`library/`: sorting, A-Z index, view
  preferences, RomM multi-disc grouping), local games and local disc grouping
  (`localgames/`), launch resolution (`launch/`), the translation bundles
  (`resources/i18n/`) and utilities (`util/`).
- `:app` (`app/src/main/java/com/davidadrianrg/atril/`) — Android app in Kotlin
  and Jetpack Compose (Material 3). `data/` holds Room, DataStore, repositories,
  sync, metadata export and updates; `download/` the WorkManager queue; `ui/`
  the screens, view models, components and input; `launch/` the pre-launch save
  pull; `util/` Android permissions.
- `:desktop` (`desktop/src/main/kotlin/com/davidadrianrg/atril/desktop/`) —
  Compose Desktop app. The Library, launcher and detail composables live in
  `DesktopUi.kt`, `DesktopLauncher.kt`, `GameDetail.kt` and `LocalGamesUi.kt`;
  state is in `DesktopAppState.kt`; sync in `DesktopSyncCoordinator.kt`;
  persistence in `DesktopConfig.kt` (`java.util.prefs`) and
  `DesktopLibrary.kt` (library properties).
- `docs/` — design notes. `README.md` / `README.es.md` and `CHANGELOG.md` /
  `CHANGELOG.es.md` are maintained in English and Spanish.

Tests follow each module's convention: `core/src/test/kotlin`,
`app/src/test/java` and `desktop/src/test/kotlin`. JUnit4 is used everywhere;
`:core` adds `kotlinx-coroutines-test` and MockWebServer, `:app` adds MockK,
Turbine, Truth, MockWebServer and Robolectric.

## Environment

This host has no system JDK; Gradle needs the bundled JDK 17:

```bash
export JAVA_HOME=/home/david/.hermes/jdk
export ANDROID_HOME=/home/david/.hermes/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

The build uses a JDK 17 toolchain and `compileSdk`/`targetSdk` 35 (`minSdk` 26).

## Build and test

```bash
./gradlew :app:assembleDebug :desktop:compileKotlin
./gradlew :core:test :desktop:test testDebugUnitTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

There is no ktlint, detekt, spotless or dependency-check plugin configured. Do
not add one: match the style of the surrounding file and prove dependency
hygiene by diffing `gradle/libs.versions.toml` and the module `build.gradle.kts`
files.

## Conventions

- Kotlin, English identifiers and log messages; user-facing text is localized.
- All UI strings go through the `:core` bundles
  `core/src/main/resources/i18n/messages.properties` (English, authoritative)
  and `messages_es.properties` (Spanish). Both must contain exactly the same
  keys and the same positional placeholders; `I18nTest` fails the suite if they
  drift. Do not create `app/src/main/res/values/messages*.properties`.
- No new dependencies, plugins or version-catalog entries without explicit
  approval. Dependencies resolve from Maven Central (and Google for Android).
- Feature-branch workflow: create `feat/<slug>` from a freshly pulled `develop`,
  merge with `git merge --no-ff`, then delete the branch locally and on origin.
  `master` is stable; this workflow never merges into `master` and never
  publishes a release.
- Commit messages follow `type(scope): summary`.

## Gotchas

- The Android emulator is off in this environment. Validate with compilation
  plus JVM unit tests; do not start an AVD or claim instrumented results.
- The Library lists RomM games only. Local games live in the Play screen, so the
  Library's *Source: Local* option is an explicit empty state, not a data
  source.
- RomM multi-disc grouping (`library/MultiDiscGrouper`) and local multi-disc
  grouping (`localgames/LocalDiscGrouper`) are separate code paths with
  different rules. Do not merge them or route local games through RomM
  download, delete or sync flows.
- The pre-launch save pull is capped at 8 seconds by design
  (`sync/SavePull.DEFAULT_TIMEOUT_MS`) and must never block, cancel or fail a
  launch. It only downloads safe deltas; it never uploads or resolves
  conflicts.
- Library preferences use the canonical keys in
  `library/LibraryPreferenceKeys` on both platforms. Do not invent per-platform
  spellings or rename existing keys.
- Desktop tests run against an isolated `user.home` and `java.util.prefs` root
  configured in `desktop/build.gradle.kts`; restore any global preference your
  test touches.
