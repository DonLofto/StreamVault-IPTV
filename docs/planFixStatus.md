# planFix remediation status

Tracks implementation of the 59 findings in `docs/performance-audit.md`.
Plan: `docs/planFix.md`. Baseline commit: `740bd55f`.

**37 done · 4 partial · 1 superseded · 17 open.** Commits marked DONE are on `master`; the working
tree is clean. Verification for every DONE item was a module compile plus the relevant test suite;
Room-validated SQL and byte-identical golden output are called out where they apply.

---

## Done

| ID | Commit | Change |
|---|---|---|
| A1 | `3b8cb2c5` | `containsStandalone` regex replaced with an ASCII boundary scan |
| A2 | `3b8cb2c5` | Six further per-call `Regex` sites hoisted (incl. one inside a 22-iteration loop) |
| A3 | `3b8cb2c5` | 49 sequential full-string passes collapsed to one precompiled alternation |
| A7 | `a50791e5` | Recording pulse alpha read in the draw phase, not composition |
| A10 | `3b8cb2c5` | `DateFormat` prototypes cached and cloned; `DateTimeFormatter` shared |
| A15 | `db052952` | Playback support snapshot written only when its content changes |
| A21 | `63a0a2e6` | `ProviderSyncWorker` consults the playback admission gate |
| A22 | `612efdf9` | Unresolved-channel log string gated behind `Log.isLoggable` |
| A23 | `78c9ed31` | Guide program map/group moved off the caller dispatcher |
| A26 | `3b8cb2c5`, `8aad07e7` | Fingerprint regex hoisted; hex table replaces 32 `String.format` per digest |
| A28 | `3b8cb2c5` | Search regexes hoisted + decorate-sort-undecorate |
| A29 | `3b8cb2c5` | `EpgNameNormalizer` regex hoisted |
| A30 | `3b8cb2c5` | M3U `stableId` regex hoisted |
| A32 | `89746224` | EPG match search debounced (275 ms) |
| A33 | `290fe5b1` | `asReadOnlyBuffer()` only when a tap is attached |
| A36 | `0d0ef491` | Jellyfin image token decrypted once, not per image request |
| A37 | `32ea78ef` | One shared `Cache` instead of two over the same directory |
| A39 | `358c0c96` | `CancellationException` rethrown in the guide load path |
| A41 | `89746224` | Page append uses `subList` bounds instead of `drop().take()` |
| A42 | `0c671937` | Channel initials remembered per badge |
| A43 | `5217f849` | Stalker category lookup indexed by position (order-preserving) |
| A44 | `5217f849` | Category entity built only on first sight |
| A45 | `610b775e` | OkHttp client and cache quota injected `dagger.Lazy` |
| A46 | `3b8cb2c5` | Movie codec-fallback regex hoisted |
| A47 | `c12cfd42` | `DashWindow.mediaSize()` O(1) accessor |
| A48 | `b7214e14` | Live-stream-type set hoisted |
| A49 | `57dbb1aa` | Live-dot blink hoisted into a leaf composable |
| A50 | `3b8cb2c5` | Player error/track/timeshift parsers hoisted |
| A51 | `3b8cb2c5`, `8aad07e7` | See A26 (same code path) |
| A53 | `62322342` | XMLTV staging inserts wrapped in a transaction |
| A59 | `964f2926` | `getByIds` chunked at 900 for the SQLite 999 bind-variable ceiling |
| A16 | `cb13e949` | Player diagnostics collected only while the overlay is visible |
| A40 | `3b6b9ee9` | Guide clock exposed as State; grid row/cell defer through `derivedStateOf` |
| A56 | `d67ebee3` | VOD browse page built once per fetch instead of twice |
| A24 | `21c15fd8` | Archive capability evaluated once per channel, not per programme |
| A11 | `01dc8a06` | Whole-body retries capped at 2 attempts and jittered |
| A13 | see below | EPG resolution skipped when guide data did not change and mappings exist |

## Partial

| ID | State |
|---|---|
| **A5** | Parse-time half DONE (`3b8cb2c5`: fallback `Regex` and `DateTimeFormatter` hoisted). **The exception-driven format probing itself is still open** — `parseDate` still constructs and throws up to 11 `DateTimeParseException`s per date, twice per programme. **ATTEMPTED AND REVERTED 2026-09-11 — read this before retrying.** Swapping the three helpers to `DateTimeFormatter.parse(CharSequence, ParsePosition)` with a `position.index == text.length` full-consumption check **changed behaviour**: 5 `XmltvParserTest` cases failed, all of them offset-less timestamps (`20250101140000` with pattern `yyyyMMddHHmmss`) that previously parsed and now returned null, plus a `DateTimeParseException` escaping for genuinely malformed input (so the ParsePosition overload can still throw). The naive swap is **not** behaviour-preserving. Retry only by first writing failing tests that pin the offset-less case, then establishing empirically what the ParsePosition overload does to `position.index` and to field resolution for these patterns. The single-`DateTimeFormatterBuilder`-with-`optionalOffset()` route may be the better shape. |
| **A25** | Repository half DONE (`68adb5a2`: `flowOn(Dispatchers.Default)` on movie/series `getCategories`). **Open:** the `app/ui` ViewModel `combine` transforms named in the audit (MoviesViewModel:145-171, SeriesViewModel:161/322, HomeViewModel:484, EpgViewModel:1101) still run on `Main.immediate`. |
| **A27** | Reclassified DONE (`c3929c86`). The reflective-codec half was **withdrawn as wrong** — see below. **Open:** the duplicate URL parse (`EntityMappers.kt:196` and `SyncCatalogStore.kt:822` both call `parseInternalStreamUrl`) — carry the `XtreamStreamToken` forward. |
| **A52** | Row-value half DONE (`8aad07e7`: all four stage merges, 880→510 lines, Room-validated). **Open:** the monotonic watermark, so the progress commit stops re-scanning the whole provider catalog every 500 channels. |

