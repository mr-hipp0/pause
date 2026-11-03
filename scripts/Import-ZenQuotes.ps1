param(
    [Parameter(Mandatory = $true)][string]$InputFile,
    [Parameter(Mandatory = $true)][string]$SelectionFile,
    [Parameter(Mandatory = $true)][string]$RetrievedAt,
    [string]$CollectionVersion = '2026-11-03.1',
    [string]$OutputFile = (Join-Path $PSScriptRoot '..\data\src\main\assets\quotes\zenquotes-v1.json')
)

# Offline import only. Fetching is deliberately separate from builds/imports: one documented
# /api/quotes batch is sufficient for a personal cache, not a full-database export licence.
$ErrorActionPreference = 'Stop'
$null = [DateTimeOffset]::ParseExact($RetrievedAt, "yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal)
if ($CollectionVersion -notmatch '^[a-zA-Z0-9._-]{1,80}$') { throw 'CollectionVersion must be a nonempty version identifier.' }
$batch = @(Get-Content -LiteralPath $InputFile -Raw -Encoding UTF8 | ConvertFrom-Json)
$selection = Get-Content -LiteralPath $SelectionFile -Raw -Encoding UTF8 | ConvertFrom-Json
if ($selection.batchSha256 -ne (Get-FileHash -LiteralPath $InputFile -Algorithm SHA256).Hash.ToLowerInvariant()) {
    throw 'The input batch hash differs from the reviewed selection manifest.'
}
$approvedIds = @($selection.selectedIds)
if ($batch.Count -lt 1 -or $batch.Count -gt 50) { throw 'Expected one batch containing 1–50 quotes.' }
if ($approvedIds.Count -lt 1) { throw 'Select and review quotes before importing.' }
if (@($approvedIds | Select-Object -Unique).Count -ne $approvedIds.Count) { throw 'Selection contains duplicate IDs.' }

function ConvertTo-PlainQuoteText([string]$Value) {
    ([regex]::Replace([Net.WebUtility]::HtmlDecode($Value).Normalize([Text.NormalizationForm]::FormC), '\s+', ' ')).Trim()
}

$quotes = @()
$seenText = @{}
$sha = [Security.Cryptography.SHA256]::Create()
try {
    foreach ($entry in $batch) {
        if ($entry.q -isnot [string] -or $entry.a -isnot [string]) { throw 'API entries need string q and a fields.' }
        $text = ConvertTo-PlainQuoteText $entry.q
        $author = ConvertTo-PlainQuoteText $entry.a
        $bytes = [Text.Encoding]::UTF8.GetBytes($text + "`n" + $author)
        $id = 'zen-' + ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant().Substring(0, 24)
        if ($id -notin $approvedIds) { continue }
        if ($text.Length -lt 1 -or $text.Length -gt 160 -or $author.Length -lt 1 -or $author.Length -gt 80) { throw "Invalid selected quote length: $id" }
        if ($text -match '[<>\p{Cc}]' -or $author -match '[<>\p{Cc}]' -or $author -match '^(Unknown|Anonymous)$') { throw "Unusable selected quote: $id" }
        if ($seenText.ContainsKey($text.ToLowerInvariant())) { throw "Duplicate selected text: $id" }
        $seenText[$text.ToLowerInvariant()] = $true
        $quotes += [ordered]@{ id = $id; text = $text; author = $author }
    }
} finally { $sha.Dispose() }
if ($quotes.Count -ne $approvedIds.Count) { throw 'Selection does not match the supplied batch. Retain the original batch for reproducible imports.' }

$asset = [ordered]@{
    schemaVersion = 1
    collectionVersion = $CollectionVersion
    source = 'ZenQuotes'
    sourceUrl = 'https://zenquotes.io/'
    retrievedAt = $RetrievedAt
    sourceEndpoint = 'https://zenquotes.io/api/quotes'
    attribution = 'Inspirational quotes provided by ZenQuotes API'
    usage = 'Personal non-commercial local API cache; public redistribution rights have not been established.'
    quotes = $quotes
}
$destination = [IO.Path]::GetFullPath($OutputFile)
$null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
[IO.File]::WriteAllText($destination, ($asset | ConvertTo-Json -Depth 5) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
Write-Output "Imported $($quotes.Count) reviewed quotes into $destination"
