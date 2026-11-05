# Validation record — Pause 0.4.0

Validation date: November 3, 2026 (America/Chicago).

This update replaces the original AI-written reflections with 16 curated ZenQuotes entries stored in the APK. Each card shows the quote, author, and linked source in a compact wrapping footer. The collection is a personal non-commercial local API cache, not a full database export. [Source and usage details](quotes/README.md).

Tests used the isolated emulator requested by the user. Physical-device checks remain **NOT RUN** in [DEVICE_TEST_MATRIX.md](DEVICE_TEST_MATRIX.md). Previous visual-release evidence is archived in [legacy-v0.3](legacy-v0.3/).

## Environment

- Windows, JDK 21, Gradle 8.13, AGP 8.13.2, Kotlin 2.1.20; bytecode 17.
- minSdk 29; target/compileSdk 36; versionCode 4, versionName 0.4.0.
- Android 16/API 36 Google APIs x86_64 emulator Pause_API_36, WHPX.
- Standard: 480 × 960 pixels at 200dpi. Tall: 480 × 1120. Larger text: standard display at 130% system text size.
- Component accessibility check: 180% text in a 480dp viewport.
- Fingerprint: google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys.

## Results

| Check | Result | Evidence |
| --- | --- | --- |
| JVM tests | PASS: 48 (domain 24, data 21, presentation 3) | Module test-results directories; .tooling/quotes-build.log |
| Intervention UI | PASS: 11, including 3 captures | .tooling/quotes-compact-footer.log |
| Real storage and migration | PASS: 2 | .tooling/quotes-device.log |
| Real cross-app usage | PASS: 1 | .tooling/quotes-initial-TEST-Pause_API_36(AVD) - 16-_app-.xml |
| Actual Activity quotes/visuals | PASS: 1 fixture repeated for 3 profiles | .tooling/quotes-visual-standard.log, quotes-visual-tall.log, quotes-visual-large-text.log |
| Import reproducibility | PASS: identical asset SHA-256; invalid selection preserves prior output | Independent import review |
| Build-time quote validation | PASS: 16 attributed records | Every data preBuild depends on validateBundledQuotes |
| Signed debug and optimized unsigned release | PASS | .tooling/quotes-final-build.log; artifacts/Pause-0.4.0-debug.apk |
| Update over 0.3.0 | PASS: package manager accepted without uninstall | Emulator versionCode 3 → 4 / versionName 0.3.0 → 0.4.0 |
| APK signature | PASS: same existing debug certificate | SHA-256 certificate b22a42bfc8bfc6909c50d8604b311f755a5b8c1e32b483127c856e583d2f9810 |
| Android lint | PASS: 9 modules, zero errors | Module lint-results-debug reports |

**48 JVM tests and 15 distinct instrumentation tests ran for this update.** Repeated captures do not increase the distinct test count. Unchanged selector/main-page fixtures retain their 0.3 evidence rather than being counted as newly executed tests.

Lint retains dependency-update suggestions, existing legacy-resource/metadata notices, and one optional Uri KTX suggestion. Passing lint does not mean zero warnings.

## What the checks establish

Quote tests cover cycle exhaustion, cycle boundaries, concurrent duplicate callbacks, exact text/author snapshots after repository recreation, preview isolation, empty/single collections, changed collections, malformed content, corrupt state, cancellation, disk-write failures, and reset. A device test reads the actual packaged asset and exercises real DataStore persistence with an isolated state file.

The UI tests cover optional/missing quotes, author rendering, stable timer updates, source-link callbacks, full-height/quotes-off layout behavior, accessibility text scrolling, cancellation, and the existing five-minute chooser cap. The actual Activity fixture checks both themes, recreation, the longest available quote, quotes disabled, reachable cancellation, and attribution-intent dispatch. The browser intent is intercepted by instrumentation; no live website is required.

The real cross-app fixture exercises the actual Accessibility Service, repeated expiries, immediate app access after Start, measured foreground time, transient shade behavior, early departure/reopen, protected-app switching, preview isolation, and service revocation.

Initial visual-fixture issues were corrected in the test setup: the browser monitor needed CATEGORY_BROWSABLE, and scrolling must start inside the intervention's inset content area. The final three-profile visual runs pass.

## Visual review

The current attributed-quote captures are in [screenshots/quotes](screenshots/quotes/):

- [Standard dark](screenshots/quotes/standard/breathing-dark.png) and [light](screenshots/quotes/standard/breathing-light.png).
- [Tall dark](screenshots/quotes/tall/breathing-dark.png).
- [Larger-text light](screenshots/quotes/large-text/breathing-light.png).
- Each profile also contains quotes-off captures in both themes.

Quote text is complete, the author is distinct, and the linked source has a 48dp touch target. Author/source wrap when needed. Compact usable heights and enlarged text intentionally scroll; the longer quote does not require all content to fit simultaneously. Cancellation and attribution remain reachable. Scrolled screenshots can show the lower content while the heading has moved offscreen.

