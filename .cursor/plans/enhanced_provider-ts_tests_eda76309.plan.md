---
name: Enhanced Provider-TS Tests
overview: Split `test/contract.js` into per-category test files with comprehensive field validity checks, media URL verification, playback URL reachability checks, and a structured PASS/N/A/FAIL report (no SKIP — a SKIP is a FAIL).
todos:
  - id: reporters
    content: "Update reporter.js: add `na` status, `NAError`, `beginCategory`/`endCategory`, updated terminal output and JSON schema"
    status: completed
  - id: validators
    content: Create test/validators.js with shared assert helpers, assertUrl, and checkUrlReachable (HEAD fetch)
    status: completed
  - id: cat-bridge
    content: Create test/categories/bridge.js — bridge presence checks
    status: completed
  - id: cat-config
    content: Create test/categories/config.js — getFieldsSpec + validateFields checks
    status: completed
  - id: cat-catalog
    content: Create test/categories/catalog.js — listEntry + search + cover URL checks
    status: completed
  - id: cat-detail
    content: Create test/categories/detail.js — getDetail + logo/backdrop/externalUrl checks
    status: completed
  - id: cat-playback
    content: Create test/categories/playback.js — getPlaybackSpec + URL reachability + hooks
    status: completed
  - id: cli-update
    content: Update cli.js to orchestrate five category modules, thread providesCover and playable item ref between them
    status: completed
  - id: contract-delete
    content: Delete test/contract.js (fully replaced by category files)
    status: completed
isProject: false
---

# Enhanced Provider-TS Test Plan

## Current state

- Single `test/contract.js` with all checks inline
- Reporter supports `pass / skip / fail`; `SkipError` produces `skip` status
- Smoke-only validation: presence checks, basic types — no URL reachability, no media asset checks, no categories in output

## Status model change

| Old | New | Meaning |
|-----|-----|---------|
| `pass` | `PASS` | assertion succeeded |
| `skip` | replaced | eliminated |
| `fail` | `FAIL` | assertion failed — or a previously-SKIP case |
| *(new)* | `N/A` | optional feature/field not provided by this provider |

`SkipError` → `NAError`. Using `NAError` (not throwing) = N/A result. Any other throw = FAIL.

"No playable item found" stays N/A (data-dependent). "Hooks flag not passed" becomes N/A (opt-in flag). "Empty root list" → dependent test is N/A. Anything the provider _should_ provide but doesn't → FAIL.

---

## New file layout

```
test/
├── cli.js                  (updated — orchestrate categories, pass providesCover)
├── reporters.js            (updated — na state, category grouping, table summary)
├── validators.js           (new — shared assert helpers + URL validation)
├── categories/
│   ├── bridge.js           (new — bridge contract presence)
│   ├── config.js           (new — getFieldsSpec + validateFields)
│   ├── catalog.js          (new — listEntry + search + cover URLs)
│   ├── detail.js           (new — getDetail + media URLs)
│   └── playback.js         (new — getPlaybackSpec + URL reachability + hooks)
├── contract.js             (deleted — replaced by categories/)
├── env.js                  (unchanged)
├── host-apis.js            (unchanged)
└── quickjs-runner.js       (unchanged)
```

---

## Per-category detail

### `categories/bridge.js` — API: provider bridge
- `providesCover` is boolean
- All 10 required methods exist on `opentuneProvider`

### `categories/config.js` — APIs: `getFieldsSpec`, `validateFields`
- `getFieldsSpec` returns array; each field has `id`, `labelKey`, `kind ∈ {text,singleLine,password}`; optional fields (`required`, `sensitive`, `order`, `placeholderKey`) valid types when present
- `validateFields` returns `{success:true, hash, name, fields}`; non-empty strings

### `categories/catalog.js` — APIs: `listEntry`, `search`

**Basic list structure:**
- `listEntry(null)` → valid `EntryList` shape (`items[]`, `totalCount: number`)
- Each `EntryInfo`: required `id`, `title`, `type ∈ EntryType`; optional fields (`cover`, `userData`, `genres[]`, `originalTitle`, `communityRating`, `studios[]`, `indexNumber`) correct types when present
- Cover URL check per item: N/A if `providesCover=false` or cover is null; FAIL if `providesCover=true` and cover is null; URL format check (http/https) + `ffprobe` image validation if present

