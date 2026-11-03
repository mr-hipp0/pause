# Offline ZenQuotes collection

Pause 0.4.0 contains **16 short attributed quotes**, selected for a calm breathing screen from one 50-record response to `https://zenquotes.io/api/quotes`. It is not a complete ZenQuotes database export. The response was retrieved on November 3, 2026; the exact UTC retrieval time is recorded in the packaged asset.

The collection is `data/src/main/assets/quotes/zenquotes-v1.json`. Each immutable record pairs its text and author with a SHA-256-derived ID. Collection metadata records its source, version, retrieval time, credit, and intended personal non-commercial cache use. `zenquotes-selection-v1.json` records the original batch hash and selected IDs. Raw API output remains in ignored `.tooling/zenquotes-batch-2026-11-03.json` for this workspace's reproducible import.

## Source and use

[ZenQuotes' API documentation](https://docs.zenquotes.io/zenquotes-documentation/) recommends downloading quote batches and rotating them locally, and requires linked ZenQuotes attribution for free usage. Pause shows the author's name and a quiet **via ZenQuotes ↗** link to `https://zenquotes.io/`. Its accessibility label provides the full credit: **Inspirational quotes provided by ZenQuotes API**. Author and source share a wrapping footer row, retaining a 48dp source touch target.

The data is used here as a personal non-commercial local cache. This project does not claim an unrestricted redistribution licence or permission to export the entire service. [The provider's general terms](https://docs.zenquotes.io/terms-and-conditions/) restrict redistribution; clarify the applicable permission with ZenQuotes before publicly distributing a permanent collection. A paid API key alone should not be assumed to grant full database export rights.

Quotes were reviewed for length, relevance, tone, duplicates, and obvious attribution concerns. Author names are the provider's attribution, not independently authenticated primary-source citations. Entries with unknown authors and several familiar disputed attributions were excluded. Text is not rewritten: import only decodes entities and normalizes whitespace/Unicode.

## Import and validate

Builds and the installed app never contact ZenQuotes. To regenerate from the retained batch:

```powershell
.\scripts\Import-ZenQuotes.ps1 -InputFile '.tooling\zenquotes-batch-2026-11-03.json' -SelectionFile 'docs\quotes\zenquotes-selection-v1.json' -RetrievedAt '2026-11-03T13:39:49Z'
.\gradlew.bat :data:validateBundledQuotes
```

For an approved future batch/export, retain the raw response privately, review the text and author together, and generate a selection manifest before importing. Do not repeatedly call the random endpoint to harvest the database. The importer performs no network requests and refuses missing selections, invalid selected rows, duplicates, and mismatched batches. It writes only after all selected entries pass. Every Android build also validates the checked-in collection before packaging it.

The shipped display subset limits text to 160 UTF-16 units and author names to 80. The runtime reader has a slightly wider defensive limit; it drops invalid records and hides the quote card if none remain. It never substitutes an invented author or silently substitutes AI text.

## Rotation and privacy

An application-scoped repository loads the local asset on an IO dispatcher. Real interventions draw from a shuffled cycle, exhausting the collection before repeating and avoiding an immediate repeat at a cycle boundary. A separate DataStore retains rotation and the latest real session's complete quote snapshot. A small in-memory cache handles duplicate callbacks for recent sessions; the app never restores old app-use authorizations after a process restart.

Preview selection is deterministic for its session and never writes to normal rotation. Quote settings retain their global/per-app behavior. Reset Pause clears rotation; Delete history preserves it. No network permission, API key, refresh worker, analytics, or quote-service account is added. Following the source link opens a browser only when tapped and follows the existing departure/interruption rules.