## Superseded

| ID | State |
|---|---|
| **A31** | Superseded by A52 — the 18 repeated probes in `updateChangedChannelsFromStage` are removed by the row-value rewrite. Do not implement separately. |

---

## Two findings were WRONG — do not implement as written

Both surfaced only when implementing, and both are the same class of error: the plan was written from
static reading and was wrong about an API.

- **A4** — the proposed `json.decodeFromJsonElement(deserializer, element)` **does not compile**.
  `JsonParser.parseReader` returns a **Gson** `com.google.gson.JsonElement`; `decodeFromJsonElement`
  requires a **kotlinx** one. Confirmed by attempting it at both sites. The real fix removes Gson from
  the per-item path (`decodeFromStream` / a `JsonDecoder` over a reader) and must pin lenient-parsing
  behaviour with tests first, because Gson's `isLenient` and kotlinx's are not identical.
  `docs/planFix.md` A4 now documents this; risk raised S/low → M/med.
- **A27** — the plan claimed ~75 000 reflective `Method.invoke` calls per sync and said to replace
  them with direct calls. **Both halves are wrong.** `XtreamUrlCodec` probes reflectively *because*
  `URLEncoder/URLDecoder.encode/decode(String, Charset)` only exist from **API 33**, while the app is
  `minSdk = 25`. On the AFTSSS (API 28) the probe returns `null`, the `?.let` short-circuits, and the
  plain String-charset fallback runs — so there is **no reflective invoke per call at all** on the
  measured baseline. Worse, "fixing" it by calling the `Charset` overload directly would compile
  against `compileSdk = 36` and throw `NoSuchMethodError` on API 25–32, i.e. on the target device.

## Open (17)

A4, A6, A8, A9, A12, A14, A17, A18, A19, A20, A34, A35, A38, A54, A55, A57, A58.

Grouped by why they are still open:

- **Need a design decision, not a mechanical edit** — A52's watermark (what counts as "newly staged"),
  A8 (incremental byte counter must over-estimate, never under, or timeshift fills the device),
  A13 (skipping resolution is only safe if mappings are known to exist), A55 (Room migration),
  A34 (MEMORY-backend cap vs the 192 MB heap class).
- **Need device validation under the `AGENTS.md` live-TV protocol** (61 screenshots, two channels,
  media session `PLAYING`) — A14, A20, A57, and any playback/timeshift/network-admission change.
- **Need dedicated test fixtures before changing behaviour** — A4, A6/A9 (classify-once refactor
  guarded by the `ChannelNormalizerGoldenTest` baseline), A12, A17, A56.
- **Compose recomposition work** — A16, A18, A19, A24, A40. Measure with `dumpsys gfxinfo` and
  Compose metrics before and after; the audit's recomposition findings are reasoned from code, not
  measured, so they need a before/after to confirm the fix helps.
- **A11, A38** — retry-scope and timeout changes that could break legitimate large transfers
  (EPG carries a 200 MB budget); both need a `MockWebServer` fixture first.

## Environment notes

- `CancellableHttpTest` is **flaky and pre-existing**: different test cases fail on different runs when
  the class runs alongside others, and it passes 2/2 in isolation. `CancellableHttp` is untouched by
  any commit in this work. Do not chase it.
- `kill -3` does not work on this unrooted Fire OS device; thread dumps need the JDWP + `jdb`
  recipe in `docs/planFix.md` §2.2.
- `:app:compileDebugUnitTestKotlin` is **broken pre-existing** (`StartupCoordinatorTest.kt:50` passes
  an `ioDispatcher` parameter that `StartupCoordinator` no longer accepts), so no `app/` unit test
  compiles or runs. Every `app/`-module finding above was verified by compile plus the
  `domain`/`data`/`player` suites only. Fixing this needs a dispatcher-qualifier decision.
- **Cold start is ~17 s and pre-existing**, confirmed by a controlled A/B (stash, rebuild, reinstall,
  measure): +18.8s/+17.8s/+17.3s before, +17.3s/+17.4s after PR 1. The audit's original +3s082ms
  reading was not reproducible. Thread dumps place startup inside `NetworkModule.provideOkHttpClient`
  and Hilt graph construction.
