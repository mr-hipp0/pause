# Validation record — Pause 0.3.0

Validation date: November 2, 2026 (America/Chicago).

This release refreshes the visual system and all main screens, including measured full-height intervention layouts. Validation uses an isolated emulator as requested. Physical-device checks for this version remain **NOT RUN** in [DEVICE_TEST_MATRIX.md](DEVICE_TEST_MATRIX.md). Previous release evidence is archived in [legacy-v0.2](legacy-v0.2/) and [legacy-v0.1](legacy-v0.1/).

## Environment

- Windows, JDK 21, Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.1.20; Java/Kotlin bytecode 17.
- minSdk 29; target/compileSdk 36; versionCode 3, versionName 0.3.0.
- Android 16/API 36 Google APIs x86_64 emulator `Pause_API_36`, WHPX.
- Standard display: 480 × 960 pixels at 200 dpi; tall display: 480 × 1120 pixels; large-text profile: 480 × 960 at 130% system text size. Component checks additionally use 180% text in a 480dp viewport.
- Fingerprint: `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`.

## Automated checks

| Check | Result | Evidence |
| --- | --- | --- |
| Domain | PASS: 24 tests | `domain/build/test-results/test/` |
| Configuration/category persistence | PASS: 10 tests | `data/build/test-results/testDebugUnitTest/` |
| Presentation ownership | PASS: 3 tests | `feature-intervention/build/test-results/testDebugUnitTest/` |
| Intervention UI | PASS: 10 tests, including 3 image captures | `feature-intervention/build/outputs/androidTest-results/connected/debug/` |
| App selector UI | PASS: 2 tests | `feature-app-selector/build/outputs/androidTest-results/connected/debug/` |
| Real cross-app usage | PASS: 1 expanded test | `.tooling/aesthetic-app-checks.log` (combined service/visual run) |
| Full-window visual fixture | PASS: 1 fixture repeated across 3 display/text profiles | `app/build/outputs/androidTest-results/connected/debug/` |
| Room/DataStore v1 upgrade | PASS: 1 test | `data/build/outputs/androidTest-results/connected/debug/` |
| Signed debug APK | PASS: APK Signature Scheme v2 verified; same certificate as 0.2.0 | `app/build/outputs/apk/debug/` |
| Install over 0.2.0 | PASS: update accepted; installed versionCode 3/versionName 0.3.0 | Emulator package-manager update |
| Optimized unsigned release | PASS | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Android module lint | PASS: 9 modules, zero errors | Each module's `build/reports/lint-results-debug.html` |

Totals: **37 JVM tests and 15 distinct Android instrumentation tests**. Three Android tests capture component images, and one captures full-window fixtures. Repeating the visual fixture across profiles does not add distinct tests.

Lint retains warnings for available dependency updates, legacy resource qualifiers/unused fallback drawables, monochrome fallback metadata, and older-API accessibility metadata. Zero errors does not mean zero warnings.

The intervention suite includes full-viewport/footer placement, expansion or recentering when quotes are disabled, and a 480dp viewport at 180% text size. It verifies genuine scrolling reaches the reflection and cancellation, then verifies the chooser starts at the top. Existing slider limits/drag endpoints, explicit Start, preview, cancellation, and quote stability remain covered.

## Full-window and visual review

`VisualScreenshotsTest` uses the actual MainActivity and intervention Activity rather than a cropped component fixture. It captures Today, app selection, Insights, Settings, pending settings, breathing, usage selection, and setup in both themes. It also changes a breathing preset, verifies the sticky Save action remains reachable, saves through the production repository, and previews the result.

The visual fixture seeds two sample protected apps and sample history to inspect populated layouts. These are clearly test data, not measured personal usage. Protection is disabled during this visual fixture; the separate cross-app test exercises the real active Accessibility Service. Preferences, selections, and history are restored in `finally`. Both fixtures require explicit opt-in and reject physical-device hardware.

Visual review completed for the refreshed breathing/chooser, Today, selector, Insights, settings/pending Save, and setup in both themes. Standard, tall, and larger-text captures retain readable controls and reachable actions. The Insights chart was also inspected with its range labels and populated fixture data. See [standard captures](screenshots/standard/), [tall captures](screenshots/tall/), and [large-text captures](screenshots/large-text/).

The three component PNGs are breathing, selection at 5:00, and breathing without quotes. Their YouTube label is fixture data, not evidence of intercepting an installed YouTube app. The full-window captures use ordinary installed emulator apps.

## Behavioral regression

The real cross-app fixture verifies three repeated usage expiries, visible breathing and fresh selection on every cycle, immediate target access after Start, measured usage history, notification-shade pause/resume, early departure/discard/reopen, switching protected targets, preview isolation, and service revocation. It uses real launcher intents and the actual Accessibility Service rather than calling `engine.begin()` to substitute for interception.

The previous timestamp-ordering fix remains in place: delayed older window events cannot revoke a newer confirmed target, own guard callbacks do not advance foreground ordering, and stale rechecks are cancelled at handoff. No screen-content retrieval or Internet permission was added.

## Reproduce

With an isolated emulator and configured SDK:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :feature-intervention:testDebugUnitTest
.\gradlew.bat --no-parallel :feature-intervention:connectedDebugAndroidTest :feature-app-selector:connectedDebugAndroidTest
.\gradlew.bat --no-parallel :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.pauseRunInterception=true' '-Pandroid.testInstrumentationRunnerArguments.pauseRunVisuals=true' :data:connectedDebugAndroidTest
.\gradlew.bat :app:assembleDebug :app:assembleRelease lintDebug
.\scripts\Capture-Screens.ps1
.\scripts\Collect-Visuals.ps1 -Profile standard
```

Run device suites sequentially so UI automation sessions do not compete. Full-window captures are saved to the emulator Download directory and copied to `docs/screenshots`. Use `pauseVisualProfile` to label additional display profiles.

## Delivery and limits

Install **Pause-0.3.0-debug.apk** over the existing app to retain settings. The release APK is unsigned and requires release signing for distribution. Final artifact hashes are in [APK_SHA256.txt](APK_SHA256.txt).

Phone hardware, other Android versions, OEM behavior, split-screen/Picture-in-Picture, notification/deep-link launches, forced background-launch denial, reboot/force-stop recovery, and perceived haptics/audio remain outside this emulator result. Accessibility can observe a protected app after it briefly appears. Disabling the service stops protection; Pause cannot force-stop another app.