The current root component captures were regenerated for the compact footer. Their quote is a ZenQuotes documentation fixture; the new full-window quote captures use the actual packaged collection. Older main-page images retain the unchanged 0.3 design.

## Reproduce

On an isolated emulator with the configured SDK:

    .\gradlew.bat :domain:test :data:testDebugUnitTest :feature-intervention:testDebugUnitTest
    .\gradlew.bat --no-parallel :feature-intervention:connectedDebugAndroidTest :data:connectedDebugAndroidTest
    .\gradlew.bat --no-parallel :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.pause.app.OfflineQuotesVisualTest,com.pause.app.RealInterceptionTest' '-Pandroid.testInstrumentationRunnerArguments.pauseRunQuoteVisuals=true' '-Pandroid.testInstrumentationRunnerArguments.pauseRunInterception=true'
    .\scripts\Collect-QuoteVisuals.ps1 -Profile standard
    .\gradlew.bat :app:assembleDebug :app:assembleRelease lintDebug

The pauseQuoteVisualProfile runner argument labels tall/large-text captures after changing the emulator's display/text settings. Device suites must run sequentially. When rerunning instrumentation directly with adb after a Gradle device suite, reinstall both the app and test APK: the Gradle runner can remove its target on cleanup. Visual fixtures are opt-in and reject physical-device hardware.

## Delivery and limits

Install **artifacts/Pause-0.4.0-debug.apk** over the existing app. This stable artifact directory survives later Gradle packaging. A convenience copy is also under app/build/outputs/apk/debug. Final debug/release hashes are in [APK_SHA256.txt](APK_SHA256.txt).

The final APK contains all 16 records locally. It adds no Internet permission, API key, scheduled refresh, account, or quote-service dependency at runtime. Public redistribution of a permanent collection still needs the provider's applicable permission clarified; API caching support is not treated as a full database-export licence.

Phone hardware, other Android/OEM versions, split-screen/Picture-in-Picture, deep-link launches, reboot/force-stop recovery, and perceived haptics/audio retain the outstanding physical-device scenarios in the matrix.

## Post-breathing decision follow-up

Validated November 3, 2026 (America/Chicago), on the same isolated Android 16 emulator. Breathing now ends in a settled decision state. Continue explicitly opens duration selection; Leave cancels without allocating usage and returns Home. Preview returns to Pause when leaving.

- **29 JVM tests pass:** 26 domain and 3 presentation tests. Coverage includes an idle decision, guarded continuation, cancellation without an allowance, previews, and repeated expirations.
- **14 intervention UI tests pass, including 4 captures:** both choices, the five-minute cap, quote/source behavior, and enlarged text. [Reviewed decision screen](screenshots/pause-decision.png).
- **2 actual Activity/service tests pass together:** decision persistence through Activity recreation, both preview choices without history changes, three real cross-app expiration cycles, explicit continuation, and Leave with zero allocated/used time.
- Debug app and test APK builds pass. Lint passes for feature-intervention and service-interception with zero errors and existing warnings.

Evidence: `.tooling/decision-build.log`, `decision-checks.log`, `decision-activity-build.log`, `decision-ui.log`, and `decision-app.log`. An initial combined fixture run exposed an automation connection interaction; using UiAutomator consistently in both Activity/service fixtures resolved it, and the final combined run passes.

The follow-up APK is **artifacts/Pause-0.4.0-decision-debug.apk**, signed with the existing debug certificate so it can update the previous installation. SHA-256: `d20f6924c03f02a21969782b5ea92a18fd9c114abf3f998062239eb136de6782`.

## Immersive fullscreen follow-up

Validated November 5, 2026 (America/Chicago), on the isolated Android 16 emulator. The intervention window hides status and navigation bars during breathing, decision, and duration selection. Edge swipes can reveal transient system controls. The background fills the display while safe padding protects content around display cutouts.

- Debug app/test APK builds and feature-intervention lint pass with zero errors.
- Both actual Activity/service fixtures pass together. The Activity fixture checks status and navigation visibility separately during breathing, decision, recreation, and selection, and retains Continue/Leave and preview-history checks. The service fixture verifies repeated real cross-app usage cycles.
- The capture fixture was rerun successfully after adding dismissal of Android's one-time immersive-mode tutorial. [Full-window preview, 480 × 960](screenshots/pause-fullscreen-decision.png); the emulator's 128px cutout inset remains protected at the top.

Evidence: `.tooling/fullscreen-build.log`, `fullscreen-device.log`, `fullscreen-capture-build.log`, and `fullscreen-capture.log`. The installable update is **artifacts/Pause-0.4.0-fullscreen-debug.apk**, signed with the existing debug certificate. SHA-256: `e67066baf5621fcc24f0a78eb74afa5ba35ce1a6a42a4930719259a1192dbe17`.
