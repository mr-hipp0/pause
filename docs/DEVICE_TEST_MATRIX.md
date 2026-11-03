# Physical-device acceptance matrix — Pause 0.4.0

**Status: NOT RUN for 0.4.0.** The user reported that the previous APK worked on their phone. This matrix tracks the revised usage flow separately. Automated results are in [VALIDATION.md](VALIDATION.md).

Install the new signed debug APK over the existing app. Accept the disclosure, enable the Accessibility Service, confirm connected status, select installed test targets, and enable fallback notifications. Record device model, Android/API version, APK hash, date, expected versus observed behavior, and relevant logs before marking any row PASS, FAIL, or BLOCKED.

| ID | Action | Expected result | Physical status |
| --- | --- | --- | --- |
| 01 | Launch a protected app. | One breathing screen, fresh duration chooser, then immediate app access after Start. | NOT RUN |
| 02 | Choose 5 seconds, then repeat at least three expiries. | Every allowance ends in breathing and a fresh chooser; no unlimited access. | NOT RUN |
| 03 | Choose exactly 5 minutes; attempt values above the maximum. | UI, saved settings, and runtime cap each allowance at 300 seconds. | NOT RUN |
| 04 | Leave an app before expiry; reopen immediately. | Unused time is discarded. Breathing and a new allowance are required. | NOT RUN |
| 05 | Open the keyboard while using an admitted app. | Typing counts as app time and does not create a new intervention. | NOT RUN |
| 06 | Cover the app with the notification shade, permission prompt, or volume dialog; dismiss it. | Covered time does not consume the budget; confirmed return resumes charging without resetting it. | NOT RUN |
| 07 | Switch directly between two protected apps, both during usage and during breathing. | Correct target, fresh intervention, no stale callback cancels a new allowance. | NOT RUN |
| 08 | Protect ten installed apps; restart Pause. | All selections persist and supported enabled targets can be intercepted independently. | NOT RUN |
| 09 | Cancel or press Back during breathing/selection. | Returns Home, records one cancellation, and grants no allowance. | NOT RUN |
| 10 | Revoke Accessibility during usage; then reopen a target. | Active allowance ends, status reflects disconnection, and Pause does not claim protection while disabled. | NOT RUN |
| 11 | Reboot and unlock with selections saved. | Settings persist; protection resumes if Android reconnects the service. Record OEM restrictions. | NOT RUN |
| 12 | Lock the screen during usage or intervention; unlock and reopen. | No old allowance survives; reopening requires a fresh intervention. | NOT RUN |
| 13 | Kill the Pause process; test force-stop separately. | No admission is restored; unfinished stored records become interrupted. Record force-stop limits. | NOT RUN |
| 14 | Rotate during breathing/selection and while the target is open. | Correct session/target and selected duration; no duplicate history or renewed budget. | NOT RUN |
| 15 | Update an existing 0.1.0 installation with saved overrides/history. | No uninstall required; selections/settings remain; old waiting history is identified as legacy. | NOT RUN |
| 16 | Toggle quotes globally and in a per-app override; restart. | Preference persists. An optional readable reflection card appears only when enabled. | NOT RUN |
| 17 | Inspect the launcher icon in normal and themed mode. | Launcher-shaped adaptive icon with rounded fallback and a clean Pause glyph. | NOT RUN |
| 18 | Adjust breathing, usage bounds, theme, haptics, and sound. | Independent settings persist; inherited apps follow defaults; overrides retain their values. | NOT RUN |
| 19 | Use TalkBack, slider actions, ± controls, large fonts, and a small display. | Duration and actions remain understandable and reachable. | NOT RUN |
| 20 | Preview through Settings, then open a protected app. | Preview never launches a target or adds history; real protection still intervenes afterward. | NOT RUN |
| 21 | Uninstall/update a protected target; install a new app. | Removal cleans its entry, replacement preserves settings, new launchable apps are discoverable. | NOT RUN |
| 22 | Launch via a notification/deep link. | Observable foreground transition is intercepted. Record whether Start returns to the landing screen instead of the deep link. | NOT RUN |
| 23 | Trigger background launch denial with notifications enabled, denied, and channel blocked. | Available fallback opens the same attempt with full breathing; otherwise the attempt is interrupted. | NOT RUN |
| 24 | Compare history after expiry, early departure, preview, and reset. | Only measured usage contributes app time; legacy waiting is separate; reset does not resurrect queued writes. | NOT RUN |
| 25 | Inspect recovery controls and privacy. | Pause, launcher, and Settings remain accessible; no screen content or network upload is used. | NOT RUN |
| 26 | Open breathing on short and tall displays, with quotes on/off. | Circle and spacing adapt to usable height; reflection and cancellation remain reachable; no large unused lower region. | NOT RUN |
| 27 | Increase Android text size; navigate all screens and the chooser. | Text wraps clearly; smaller layouts scroll; essential actions are reachable; each intervention stage starts at the top. | NOT RUN |
| 28 | Edit global and per-app settings, expand breathing rhythm, save, and preview. | Sticky Save is reachable, Preview uses saved changes, and inherited/overridden settings remain correct. | NOT RUN |
| 29 | Switch between dark/light/system themes; use navigation, search, selection, and filters. | Consistent typography, surfaces, vector icons, selected states, contrast, and touch targets. | NOT RUN |
| 30 | Choose Today, 7 days, and 30 days in Insights. | Totals and the chart use explicit matching ranges; earlier wait history stays separate and secondary. | NOT RUN |
| 31 | Launch a fresh installation offline with quotes enabled. | Attributed local ZenQuotes entry appears immediately; breathing never requires network. | NOT RUN |
| 32 | Complete a full quote rotation, restart Pause, and use Preview repeatedly. | Real sessions avoid repeats until collection exhausted; restarts preserve progress; Preview does not consume it. | NOT RUN |
| 33 | Inspect author/source in both themes and with large text; follow the source link. | Full text wraps; source is accessible and opens only on tap; departure grants no allowance. | NOT RUN |
| 34 | Reset Pause; compare with deleting history only. | Reset clears quote progress; deleting history preserves it. | NOT RUN |

Use stock and OEM devices where available, including the oldest supported Android 10/API 29 and current Android. Emulator results do not change these physical-test statuses. Split-screen/Picture-in-Picture timing, OEM restrictions, and actual haptic/audio perception require separate device assessment.
