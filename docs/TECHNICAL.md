# Pause technical documentation

This document holds implementation, behavior, and development details intentionally kept out of the product-focused README.

## Session behavior

Pause uses a repeating intervention loop:

`BREATHING → SELECTION → LAUNCHING → USING`

- Every opening of a protected app starts breathing and a fresh duration choice.
- The allowance is capped at 300 seconds and begins only after the target app is confirmed in the foreground.
- Time is charged only while the protected app is visibly eligible. Keyboards count as part of that app; brief covering system windows pause charging.
- Staying in the app until expiry starts another breathing-and-selection cycle.
- Leaving for Home, Recents, another app, or the lock screen ends the allowance and discards unused time.
- Switching protected apps ends the first allowance and starts a fresh intervention for the second.
- Cancellation returns Home and grants no access.
- A failed launch, revoked service, or process restart never restores an unlimited allowance.
- Preview exercises breathing and selection without opening a target or adding usage history.

Each intervention has an immutable target and unique ID. Presentation ownership, event timestamps, and generation checks prevent delayed accessibility events or stale Activity callbacks from changing a newer session.

## Storage and history

Pause stores preferences in DataStore and protection/history records in Room. Android backup and device-to-device extraction are disabled.

Room schema version 2 migrates version 1 data without discarding protected apps, overrides, consent, theme, or history. Old waiting-mode records remain marked `LEGACY_WAIT`; they are never reinterpreted as measured app use. Current `USAGE` records store the chosen allowance, confirmed usage start, observed foreground milliseconds, outcome, and completion time.

History writes are serialized. Reset waits for queued writes before clearing storage. Quote rotation uses a separate local DataStore; Preview does not consume real rotation, Delete history preserves it, and Reset Pause clears it.

## Architecture

| Module | Responsibility |
| --- | --- |
| `app` | Dependency graph, onboarding, navigation, permissions, cleanup |
| `core` | Shared Compose theme and dependency contracts |
| `domain` | Models, foreground-use engine, repository contracts |
| `data` | Room/DataStore, migration, app catalog, offline quotes |
| `feature-dashboard` | Protection overview and today's measured usage |
| `feature-app-selector` | App discovery, search, categories, selection |
| `feature-intervention` | Breathing, quotes, duration selector, launch/cancel |
| `feature-settings` | Global/per-app configuration, Preview, reset |
| `feature-statistics` | Measured history and charts |
| `service-interception` | Accessibility events, timing, presentation fallback |

## Build

Requirements: JDK 21, Android SDK platform 36, Android build tools 35.0.0, and platform tools. Gradle emits Java/Kotlin 17 bytecode. The minimum supported Android version is Android 10/API 29.

Configure the ignored `local.properties`:

```properties
sdk.dir=C\:/Android/Sdk
```

Common PowerShell commands:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :feature-intervention:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:assembleRelease lintDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.pause.app/.MainActivity
```

The optimized release APK is unsigned and needs a release key before distribution. Tooling, caches, local configuration, build output, keystores, and packaged artifacts are excluded from Git.

## Privacy and platform limits

- Pause requests no `INTERNET` permission and contains no account, backend, analytics, advertising SDK, or cloud sync.
- Accessibility observes package/window transitions with `canRetrieveWindowContent=false`; it does not retrieve screen text or typed content.
- App discovery uses scoped launcher queries rather than `QUERY_ALL_PACKAGES`.
- Android can briefly show a target before its foreground transition is observed.
- Background-launch restrictions or manufacturer behavior can delay presentation. A permitted notification is the fallback.
- Disabling, force-stopping, or uninstalling Pause stops protection. Pause cannot force-stop another app.
- Google Play distribution requires the applicable Accessibility API declaration, disclosure, listing information, and review.

Platform references: [package visibility](https://developer.android.com/training/package-visibility/declaring), [background activity restrictions](https://developer.android.com/guide/components/activities/background-starts), and [Google Play Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en).

## Validation and visual fixtures

Detailed results and known limits are in [VALIDATION.md](VALIDATION.md). Physical-device scenarios remain in [DEVICE_TEST_MATRIX.md](DEVICE_TEST_MATRIX.md).

```powershell
.\gradlew.bat :feature-intervention:assembleDebugAndroidTest
.\scripts\Capture-Screens.ps1
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.pause.app.VisualScreenshotsTest' '-Pandroid.testInstrumentationRunnerArguments.pauseRunVisuals=true'
.\scripts\Collect-Visuals.ps1 -Profile standard
```

Run UI automation sequentially on an isolated emulator. Screenshot fixtures contain demo data and do not claim personal usage or physical-device certification.

## Quote collection

Pause 0.4.0 bundles a small local ZenQuotes cache with author attribution and a source link. It performs no runtime quote requests. Provenance, import validation, caching behavior, and redistribution limitations are documented in [quotes/README.md](quotes/README.md).
