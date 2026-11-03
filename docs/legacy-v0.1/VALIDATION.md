# Validation record

Validation date: November 1, 2026 (America/Chicago).

The user chose **emulator testing only for now**. This is an emulator-verified implementation, not a claim of physical-device certification or Google Play approval. The separate [physical-device matrix](DEVICE_TEST_MATRIX.md) remains **NOT RUN**.

## Environment

- Windows host; JDK 21; Gradle 8.13; Android Gradle Plugin 8.13.2; Kotlin 2.1.20.
- Java/Kotlin output targets version 17. Android minSdk 29; target/compileSdk 36.
- Android 16 / API 36 Google APIs x86_64 emulator, `Pause_API_36`, using WHPX.
- Display: 480 × 960 pixels, 200 dpi.
- Build fingerprint: `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`.

## Automated checks

| Check | Result | Evidence |
| --- | --- | --- |
| Domain unit tests | PASS: 19 tests | `domain/build/test-results/test/` |
| Configuration and category persistence unit tests | PASS: 8 tests | `data/build/test-results/testDebugUnitTest/` |
| Presentation ownership regression tests | PASS: 3 tests | `feature-intervention/build/test-results/testDebugUnitTest/` |
| Intervention component instrumentation | PASS: 8 tests, including 4 image captures | `feature-intervention/build/outputs/androidTest-results/connected/debug/` |
| App-selector instrumentation | PASS: 2 tests | `feature-app-selector/build/outputs/androidTest-results/connected/debug/` |
| Real cross-app integration | PASS: 1 expanded test; repeated successfully | `app/build/outputs/androidTest-results/connected/debug/` |
| Debug APK | PASS; APK signature verified | `app/build/outputs/apk/debug/app-debug.apk` |
| Optimized release APK | PASS, unsigned | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Android module lint | PASS: no errors | Each Android module's `build/reports/lint-results-debug.html` |

Totals: **30 JVM unit tests and 11 Android instrumentation tests**. Four instrumentation tests capture rendered fixtures; they are not four additional cross-app behavior tests. Lint still reports dependency-update suggestions and the API-31 accessibility metadata attribute on older supported versions; zero errors does not mean zero warnings.

## What the real-app test verified

The opt-in integration fixture discovers installed apps through the production catalog and launches two ordinary installed emulator apps. It does not call the intervention engine's `begin()` method to substitute for interception.

1. Persist ten discovered app selections through the production Room repository, then configure two real targets for the launch sequence.
2. Enable the actual Accessibility Service and await loaded protection settings.
3. Launch a target, observe visible breathing, then require an explicit Start from fresh duration selection.
4. Observe countdown and final decision; Continue opens the original target without immediately retriggering.
5. Leave and reopen that target; a new attempt resets to the preferred starting duration.
6. Cancel to Home, then intercept and cancel the second protected app independently.
7. Open Preview through the actual Settings UI, leave for a protected app, and verify real protection still intervenes. Preview produces no real history record.
8. Switch directly from an active intervention to the second target; record the interrupted original attempt and a fresh correctly targeted intervention.
9. Revoke Accessibility during an intervention; verify interruption and truthful disabled status, then verify the disabled service does not claim to guard the target.
10. Verify completion/outcomes and target identities in the production history repository.

The fixture runs only when explicitly enabled and only on emulator hardware. It restores the original settings/selections/accessibility configuration. It temporarily enables Accessibility through test-only shell privileges; this does not replace the production disclosure and user-consent flow.

## Regression fixes covered

- Coalesce duplicate pending presentations; isolate the intervention task and use `singleTask`.
- Give each activity presentation an ownership token so a stale lifecycle callback cannot cancel its replacement.
- Recheck genuine protected-app transitions after pause/stop instead of losing early foreground events.
- Load protection caches before reporting readiness; replay the latest relevant startup event.
- Measure usable screen height after system-bar insets so compact layouts keep Start reachable.
- Match system-bar icon contrast to the selected theme.
- Always reset the second timer for a new attempt and enforce the 300-second cap in configuration, persistence, engine, and UI.

## Reproduce

From PowerShell, with the SDK configured in `local.properties`:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :feature-intervention:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:assembleRelease lintDebug
.\gradlew.bat --no-parallel :feature-intervention:connectedDebugAndroidTest :feature-app-selector:connectedDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.pauseRunInterception=true'
.\scripts\Capture-Screens.ps1 -SdkPath C:\Android\Sdk -Serial emulator-5554
```

Use an isolated emulator with at least ten discoverable launchable apps and two ordinary test targets. Run the real-app integration separately from the component suites so UI automation sessions do not compete for the same screen. First builds require dependency downloads.

## Screenshots and delivery

The four PNGs in [screenshots](screenshots/) are rendered Compose fixtures for breathing, selection at **5:00**, countdown, and decision. Their YouTube label is a fixture; it does not claim YouTube was used in the real cross-app test. Screenshots are inspected separately from test assertions.

Visual review completed for all four dark-theme captures. The compact selector's Start action and countdown's Cancel action are visible without scrolling; larger text and smaller viewports retain scrolling for access.

Install the **signed debug APK** for local testing. The minified release output is intentionally unsigned and needs release signing before distribution. Artifact hashes are recorded in [APK_SHA256.txt](APK_SHA256.txt).

## Remaining limits

Physical devices, OEM-specific behavior, Android versions other than API 36, reboot/process-death behavior on hardware, notification/deep-link launches, forced background-launch denial, and actual haptic/audio perception have not been physically validated. See the matrix for all outstanding phone scenarios. Foreground detection can occur after the target briefly appears; Close returns Home and does not force-stop another application's process.
