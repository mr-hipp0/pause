# Validation record — Pause 0.2.0

Validation date: November 2, 2026 (America/Chicago).

The requested workflow is **emulator testing only**. These results cover the revised foreground-usage flow. Physical-device testing of 0.2.0 remains **NOT RUN** in [DEVICE_TEST_MATRIX.md](DEVICE_TEST_MATRIX.md). The user reported the earlier APK worked on their phone; that is not a test result for this update. The previous implementation report and obsolete waiting-flow screenshots are archived under [legacy-v0.1](legacy-v0.1/).

## Environment

- Windows, JDK 21, Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.1.20; Java/Kotlin bytecode 17.
- minSdk 29, target/compileSdk 36; versionCode 2, versionName 0.2.0.
- Android 16/API 36 Google APIs x86_64 emulator `Pause_API_36`, WHPX, 480 × 960 pixels at 200 dpi.
- Fingerprint: `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`.

## Automated checks

| Check | Result | Evidence |
| --- | --- | --- |
| Domain | PASS: 24 tests | `domain/build/test-results/test/` |
| Configuration/category persistence | PASS: 10 tests | `data/build/test-results/testDebugUnitTest/` |
| Presentation ownership | PASS: 3 tests | `feature-intervention/build/test-results/testDebugUnitTest/` |
| Intervention UI | PASS: 7 tests, including 2 captures | `feature-intervention/build/outputs/androidTest-results/connected/debug/` |
| App selector UI | PASS: 2 tests | `feature-app-selector/build/outputs/androidTest-results/connected/debug/` |
| Actual Room/DataStore upgrade | PASS: 1 test | `data/build/outputs/androidTest-results/connected/debug/` |
| Real cross-app service integration | PASS: 1 expanded test; repeated after final service changes | `app/build/outputs/androidTest-results/connected/debug/` |
| Signed debug APK | PASS; APK Signature Scheme v2 verified, same certificate as 0.1.0 | `app/build/outputs/apk/debug/app-debug.apk` |
| Launcher icon | PASS: rounded adaptive icon visually confirmed on the installed app | `docs/screenshots/pause-launcher.png` |
| Install over 0.1.0 | PASS: package-manager update accepted without uninstall | Emulator `adb install -r` |
| Optimized unsigned release | PASS | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| All Android modules lint | PASS: 9 modules, zero errors | Module `build/reports/lint-results-debug.html` |

Totals: **37 JVM tests and 11 Android instrumentation tests**. The Android total includes two screenshot fixture captures; recapturing those images does not add distinct behavior tests.

Lint has warnings for dependency updates, resource qualifiers/unused source drawables, monochrome fallback metadata, and accessibility API metadata on older supported Android versions. Android 13+ monochrome assets are supplied separately in `mipmap-anydpi-v33`; zero errors does not mean zero warnings.

The 37 JVM tests cover monotonic timing, the 300-second cap, fresh choices, explicit Start, confirmed foreground before charging, repeated expiries, departure, covering overlays, launch timeout/failure, preview isolation, stale timer behavior, persistence ordering/failure/retry, codec compatibility, and presentation ownership.

## Real-app integration coverage

The opt-in fixture launches ordinary installed emulator apps through their actual launcher intents and the production Accessibility Service. It never calls `engine.begin()` as a substitute for interception.

- Save ten discovered apps in the actual protection repository, then exercise two ordinary targets.
- Observe full visible breathing and a fresh choice, with no automatic admission while choosing.
- Start immediate foreground use and complete three consecutive expiry → breathing → fresh-choice cycles.
- Verify measured usage and EXPIRED history, rather than legacy waiting values.
- Cover the target with the notification shade, check charging pauses, dismiss it, and check charging resumes on the same allowance.
- Leave early, verify LEFT_APP and no timer resurrection, reopen with fresh full allowance, then switch to the second target.
- Leave Settings Preview for a protected app; preview grants no access and adds no history.
- Revoke Accessibility while using a target; verify interruption and truthful service state.

The fixture only runs on emulator hardware with explicit opt-in. It restores prior preferences, selections, and accessibility settings in `finally`. Test-only shell privileges enable the service; production setup still requires the user's disclosure acceptance and Android permission flow.

The integration regression exposed a launcher event delivered after the confirmed target even though its event timestamp was older. The adapter now rejects older window events, excludes its own guard callbacks from foreground ordering, and cancels stale rechecks when usage starts. No broader screen-content permission was added.

The migration fixture creates the exact version-1 SQLite tables/identity and old DataStore JSON. It opens the production version-2 store and checks preserved settings/selections, bounded converted duration preferences, explicit LEGACY_WAIT history, and persisted quote settings.

## Reproduce

From PowerShell with an isolated emulator and `local.properties` configured:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :feature-intervention:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:assembleRelease lintDebug
.\gradlew.bat --no-parallel :data:connectedDebugAndroidTest :feature-intervention:connectedDebugAndroidTest :feature-app-selector:connectedDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.pauseRunInterception=true'
.\scripts\Capture-Screens.ps1 -Serial emulator-5554
```

Run the cross-app suite separately from UI suites to avoid competing automation sessions. The two screenshot tests capture breathing with a reflection and selection at 5:00; fixture labels do not imply YouTube was an actual integration target.

## Visual review

The refreshed [breathing](screenshots/pause-breathing.png) and [selection](screenshots/pause-selection.png) fixtures were inspected at the compact emulator size. The lavender reflection card is prominent and readable; the five-minute dial, primary Start action, leaving rule, and cancellation are reachable. No waiting countdown or final decision is shown. The final installed [launcher icon](screenshots/pause-launcher.png) is round on the emulator's Pixel launcher; other launchers choose their adaptive mask.

## Delivery and limits

Install **Pause-0.2.0-debug.apk** from `app/build/outputs/apk/debug/` over the previous app to retain settings. The final APK installed successfully over 0.1.0, reports versionCode 2/versionName 0.2.0, and has the same SHA-256 signing-certificate digest (`b22a42bfc8bfc6909c50d8604b311f755a5b8c1e32b483127c856e583d2f9810`). The release build is unsigned and needs release signing before distribution. Hashes are in [APK_SHA256.txt](APK_SHA256.txt).

Physical devices, other Android versions, OEM behavior, notification/deep-link launches, split-screen/Picture-in-Picture timing, forced background-launch denial, reboot/force-stop recovery, and actual haptic/audio perception remain outside this emulator result. Accessibility can observe the target after it briefly appears. Turning off the service stops protection; Pause cannot force-stop another app.