**Pagination:**
- `listEntry(null, startIndex=0, limit=5)` + `listEntry(null, startIndex=5, limit=5)` → no duplicate item IDs across pages
- `items.length ≤ limit` on each page — FAIL if page exceeds requested limit
- If `totalCount > limit`: second page must be non-empty — FAIL if page 2 is empty
- Cumulative item IDs across all pages fetched are unique (no server-side drift/re-ordering)

**Series / Season rules:**
- Locate a `Series` item in the root/browsed catalog — N/A if none found
- `listEntry(series.id)` children must all be `Season` or `Episode` type — FAIL if other types appear
- Locate a `Season` item (from Series children or directly in catalog) — N/A if none found
- `listEntry(season.id)` children must all be `Episode` type — FAIL if other types appear
- Episodes within a season must have `indexNumber` set (non-null `number`) — FAIL if any episode lacks `indexNumber`
- `indexNumber` values within a season must be unique — FAIL if duplicates found
- Progressive pagination consistency for season episodes: page through all episodes of a season using the same `limit` until `totalCount` is reached; concatenated IDs must be unique (no duplicates), and `indexNumber` values across pages must remain unique and match what would be fetched in a single large request — FAIL if mismatches

**Search:**
- `search("")` → array; each item passes same `EntryInfo` checks
- `listEntry` on a non-Series container child → valid child list

### `categories/detail.js` — API: `getDetail`
- `getDetail(firstItem.id)` → valid `EntryDetail`
- Required: `title (string)`, `isMedia (boolean)`, `backdrop (array)`, `externalUrls (array)`, `providerIds (object)`, `streams (array)`
- `logo`: N/A if null; URL format check + `ffprobe` image validation if present
- `backdrop[]`: N/A if empty; each item URL format check + `ffprobe` image validation if non-empty
- `externalUrls[]`: each item has `name (string)`, `url (string)` — URL format check
- `streams[]`: each `StreamInfo` has `index (number)`, `type (string)`, `isDefault (boolean)`, `isForced (boolean)`; optional `codec`, `title`, `language` correct types when present
- Optional fields (`overview`, `rating`, `bitrate`, `year`, `etag`) correct types when present

### `categories/playback.js` — APIs: `getPlaybackSpec`, `onPlaybackReady`, `onProgressTick`, `onStop`
- `getPlaybackSpec` → valid `PlaybackSpec`
- `url` is non-empty string — FAIL if null/empty
- `url` is valid http/https URL (format check)
- `url` is reachable: HEAD request (with `spec.headers`) → 2xx/206/3xx; FAIL if network error or 4xx/5xx
- `url` passes `ffprobe` media validation: must contain at least one `video` or `audio` stream; N/A if `ffprobe` not available
- `mimeType` string type when present
- `subtitleTracks[]`: each has `trackId`, `label` (strings), `isDefault`, `isForced` (booleans), `language`/`externalRef` string|null
- `hooksState` is object
- `onPlaybackReady` / `onProgressTick` / `onStop`: N/A if `--hooks` flag not passed; otherwise call and FAIL on error

---

## `test/validators.js` (shared helpers)

- All existing assert helpers moved here (`assert`, `assertType`, `assertNonEmptyString`, `assertObject`)
- `assertUrl(value, path)` — valid `URL` parse + `http:` or `https:` protocol
- `checkUrlReachable(url, headers)` — `fetch(url, { method:'HEAD', headers })`, validates 2xx/206/3xx response
- `checkMediaWithFfprobe(url, headers, { expectVideo, expectImage })` — spawns `ffprobe -v error -show_streams -print_format json` with headers passed via `-headers`; checks at least one stream of the expected type; returns N/A if `ffprobe` binary is not on `$PATH`

---

## Updated `reporter.js`

- `na` status replaces `skip`
- `NAError` replaces `SkipError`
- `beginCategory(name, apis[])` / `endCategory()` — categories are tracked in output
- Terminal output: grouped by category with category header line, then `PASS`/`N/A`/`FAIL` per test
- Summary table: per-category counts + grand total
- JSON output: `{ meta, categories: [{name, apis, results:[{name, status, durationMs, detail?, error?}]}] }`

---

## Updated `cli.js`

- Imports and calls the five category modules in order
- Passes `providesCover` value (read once after bridge check) to catalog category
- Passes `--hooks` option flag to playback category
- Catalog category returns a context object: `{ firstItem, seriesItem, seasonItem, playableItem }` — threaded into detail and playback categories to avoid re-scanning
- `--ffprobe` flag (default on if `ffprobe` found on `$PATH`; `--no-ffprobe` to disable) controls whether `ffprobe` checks run; probe binary path detected once at startup and passed down