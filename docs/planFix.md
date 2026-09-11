# Performance Remediation Plan

**Addresses:** all 59 findings in `docs/performance-audit.md` (10 Critical · 18 High · 27 Medium · 4 Low).
**Baseline:** commit `740bd55f`, device Amazon Fire TV Stick AFTSSS (Fire OS 9 / Android 9, 4 cores, 922 MB RAM, 32-bit armeabi-v7a).
**Supersedes:** `docs/planFixAudit.md` (deleted; this is now the single canonical plan).

---

## 0. How to read this document

Every finding gets its own **card**: location, root cause, the change to make, the files to touch, the
regression test that proves behaviour is preserved, how to validate the win on device, and the risk.

**Effort:** `S` = under 2 h · `M` = half a day · `L` = 1–2 days · `XL` = over 2 days.
**Risk:** `low` = mechanical, behaviour-preserving · `med` = touches behaviour or concurrency, needs tests · `high` = could regress playback, timeshift, or data.

Ordering is by **(measured impact ÷ risk)**, not by subsystem. Section 1 is the strategy; section 2
is the validation protocol; sections 3–6 are the cards.

---

## 1. Execution strategy

### 1.1 Dependency graph

```
Phase 1 (C1 hoists)  ──┬──▶ Phase 2 (sync hot path)  ──▶ Phase 4 (read path)  ──▶ acceptance test
                       ├──▶ Phase 3 (EPG ingest)
                       └──▶ Phase 5/6/7 (playback, UI, network) — measurable only after Phase 1
```

**Phase 1 is a hard prerequisite for every before/after measurement.** While the app burns 3 of 4
cores at idle, no other measurement is trustworthy. Land it, re-baseline §2.3, then proceed.

### 1.2 PR sequence

| PR | Contents | Findings closed | Effort |
|---|---|---|---|
| **1** | Phase 1 — all class-C1 hoists | A1, A2, A3(partial), A5(partial), A10, A26(partial), A28(partial), A29, A30, A46, A48(partial), A50, A51(partial) | L |
| **2** | Sync hot path | A4, A26, A27, A31, A43, A44, A51, A52, A54, A58 | XL |
| **3** | EPG ingest | A5, A11, A12, A13, A24(partial), A53 | L |
| **4** | Read path | A3(finish), A6, A9 | M |
| **5** | Playback + timeshift | A7, A8, A14, A15, A16, A33, A34, A35, A47, A49, A57 | XL |
| **6** | UI + Compose | A17, A18, A19, A22, A23, A24, A25, A39, A40, A41, A42 | L |
| **7** | Network | A20, A21, A36, A37, A38, A45 | M |
| **8** | Correctness follow-ups | A13(secondary), A39, A59 + dead-code removal | S |

### 1.3 Batched work items — one change closes many findings

1. **The C1 hoist sweep.** ~15 sites share one mechanical fix. Do them in a single PR with one shared
   golden test, not 15 PRs: A1, A2, A10, A28, A29, A30, A46, A50, plus the parse-time half of A5 and
   A51.
2. **"Classify once" refactor.** A3, A6 and A9 are three symptoms of one design problem — the
   catalog is reclassified on read instead of persisted at ingest. Fix them together, or the win
   from A6 will be re-lost to A3.
3. **Timeshift accounting.** A8 and A35 interact: changing the chunk interval changes how many files
   A8's walk visits. Fix A8's incremental counter **before** tuning anything about chunks.
4. **Main-thread mapping.** A23, A24, A25 and A41 are all "mapping/sorting runs on the collector's
   dispatcher". One audit of `app/.../ui/screens/**/*ViewModel*.kt` for missing `flowOn`/`withContext`
   closes all four.

### 1.4 Full sequencing table

| ID | Tier | Phase | Effort | Risk | Depends on |
|---|---|---|---|---|---|
| A1 | Critical | 1 | S | low | — |
| A2 | Critical | 1 | S | low | — |
| A3 | Critical | 1→4 | M | med | A1, A2 |
| A4 | Critical | 2 | S | low | — |
| A5 | Critical | 1→3 | M | med | PR 1 |
| A6 | Critical | 4 | L | med | A1–A3 |
| A7 | Critical | 5 | S | low | PR 1 |
| A8 | Critical | 5 | M | med | — |
| A51 | Critical | 1→2 | M | low | — |
| A52 | Critical | 2 | L | med | — |
| A9 | High | 4 | M | med | A6 |
| A10 | High | 1 | S | low | — |
| A11 | High | 3 | M | med | — |
| A12 | High | 3 | L | med | — |
| A13 | High | 3 | S | med | — |
| A14 | High | 5 | M | med | PR 1 |
| A15 | High | 5 | S | low | — |
| A16 | High | 5 | S | low | — |
| A17 | High | 6 | M | med | — |
| A18 | High | 6 | M | med | — |
| A19 | High | 6 | M | med | — |
| A20 | High | 7 | M | high | — |
| A21 | High | 7 | S | med | — |
| A53 | High | 3 | S | low | — |
| A54 | High | 2 | M | med | A52 |
| A55 | High | 2 | S | low | — |
| A56 | High | 2 | S | low | — |
| A57 | High | 5 | M | med | — |
| A22–A46 | Medium | 2/3/6/7 | S–M each | low–med | per card |
| A58 | Medium | 2 | M | med | — |
| A59 | Medium | 8 | S | low | — |
| A47–A50 | Low | 1/5/6 | S each | low | PR 1 |

---

## 2. Validation protocol

### 2.1 Device instrumentation

```bash
DEV=192.168.0.2:5555
PID=$(adb -s $DEV shell pidof com.streamvault.app | tr -d '\r')

# Process CPU over a fixed window
A=$(adb -s $DEV shell cat /proc/$PID/stat | awk '{print $14+$15}'); sleep 10
B=$(adb -s $DEV shell cat /proc/$PID/stat | awk '{print $14+$15}')
echo "$(( (B-A)/10 ))% of one core"

# Per-thread CPU — identifies WHICH thread
adb -s $DEV shell top -n 1 -b -H -p $PID | sed -n '6,12p'

# Allocation churn — primary regression signal for Phase 1
adb -s $DEV logcat -c && sleep 30
adb -s $DEV logcat -d | grep -c "Background concurrent copying GC"

# Heap trend — must stay flat (proves churn removed, not moved)
adb -s $DEV exec-out run-as com.streamvault.app \
  cat /data/data/com.streamvault.app/files/diagnostics/runtime-memory.log | tail -n 5

# Cold start
adb -s $DEV shell am force-stop com.streamvault.app; sleep 3
adb -s $DEV shell am start -W -n com.streamvault.app/.MainActivity

# Built-in slow-query log (debug builds, 100 ms threshold, tag RoomSlowQuery)
adb -s $DEV logcat -d | grep "RoomSlowQuery"
```

### 2.2 Thread-dump sampling — the decisive instrument

`kill -3` **does not work** on this device: ART writes the dump to `/data/anr/trace_00`, mode `640`
`tombstoned:system`, unreadable by `shell` or `run-as` without root, and `debuggerd -b` requires
root. Use JDWP:

```bash
adb -s $DEV forward tcp:8700 jdwp:$PID
printf 'suspend\nwhere all\nresume\nquit\n' | jdb -attach localhost:8700 > /tmp/dump_$i.txt
```

`suspend` **must** precede `where all`, or every thread reports "Current thread isn't suspended".
Take **≥16 samples** and aggregate:

```bash
cat /tmp/dump_*.txt | grep -hE "^  \[[1-8]\] com\.streamvault" \
  | sed 's/^ *\[[0-9]*\] //; s/ (.*//' | sort | uniq -c | sort -rn
```

### 2.3 Acceptance thresholds

| Metric | Baseline (740bd55f) | Target |
|---|---|---|
| Idle/background process CPU | 135–320 % | **< 20 %** |
| GC events per 30 s (idle) | ~30 | **< 5** |
| `javaUsedMb` in `runtime-memory.log` | flat 8–10 MB | flat (unchanged — must remain a non-leak) |
| Time to first frame | `+3s082ms` | **< 1.5 s** |
| Cold start TotalTime (median of 3) | 3043 ms | **< 2000 ms** |
| 16-dump active pipeline | 4/16 in normalizer | **0/16** |
| `Pattern.compile` in dumps | present | **absent** |

**Re-baseline every threshold after PR 1.** Later phases compare against post-PR-1 numbers.

**Live-TV changes additionally require** the full protocol in `AGENTS.md`: 2-second screenshot cadence
for ≥90 s (61 frames preferred), media session `PLAYING` with `error=null`, no fatal player error, no
stuck-player timeout, no unintended MPEG-TS fallback, on **two** channels.

---

## 3. CRITICAL findings

### A1 · `ChannelNormalizer.containsStandalone` compiles a regex per call
**Location:** `domain/src/main/java/com/streamvault/domain/util/ChannelNormalizer.kt:288-291`
**Cause:** `val regex = Regex(...)` built on every call and discarded. Called from `resolveCodecLabel` (`:226`), `resolveTransportLabel` (`:231-235`), `resolveSourceHint` (`:239`), `resolveLanguageHint` (`:262`) — each inside a `firstOrNull` over a tag map (10/5/11/21 entries), twice per entry (name + URL). ≈66 compiles per channel.
**Change:**
1. Replace the regex entirely with a boundary scan: find `token` via `indexOf`, require non-alphanumeric (or string edge) on both sides. Keep `IGNORE_CASE` by lowercasing both inputs at the call sites (they already pass `lowerName`/`lowerUrl`).
2. If regex readability is preferred, memoise `private val standaloneRegexCache = HashMap<String, Regex>()` — the tag sets are fixed (~47 distinct tokens).
**Files:** `ChannelNormalizer.kt`
**Test:** extend the existing `domain/src/test/java/com/streamvault/domain/util/ChannelNormalizerTest.kt` with a **golden-output** fixture (~200 real channel names → expected `ChannelClassification`) plus adversarial cases: token at string start/end, token adjacent to digits, `dv` inside a word, `"mpeg-ts"` vs `"mpeg ts"`.
**Validate:** §2.2 — `Pattern.compile` must disappear from the aggregated histogram; `containsStandalone` drops out of the active-frame list.
**Risk:** low — pure hoist, guarded by the golden test.
**Effort:** S

### A2 · Six further per-call `Regex(...)` sites in `ChannelNormalizer`
**Location:** `ChannelNormalizer.kt:217` (inside `resolutionTags.forEach`), `:181`, `:202`, `:203`, `:283`, `:315`
**Cause:** Same mechanism as A1 at six more sites. `:217` is the worst: a fresh `Regex` per iteration of a 22-entry loop, so a name with no resolution token compiles all 22; `:283` duplicates the `dv` lookaround already compiled at `:181`.
**Change:**
1. `:217` — replace with a file-scope `private val resolutionTagMatchers: List<Pair<Regex, Int>>` built once from `resolutionTags`, mirroring the existing precompiled set at `:14-20`.
2. Hoist `:181`, `:202`, `:203`, `:283`, `:315` to file-level `private val`s; `:283` reuses `:181`'s pattern rather than duplicating it.
**Files:** `ChannelNormalizer.kt`
**Test:** same golden fixture as A1; additionally assert the hoisted set is built once (a counter on the initialiser).
**Validate:** §2.2 — `resolveDeclaredHeight` must vanish from the active-frame histogram.
**Risk:** low. **Effort:** S

### A3 · `buildCanonicalName` runs ~57 sequential full-string passes per channel
**Location:** `ChannelNormalizer.kt:190-209`, loop at `:195-197` over the 49 patterns from `:101-109`
**Cause:** `removableCanonicalPhrases.forEach { cleaned = cleaned.replace(regex, " ") }` re-scans the whole remaining string 49 times, allocating a new `String` each pass, whether or not anything matches. Plus `bracketRegex`, `leadingRegionRegex`, `heightRegex`, `frameRateRegex`, `separatorRegex`, `collapseWhitespaceRegex`. **This is a different mechanism from A1/A2 and survives their fix.**
**Change (pick one, in order of preference):**
1. **Tokenise once** — split the name on separators, match each token against a `Set<String>` derived from the same tag maps, and rebuild. Turns 49 full-string scans into one split plus O(tokens) set lookups.
2. **Single alternation** — build one `Regex` from the 49 phrases (`\b(?:phrase1|phrase2|…)\b`) and do one `replace`. Cheaper to implement, still one full scan.
3. **Interim** — early-exit the loop when `cleaned` is unchanged after a pass.
**Files:** `ChannelNormalizer.kt:190-209`
**Test:** the A1 golden fixture **must** pass byte-identical — this function feeds `logicalGroupId`, so any drift silently re-groups the user's channels. Add explicit cases for names that are nothing but tags (e.g. `"HD 1080p FPS"`).
**Validate:** §2.2 — `buildCanonicalName` must leave the active-frame histogram; expect a large drop in the GC rate.
**Risk:** **med** — output feeds channel grouping identity; the golden test is mandatory, not optional.
**Effort:** M

### A4 · Every catalog item is JSON-decoded three times
**Location:** `data/src/main/java/com/streamvault/data/remote/xtream/OkHttpXtreamApiService.kt:780` + `:788`; same shape at `:592` + `:600`
**Cause:** `JsonParser.parseReader` builds a Gson tree → `element.toString()` re-serialises it to a fresh `String` → `json.decodeFromString(deserializer, element.toString())` parses that String again. Three traversals plus a transient JSON String per item.
**Change (CORRECTED 2026-09-11 — the original suggestion does not compile):** an earlier revision of
this card proposed `json.decodeFromJsonElement(deserializer, element)`. **That is wrong**:
`JsonParser.parseReader` returns a **Gson** `com.google.gson.JsonElement`, while
`decodeFromJsonElement` requires a **kotlinx** `kotlinx.serialization.json.JsonElement`. The mismatch
was confirmed by attempting it — `Argument type mismatch: actual type is 'com.google.gson.JsonElement!',
but 'kotlinx.serialization.json.JsonElement' was expected` at both sites.

The real fix must remove **Gson** from the per-item path, not just the `toString()`:

1. Replace the Gson `JsonReader` framing loop with a kotlinx streaming decoder
   (`Json.decodeFromStream` in kotlinx-serialization 1.9.0, or a `JsonDecoder` over a reader),
   decoding `XtreamLiveStreamRow` directly from the stream.
2. Preserve the existing failure contract: `XtreamParsingException` carrying `descriptor.hint` and the
   sanitized preview, raised on both a JSON syntax error and a serializer error. The loop currently
   relies on Gson's `isLenient = true`; kotlinx `Json { isLenient = true }` is the analogue but the two
   are **not** identical, so malformed-input behaviour must be pinned by tests before switching.
3. The same treatment applies to the generic path at `:780`/`:788`.

**Files:** `OkHttpXtreamApiService.kt`
**Test:** golden fixtures for (a) a well-formed array, (b) a malformed element mid-array, (c) a payload
relying on lenient parsing — asserting identical DTOs and identical `XtreamParsingException` messages
before and after.
**Validate:** `Debug.getGlobalAllocCount()` deltas across one catalog sync. Expect ~7 MB fewer
transient strings per 15k-channel sync.
**Risk:** **med** (was "low") — rewrites a parser on the ingest path and swaps the JSON engine for that
loop. **Effort:** M (was S)

### A5 · `XmltvParser.parseDate` throws up to 11 exceptions per call, twice per programme
**Location:** `data/src/main/java/com/streamvault/data/parser/XmltvParser.kt:567-616`; call sites `:146-147`, `:265-266`, `:415-416`
**Cause:** The probe chain uses `runCatching { … }.getOrNull()` (`:630-649`), so **every miss constructs and throws a `DateTimeParseException` with a captured stack trace** — the expected path, not an error path. `parseDate` runs twice per `<programme>` (start + stop) and `EPG_MAX_PROGRAMMES = 2_000_000`.
**Change:**
1. Replace the probing with `DateTimeFormatter.parse(CharSequence, ParsePosition)`, which returns an error index and throws nothing.
2. Or collapse the whole chain into a single `DateTimeFormatterBuilder` with `optionalOffset()` and permissive patterns.
3. Independently, hoist `:601` (`Regex("[^\d]")`) and `:605` (`DateTimeFormatter.ofPattern`) — see A5' in PR 1.
**Files:** `XmltvParser.kt`
**Test:** `XmltvParserTest.kt` — assert the same instants for every existing fixture shape (`:91`, `:161`, `:181`, `:204` are offset-less; `:50-64` carry `+0000`). Add an assertion that **zero** `DateTimeParseException` instances are constructed (counter on a seam).
**Validate:** method-trace one EPG refresh; `Throwable.<init>` must not dominate. Count parse failures logged per refresh.
**Note:** reviewers disagreed on 5 vs 11 misses per call because it depends on the feed shape. The test settles the real number; the mechanism is the same either way.
**Risk:** **med** — date parsing feeds guide correctness. Golden instants required.
**Effort:** M

### A6 · `observeChannels` reclassifies the entire catalog on every emission
**Location:** `data/src/main/java/com/streamvault/data/repository/ChannelRepositoryImpl.kt:330-353`, `:450-469`
**Cause:** A `combine` of six flows (including user preferences) whose transform runs, per emission: visibility filter → hidden-id filter → `buildPresentedChannels` → a per-channel `copy` map → `applyNumbering`. `buildPresentedChannels` calls `ChannelNormalizer.classify` for **every** channel — the full cost of A1+A2+A3. No LIMIT. Any preference toggle reclassifies the whole catalog.
**Change:**
1. Persist the classification at ingest (A9) or memoise it in an LRU keyed by `(name, providerId)`, so the read path is a lookup rather than a recompute.
2. Collapse the per-channel `copy` at `:462-468` — only channels in `unlockedCats` need a copy; the rest can pass through unchanged.
3. Make the emission window explicit rather than unbounded.
**Files:** `ChannelRepositoryImpl.kt`, plus the entity/DAO layer if classification is persisted
**Test:** a test asserting that changing a preference (parental level, grouping mode, hidden ids) invokes `classify` **zero** times — instrument with a counting seam.
**Validate:** §2.2 — this is the **acceptance test for the whole audit**: 0/16 samples in the presentation pipeline.
**Risk:** **med** — grouping/visibility behaviour is user-visible. Golden test required.
**Effort:** L

### A7 · `PlayerScreen` recomposes at frame rate while a recording is active
**Location:** `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt:952-984`
**Cause:** `rememberInfiniteTransition` (`:953`) drives `animateFloat` (`:954-962`) read **in the composition phase** and again at `:975`. A composition-phase animated-State read invalidates the restart scope of the reading composable = the entire ~1370-line `PlayerScreen` body (56 `collectAsStateWithLifecycle`, 36 `remember`/`LaunchedEffect`). `tween(750)` + `RepeatMode.Reverse` never stops while recording.
**Change:**
1. Move the alpha into the draw phase: `Modifier.graphicsLayer { alpha = recordingAlpha.value }` (read `.value` inside the lambda, not with `by` in composition).
2. Or extract the indicator into its own leaf composable so only it re-executes.
**Files:** `PlayerScreen.kt:952-984`
**Test:** a Compose test asserting the indicator still blinks and that `PlayerScreen` recomposition count stays flat while `status == RECORDING`.
**Validate:** `dumpsys gfxinfo` framestats during recording vs idle; recomposition counter on `PlayerScreen`.
**Risk:** low. **Effort:** S

### A8 · Timeshift invalidates its disk-usage cache every chunk, forcing a directory stat-walk every 2 s
**Location:** `player/.../timeshift/LiveTimeshiftManager.kt:734-737` → `TimeshiftDiskManager.kt:73-77`, `:89-110`, `:97-107`; `LiveTimeshiftManager.kt:566-574`
**Cause:** Each finalised chunk calls `recordFileMutation()` (sets `cachedUsageBytes = -1`) then immediately `checkDiskAndBudget()` → `isWithinBudget()` → `currentUsageBytesLocked()`, which sees the invalidated cache and re-walks the whole tree. Per file: 2 stat syscalls + ~5–7 objects. `PROGRESSIVE_CHUNK_MS = 2_000L` (`:1435`); default 30-min depth ⇒ up to 900 `chunk-*.ts` files. The walk holds `accountingLock`.
**Change:**
1. Maintain an **incremental byte counter** updated on every write/delete instead of invalidating and re-walking.
2. Keep the full walk only as a periodic reconciliation (e.g. once per session start, or every N minutes).
3. Ensure the lock is not held across the walk.
**Files:** `TimeshiftDiskManager.kt`, `LiveTimeshiftManager.kt`
**Test:** extend `LiveTimeshiftManagerTest.kt` — add 900 synthetic files and assert `isWithinBudget()` is O(1) w.r.t. file count (time 1000 calls before/after).
**Validate:** counters around `currentUsageBytesLocked` (calls, files visited, ms) logged once per 10 s during a 10-minute rewind; expect ~1 walk per session instead of 1 per 2 s.
**Risk:** **med** — disk-quota correctness guards against filling the device. Any counter must be conservative (over-estimate, never under).
**Effort:** M · **Note:** prerequisite for any chunk-interval tuning (interacts with A35).

### A51 · Catalog fingerprinting per row: 4–10 regex compiles, 1–4 URI parses, 32 `Formatter`s
**Location:** `data/src/main/java/com/streamvault/data/sync/SyncCatalogStore.kt:930` (`Regex` per call), `:926`, `:923` (32× `String.format`), `:939` (`URI` per call), `:916`
**Cause:** `normalizeText` builds a `Regex` inside the function body; `fingerprint()` renders SHA-256 as `joinToString("") { "%02x".format(it) }` = **32 `java.util.Formatter` instances plus 32 boxed bytes and 32 strings per digest**; `normalizeUrl` constructs a `java.net.URI` per URL field.
**Change:**
1. Hoist the `\\s+` regex to a file-level `private val`.
2. Replace `"%02x".format(...)` with a static `HEX` char array lookup.
3. Avoid re-parsing URLs already parsed elsewhere (pairs with A27).
**Files:** `SyncCatalogStore.kt`
**Test:** a JVM benchmark over a 50k-row synthetic catalog with `measureTimeMillis` + allocation counting; assert identical fingerprints (golden set) before/after.
**Validate:** the staging phase is already timed (`SyncManagerXtreamLiveStrategy.kt:275-287` reports `staging=…ms`); compare. On device, look for `java.util.Formatter`/`URI` frames in a perfetto trace during sync.
**Risk:** low — fingerprints must not change or every row re-syncs. Golden test mandatory.
**Effort:** M

### A52 · Stage-merge `UPDATE`s use 15–21 correlated subqueries per row, re-run over the whole catalog every 500 channels
**Location:** `data/src/main/java/com/streamvault/data/local/dao/CatalogSyncDao.kt:203-320` (channels: 15 + 1), `:391-545` (movies: 20 + 1), `:636-774` (series: 18 + 1)
**Cause:** `UPDATE t SET col = (SELECT stage.col FROM stage WHERE …)` repeated per column — SQLite cannot share the stage lookup, so each changed row costs one index probe **per column**. Worse, `commitStagedLiveCatalogProgress` (`SyncCatalogStore.kt:208-214`) re-runs it with no watermark and a `WHERE` of only `provider_id`, re-scanning every channel each time; the stage table is never cleared between passes. `LIVE_PROGRESS_COMMIT_CHANNEL_INTERVAL = 500` ⇒ ~60 executions × 30k rows = **~1.8 M row visits** for a 30k catalog. **O(catalog²/500).**
**Change:**
1. Rewrite using SQLite row values: `UPDATE t SET (a,b,…) = (SELECT stage.a, stage.b, … FROM stage WHERE …)` — supported since SQLite 3.15, present on Android 9. One probe per row instead of 15–21.
2. Add a **monotonic watermark** so each progress commit merges only newly staged rows.
3. Verify index coverage is retained (`Entities.kt:471`, `:506`, `:547`, `:93`).
**Files:** `CatalogSyncDao.kt`, `SyncCatalogStore.kt`
**Test:** extend `data/src/test/java/com/streamvault/data/local/ChannelBrowseQueryPlanTest.kt:63-74` (already a real-SQLite `EXPLAIN QUERY PLAN` harness) to assert no repeated scalar-subquery nodes. Add a store-level test asserting the merge is idempotent and yields identical rows.
**Validate:** count `RoomSlowQuery` lines containing `UPDATE channels` during a sync (100 ms threshold, already enabled in debug via `SlowQueryLoggingOpenHelperFactory`).
**Risk:** **med** — this is the sync's write path; a wrong watermark silently drops updates. Test idempotency explicitly.
**Effort:** L

---

## 4. HIGH findings

### A9 · `ChannelRepositoryImpl` classifies per row; grouped mode classifies the whole pool per emission
**Location:** `ChannelRepositoryImpl.kt:781-806` (`classify` at `:784`), `buildGroupedChannels` `:471-483`, `observeChannels` `:95`, `:715-720`
**Cause:** Same design problem as A6 — classification happens on read, per row, with no LIMIT, on the collector's dispatcher.
**Change:** classify once at ingest and persist, or memoise per `(name, providerId)`; see A6. Fix together.
**Files:** `ChannelRepositoryImpl.kt` (+ entity/DAO if persisted)
**Test:** counting seam asserting zero `classify` calls on a read-path emission.
**Validate:** §2.2 histogram. **Risk:** med. **Effort:** M · **Depends on:** A6

### A10 · `createDateTimeFormat` builds a `DateFormat` per call, from composition
**Location:** `app/src/main/java/com/streamvault/app/ui/time/AppTimeFormatters.kt:19-23` (and `:25-29` for `DateTimeFormatter`)
**Cause:** Confirmed by thread dump: `DateFormat.getDateTimeInstance` → `SimpleDateFormat.<init>` → `NumberFormat.getIntegerInstance` → `DecimalFormat.<init>` → `DecimalFormatSymbols.getIcuDecimalFormatSymbols`, reached from `rememberSettingsScreenLabels (SettingsScreenState.kt:83)` inside composition.
**Change:**
1. Cache instances per `(AppTimeFormat, Locale)` in a small map (note: `DateFormat` is **not thread-safe** — either cache per-thread or return a fresh clone from a cached prototype; `clone()` is far cheaper than construction).
2. Ensure callers `remember` the result keyed on format + locale.
**Files:** `AppTimeFormatters.kt`, `SettingsScreenState.kt`
**Test:** a test asserting repeated calls with the same key do not construct a new formatter; a Compose test asserting recomposition does not re-create it.
**Validate:** §2.2 — the `DateFormat`/`DecimalFormat` frames must leave the histogram.
**Risk:** low, **but** thread-safety: never share a mutating `DateFormat` across threads.
**Effort:** S

### A11 · Whole XMLTV refresh retried as a unit, with no size awareness
**Location:** `data/.../sync/SyncManager.kt:4305-4307`, `:4340-4342`, `:4384-4389`; policy `SyncManagerXtreamSupport.kt:114-140`
**Cause:** `retryTransient` (3 attempts, fixed 700 ms/1.4 s, no jitter) wraps download + gunzip + XML parse + staging insert. A reset at 90 % of a 50–200 MB feed restarts from byte 0.
**Change:**
1. Narrow the retry to the connection phase, or make the download resumable (ETag/`Range`).
2. Use the health-scaled `retryDelayFor` (`XtreamAdaptiveSyncPolicy.kt:159-174`) instead of fixed delays.
3. Add jitter to de-align concurrent workers.
**Files:** `SyncManager.kt`, `SyncManagerXtreamSupport.kt`
**Test:** `MockWebServer` truncating a body at ~50 % — assert exactly **one** download attempt per logical failure of the parse phase.
**Validate:** count HTTP request starts per sync.
**Risk:** med — retries exist for a reason; do not remove resilience, just stop redoing work. **Effort:** M

### A12 · EPG refresh rewrites the whole `programs` table every cycle
**Location:** `data/.../repository/EpgRepositoryImpl.kt:364-371`
**Cause:** `deleteByProvider` + `moveToProvider` = DELETE N + UPDATE N in one transaction, on a table with six indices including a UNIQUE one (`StreamVaultDatabase.kt:239-244`) — every row pays index delete+reinsert. Repeated every 6 h TTL even for identical feeds.
**Change:**
1. Stage under the **real** provider id and swap via `INSERT … SELECT` carrying the target id, avoiding the second index-maintenance pass.
2. Skip the rewrite entirely when the feed is unchanged (hash/ETag comparison).
**Files:** `EpgRepositoryImpl.kt`
**Test:** assert row-level identity after refresh; assert no write occurs for an identical feed.
**Validate:** log `programs` row count and transaction wall-clock; measure WAL growth during a sync.
**Risk:** med — guide data is user-visible; partial-failure atomicity must be preserved. **Effort:** L

### A13 · Full EPG re-resolution runs even when the download was skipped
**Location:** `data/.../epg/EpgResolutionEngine.kt:61-251`; invoked `SyncManager.kt:4404` (and `:4391`, `:4401`, `:4419`, `:4439`, `:4443`, `:4469`, `:4508`, `:4511`)
**Cause:** The TTL gate at `SyncManager.kt:4293-4295` can skip the download, but control falls through and `:4404` still runs the full pass — all channels loaded, every `ChannelEpgMappingEntity` rebuilt, `replaceForProvider` doing DELETE-all + INSERT-all.
**Change:** make the resolution conditional on the same gate; when the feed is unchanged, skip. Also chunk the `IN` list at `:110-112` to 500 (see A59).
**Files:** `SyncManager.kt`, `EpgResolutionEngine.kt`
**Test:** a test asserting no resolution pass runs when `lastEpgSuccess` is within TTL.
**Validate:** time `resolveForProvider` and count rows written to `channel_epg_mappings` per sync.
**Risk:** med — mappings must still refresh when the source genuinely changes. **Effort:** S

### A14 · Live playback re-queries a 30-hour EPG window every 30 s and sorts it on Main
**Location:** `app/.../ui/screens/player/PlayerEpgActions.kt:7`, `:28-53`; `EpgRepositoryImpl.kt:432`, `:438-440`; `PlayerProgramTimelineSupport.kt:21-31`
**Cause:** Infinite `viewModelScope` loop requests `now-24h..now+6h` every 30 s. After `withContext(IO)` returns, `shiftAll().sortedBy{}` runs on **Main**. `buildProgramTimeline` then sorts the same list **three times** and calls `isArchivePlayable` per programme, allocating an `ArchivePlaybackCapability` each time — also on Main. 120 iterations/hour.
**Change:**
1. Shrink the window to what the UI shows (the +6 h/−24 h range is far larger than any need).
2. Move the shift+sort inside `withContext(Dispatchers.Default)`.
3. Collapse the three sorts into one; hoist the per-channel `isArchivePlayable` decision out of the per-programme loop.
**Files:** `PlayerEpgActions.kt`, `EpgRepositoryImpl.kt`, `PlayerProgramTimelineSupport.kt`, `ArchivePlayback.kt`
**Test:** unit test asserting no main-thread work (collect with a `TestDispatcher` and assert dispatch).
**Validate:** `Choreographer` skipped-frame logs over 5 minutes of live playback with the EPS overlay open.
**Risk:** med — playback-adjacent; requires the live-TV protocol. **Effort:** M

### A15 · Per-second diagnostics file write during playback
**Location:** `player/.../Media3PlayerEngine.kt:397-402`, gate `:2260-2263`, `PlaybackSupportSnapshotStore.kt:17-21`
**Cause:** The 1 s tick builds an 18-line string and launches an IO coroutine that creates/truncates/writes/closes `filesDir/diagnostics/crash/latest-playback-support.txt`. 3 600 cycles/hour, not conflated, sharing flash with the timeshift writer and Media3 `SimpleCache`.
**Change:**
1. Write only on state **transitions**, not every tick.
2. Conflate the launch (cancel the in-flight job or use a `Channel(CONFLATED)`).
3. Consider keeping the snapshot in memory and flushing on crash/exit only.
**Files:** `Media3PlayerEngine.kt`, `PlaybackSupportSnapshotStore.kt`
**Test:** assert the file mtime changes only on transitions.
**Validate:** `ls -l --full-time` on the diagnostics dir twice, 60 s apart — expect 0 writes at steady state.
**Risk:** low — but keep the file populated on crash, since it exists for post-mortem support. **Effort:** S

### A16 · `PlayerScreen` recomposes once per second during all playback
**Location:** `PlayerScreen.kt:213`, `PlayerViewModel.kt:743-757`, `Media3PlayerEngine.kt:359-383`
**Cause:** `lastVideoFrameAgoMs` is recomputed as `now - lastFrameAt` (`VideoStallDetector.kt:41-44`), so it differs every tick; the `StateFlow` always emits; the ~1370-line root body re-executes each second even though diagnostics render only under `showDiagnostics` (`:1246`).
**Change:**
1. Gate the collection at `:213` on `showDiagnostics`.
2. Stop publishing `lastVideoFrameAgoMs` on a 1 Hz cadence — derive it in the UI when the overlay is open.
**Files:** `PlayerScreen.kt`, `PlayerViewModel.kt`, `Media3PlayerEngine.kt`
**Test:** assert no `playerDiagnostics` emission occurs while the overlay is closed.
**Validate:** recomposition counter on `PlayerScreen` over 60 s of playback with diagnostics hidden — expect ~0.
**Risk:** low. **Effort:** S

### A17 · EPG guide search loads the entire channel table and does three O(N) main-thread passes per keystroke
**Location:** `app/.../ui/screens/epg/EpgViewModel.kt:2007-2013`, `:1949-1996`, `:1437-1453`; DAO `data/.../local/dao/Daos.kt:96-110`
**Cause:** `getChannels(providerId).first()` hits `ChannelDao.getByProvider` with **no LIMIT**. The continuation resumes on `Dispatchers.Main.immediate`, so `mapNotNull{}` → `toMap()`, `filter { contains(ignoreCase=true) }`, `toSet()` and a third `buildList` pass all run on the UI thread over every channel.
**Change:**
1. Push the search predicate into SQL (a `LIKE`/`FTS` query) instead of loading everything.
2. Move any remaining mapping off Main with `withContext(Dispatchers.Default)`.
3. Raise the debounce from 150 ms and add `distinctUntilChanged`.
**Files:** `EpgViewModel.kt`, `Daos.kt`
**Test:** assert the query returns a bounded page; assert mapping runs off Main.
**Validate:** `RepositoryTimingReporter` `rows=` value per keystroke — must be small and constant, not the provider channel count.
**Risk:** med — search results must stay correct; keep a substring fallback. **Effort:** M

### A18 · EPG grid composes every programme in the window, non-lazily
**Location:** `app/.../ui/screens/epg/EpgGridComponents.kt:533-545`, markers `:514-523`, viewport `:170`
**Cause:** `programs.forEach { ProgramItem(...) }` in a plain `Row`/`Box` of full timeline width. Window is 7 h but only 3 h is visible, so ~2.3× the cells are composed per row, for every visible row.
**Change:**
1. Replace the inner `Row` with a `LazyRow` (add `key` per programme).
2. Or compose only the visible window and render beyond it on demand.
**Files:** `EpgGridComponents.kt`
**Test:** a Compose test counting `ProgramItem` compositions — must be bounded by visible cells, not window size.
**Validate:** `dumpsys gfxinfo framestats` during a scripted guide scroll before/after.
**Risk:** med — focus/D-pad navigation in the grid is delicate (see B7 in the prior audit); verify focus traversal explicitly.
**Effort:** M

### A19 · EPG grid callbacks are keyed on the whole `EpgUiState`
**Location:** `app/.../ui/screens/epg/EpgScreen.kt:455-486`; `EpgGridComponents.kt:118-135`, `:196-229`, `:333-354`
**Cause:** Lambdas close over the entire `uiState`, so any field change — including ones the grid never displays (`isPreviewLoading`, `previewErrorMessage`, `isRefreshing`, `lastUpdatedAt`, `isGuideStale`) — recreates them and defeats skipping into every visible row and cell.
**Change:**
1. Pass only the fields each callback needs (or use `rememberUpdatedState`), so the lambdas are stable across unrelated state changes.
2. Whitelist the UI state holder types in `compose_stability.conf` — only `com.streamvault.domain.model.*` is listed today, and `kotlinx.collections.immutable` is whitelisted but used nowhere.
3. Add `metricsDestination`/`reportsDestination` to the existing `composeCompiler {}` block (`app/build.gradle.kts:165-167`) and confirm the types become `skippable`.
**Files:** `EpgScreen.kt`, `EpgGridComponents.kt`, `compose_stability.conf`, `app/build.gradle.kts`
**Test:** Compose metrics report asserting `EpgGrid`/`EpgRow`/`ProgramItem` are skippable.
**Validate:** recomposition counts while toggling only the preview spinner.
**Risk:** med. **Effort:** M

### A20 · The H6 network isolation does not actually cover Stalker or EPG
**Location:** `app/.../di/NetworkModule.kt:82-86`, `:138-142`, comment at `:99-104`
**Cause:** `OkHttpClient.newBuilder()` **shares the same `Dispatcher` and `ConnectionPool` by reference**. `@BackgroundSyncClient` is used only by `SyncManager` for M3U/Xtream (`SyncManager.kt:216`, `:239-248`); Stalker uses the main client (`NetworkModule.kt:148`) and EPG uses a main-derived client (`EpgRepositoryImpl.kt:85-88`). EPG carries a 200 MB / 120 s budget.
**Change:**
1. Give Stalker and EPG genuinely separate clients — construct with `OkHttpClient.Builder()` or an explicitly new `ConnectionPool`+`Dispatcher`, not `newBuilder()`.
2. Correct the comment at `NetworkModule.kt:99-104`, which claims protection that is not in force.
**Files:** `NetworkModule.kt`, `EpgRepositoryImpl.kt`, `OkHttpStalkerApiService.kt`
**Test:** a unit test asserting the two DI clients do **not** share `dispatcher`/`connectionPool` identity (they compare equal today).
**Validate:** run playback + Stalker sync + EPG refresh together; compare player stall counters before/after.
**Risk:** **high** — changes network admission; can affect playback stability. Requires the live-TV protocol.
**Effort:** M

### A21 · The playback admission gate is never consulted by the main sync path
**Location:** gate `data/.../sync/PlaybackNetworkAdmissionGate.kt:64-77`; call sites only `StalkerIndexWorker.kt:50`, `BackgroundEpgSyncWorker.kt:56`
**Cause:** `ProviderSyncWorker` (`:99-107`) declares no gate and syncs every provider; inside `SyncManager` the only playback check is Stalker-only (`:1971`, `:2321`). Xtream catalog and every EPG refresh run unthrottled during playback.
**Change:** call `awaitBackgroundAdmission` from `ProviderSyncWorker` and the Xtream/EPG ingress points.
**Files:** `ProviderSyncWorker.kt`, `SyncManager.kt`
**Test:** a test asserting a sync defers while an active-playback count is non-zero.
**Validate:** start playback, force a sync, confirm the gate's `activePlaybackCount` affects request rate.
**Risk:** med — over-gating could stall sync indefinitely; bound the wait. **Effort:** S

### A53 · External XMLTV import inserts 500-row batches with no enclosing transaction
**Location:** `data/.../repository/EpgSourceRepositoryImpl.kt:387-390`, `:397-402`; correct pattern at `EpgRepositoryImpl.kt:283-291`
**Cause:** The parse callback calls `epgProgrammeDao.insertAll(...)` directly every 500 rows; only the staging→live swap (`:405-411`) is transactional. Each unwrapped insert is its own implicit transaction = commit + WAL frame + **fsync** (WAL sync mode is FULL). Up to ~4 000 commit+fsync cycles per source refresh.
**Change:** wrap both flushes in `transactionRunner.inTransaction { }`, exactly as `EpgRepositoryImpl` already does. Also drop the `.toList()` copy at `:388`/`:401`.
**Files:** `EpgSourceRepositoryImpl.kt`
**Test:** assert the flush count and that rows land atomically.
**Validate:** perfetto fsync count during an external-source refresh.
**Risk:** low — matches the sibling implementation. **Effort:** S

### A54 · Channel count/category-count Flows re-run `GROUP BY COUNT(DISTINCT CAST(...))` over the whole table per sync write
**Location:** `data/.../local/dao/Daos.kt:506`, `:509-519`, `:521-537`, `:539-556`, `:558-569`; consumers `ChannelRepositoryImpl.kt:355-372`, `:86-90`
**Cause:** Room `Flow` queries re-execute on **every `channels` invalidation**. The grouped variants compute `COUNT(DISTINCT CASE WHEN … THEN logical_group_id ELSE CAST(id AS TEXT) END)` — SQLite builds an ephemeral B-tree keyed on a per-row **derived text value**, allocating a string per row. The sync writes channels ~every 500 accepted, so this re-runs dozens of times per sync.
**Change:**
1. Coalesce/debounce the aggregate behind a sampled flow, or
2. Maintain counts incrementally on write.
**Files:** `Daos.kt`, `ChannelRepositoryImpl.kt`
**Test:** assert the aggregate is not recomputed more than once per N writes.
**Validate:** count `RoomSlowQuery` entries containing `COUNT(DISTINCT` per sync.
**Risk:** med — counts drive UI badges; stale counts are user-visible. **Effort:** M · **Depends on:** A52

### A55 · VOD duplicate resolution scans every movie/series, unindexed, materialising full rows
**Location:** `Daos.kt:1569`, `:1572`, `:1575`; series twins `:2574`, `:2577`; callers `MovieRepositoryImpl.kt:583-599`, `SeriesRepositoryImpl.kt:709-718`
**Cause:** `SELECT * FROM movies WHERE provider_id = ? AND tmdb_id = ?` (plus year / release_date-prefix variants, up to 3 queries) has only `provider_id` indexed. SQLite walks every movie row of the provider and materialises whole rows including large `plot`/`cast`/`director` TEXT.
**Change:**
1. Add indices `(provider_id, tmdb_id)` and `(provider_id, year)` — requires a Room migration.
2. Select only identity columns instead of `SELECT *`.
**Files:** `Entities.kt`, `StreamVaultDatabase.kt`, `Daos.kt`, migration test
**Test:** `EXPLAIN QUERY PLAN` asserting `SEARCH … USING INDEX` rather than `SCAN`; a Room migration test.
**Validate:** `RoomSlowQuery` entries on a single detail open.
**Risk:** med — schema change requires a correct migration (prior audit hardened migrations v62→v63; follow that pattern).
**Effort:** S

### A56 · Movie/series browse loads the entire source list and the entire playback history **twice** per page fetch
**Location:** `SeriesRepositoryImpl.kt:1091-1092`, `:1131-1132` (and `:1029-1036`); `MovieRepositoryImpl.kt:1092-1093`, `:1119-1120`; unbounded history at `Daos.kt:3321`
**Cause:** After building the page, when `duplicateHandlingMode != SHOW_ALL` the code re-runs `seriesBrowseSource(query).first()` **and** `playbackHistoryDao.getByProvider(...).first()` purely to compute `totalCount` via `.size`. That history query has **no LIMIT**.
**Change:** compute the filtered list once and reuse its `.size`; bound the history query.
**Files:** `SeriesRepositoryImpl.kt`, `MovieRepositoryImpl.kt`, `Daos.kt`
**Test:** assert each source is subscribed once per page fetch; assert `totalCount` unchanged.
**Validate:** `RepositoryTimingReporter` — one `rowCount` entry per fetch, not two.
**Risk:** low. **Effort:** S

### A57 · Every 5 s of VOD playback: a Room write transaction plus up to 40 ContentResolver writes
**Location:** `app/.../ui/screens/player/PlayerLifecycleActions.kt:17-24`, `:34`, `:36`, `:37`; `app/.../tv/WatchNextManager.kt:35-46`, `:83-91`, `:63-76`; `PlaybackHistoryRepositoryImpl.kt:185-211`
**Cause:** Each 5-second tick opens a Room transaction and then **unconditionally** calls `WatchNextManager.refreshWatchNext()`, which has **no throttle**: it re-queries the active provider and history, queries `TvContract.WatchNextPrograms` with a **null selection** (unbounded), then re-issues insert/update for up to 40 entries — up to 40 binder IPC calls per 5 s even when nothing changed.
**Change:**
1. Gate `refreshWatchNext` on an actual state change (position bucket / program change).
2. Bound the existing-entries query (pass a selection or limit).
3. Keep the resume-position write, but only when the position moved meaningfully.
**Files:** `PlayerLifecycleActions.kt`, `WatchNextManager.kt`, `PlaybackHistoryRepositoryImpl.kt`
**Test:** assert no ContentResolver write occurs when state is unchanged across ticks.
**Validate:** perfetto binder-transaction count to the TV provider per 5 s window during VOD playback.
**Risk:** med — Watch Next is user-visible on the Fire TV home row; must still update on genuine progress.
**Effort:** M

---

## 5. MEDIUM findings

| ID | Change | Files | Test / Validate | Risk | Effort |
|---|---|---|---|---|---|
| **A22** Eager log strings in EPG resolution | Guard the interpolation at `EpgResolutionEngine.kt:214-218` behind the log level; drop the two nested `enabledAssignments.any{}` scans | `EpgResolutionEngine.kt` | Assert no string built when logging is off | low | S |
| **A23** `getProgramsForChannelsSnapshot` maps/groups on caller's dispatcher | Wrap `EpgRepositoryImpl.kt:156-160` in `withContext(Dispatchers.Default)` (pattern: `EpgResolutionEngine.kt:267`) | `EpgRepositoryImpl.kt` | `TestDispatcher` assertion | low | S |
| **A24** `buildGuideDisplaySnapshot` O(ch × prog) with per-programme allocation, on Main | Hoist `isArchivePlayable` to the channel level; memoise; move off Main | `EpgViewModel.kt:1915-1930`, `ArchivePlayback.kt` | Assert ≤1 capability per channel per pass | med | M |
| **A25** No ViewModel in `app/ui` switches dispatcher | Add `flowOn`/`withContext` where mapping/sorting happens (`ChannelRepositoryImpl.kt:353` is the model) | `app/.../ui/screens/**/*ViewModel*.kt`, `CategoryDisplayPreferences.kt`, `MovieRepositoryImpl.kt:357-368` | Dispatcher assertion per transform | med | M |
| **A26** `channelFingerprint` per channel: regex + URI + 32 `String.format` | See A51 (same code) — hoist, hex table, reuse parsed URL | `SyncCatalogStore.kt` | Golden fingerprints | low | S |
| **A27** Same URL parsed twice, via reflective codecs | Carry `XtreamStreamToken` from `toEntity` forward; replace reflective `Method.invoke` in `XtreamUrlFactory.kt:27-42` with direct `URLDecoder` | `EntityMappers.kt:196`, `SyncCatalogStore.kt:822`, `XtreamUrlFactory.kt` | Assert 1 parse per channel | low | M |
| **A28** `SearchRankingUtils` recomputes selectors per comparison | Precompute lowercase keys before sorting (decorate-sort-undecorate); hoist the 2 regexes | `SearchRankingUtils.kt:10-27` | Counting selector asserts n calls, not n log n | low | S |
| **A29** `EpgNameNormalizer` compiles a regex per call | Hoist `:30` to a `private val`, matching `:16` | `EpgNameNormalizer.kt` | Counting test | low | S |
| **A30** M3U `stableId` compiles a regex twice per entry | Hoist `SyncManagerM3uImporter.kt:379` | `SyncManagerM3uImporter.kt` | Golden ids unchanged | low | S |
| **A31** `updateChangedChannelsFromStage` repeats the lookup 18× | Superseded by A52's row-value rewrite | `CatalogSyncDao.kt` | See A52 | med | — |
| **A32** EPG override search un-debounced, `%LIKE%` full scan per keystroke | Debounce 250–300 ms; drop `LOWER()` for a normalised column; prefix-match where possible | `EpgViewModel.kt:868-878`, `EpgSourceRepositoryImpl.kt:493-516`, `Daos.kt:3626-3635` | Assert query count per 8 keystrokes drops | med | M |
| **A33** `LiveAudioTapAudioSink` allocates per buffer with no tap | Move `asReadOnlyBuffer()` inside the `tap ?: return` guard at `LiveAudioTapAudioSink.kt:45` | `LiveAudioTapAudioSink.kt` | Allocation trace shows 0 with no tap | low | S |
| **A34** MEMORY-backend rewind retains 150–300 MB vs a 192 MB heap | Cap the MEMORY window against the heap class, or refuse MEMORY when the window cannot fit | `LiveTimeshiftManager.kt`, `LiveTimeshiftModels.kt` | `dumpsys meminfo` across a 5-min session | **med** | M |
| **A35** DASH timeshift enumerates + serially downloads the whole retention window | Start from the live edge on first poll; cache resolved URIs | `LiveTimeshiftManager.kt:1376-1390`, `:1124-1141` | Unit-test `parseMpd` with a synthetic dynamic MPD; assert segment count | med | M |
| **A36** Jellyfin image interceptor decrypts via Keystore per image | Cache the decrypted token per `(providerId, password)` using the existing 30 s window | `JellyfinImageAuthInterceptor.kt` | Counting fake asserts decrypt ≪ request count | med | S |
| **A37** Two `Cache` instances over one directory | Share one `Cache` instance, or use separate directories | `NetworkModule.kt:69-74`, `:125-130` | Assert the two caches do not share a directory | med | S |
| **A38** Blocking `execute()` bypasses caps; no `callTimeout` | Set `callTimeout` on the shared client; route blocking sites through `CancellableHttp.awaitResponse` | `NetworkModule.kt:75-77` + the 9 listed call sites | `MockWebServer` dribbling 1 byte/60 s must return bounded | med | M |
| **A39** `runCatching` swallows `CancellationException` | Rethrow `CancellationException` before mapping to `emptyMap()` at `EpgViewModel.kt:1704-1706`, `:1715-1718` | `EpgViewModel.kt` | Test cancelling mid-load publishes no empty snapshot | med | S |
| **A40** 30 s guide clock invalidates every row and cell | Remove the raw `currentGuideNow()` reads at `EpgGridComponents.kt:356` and `:674`; read the clock only in the leaf that formats it; collapse the 3 duplicate providers | `EpgGridComponents.kt`, `EpgScreen.kt`, `PlayerTransparentGuideOverlay.kt` | Recomposition counter per 30 s tick | med | M |
| **A41** `requestMoreChannels` copies growing collections on Main | Avoid `drop().take()` and `Map.plus` copies; use indices/append | `EpgViewModel.kt:577-579`, `:590`, `:594`, `:613`, `:618` | Time page 1 vs page 10 | low | M |
| **A42** `ChannelLogoBadge` recomputes initials in composition | `remember(channelName) { channelInitials(channelName) }` | `ChannelLogo.kt:52` | Allocation trace during guide scroll | low | S |
| **A43** Stalker `resolveCategory` linear scan per item | Build a `HashMap` keyed by rawId + lowercased name | `StalkerProvider.kt:1479-1483`, `:1333` | Count predicate evaluations per section | low | S |
| **A44** `FallbackCategoryCollector.record` allocates per channel | Reorder: cache lookup before constructing the candidate | `SyncManagerSupport.kt:119-136` | Allocation count `CategoryEntity` ≈ distinct categories | low | S |
| **A45** Network graph built on Application main thread | Make `okHttpClient`/`appCacheQuota` `dagger.Lazy` so they build after `startDeferredStartup()`; note `imageOkHttpClient` already uses `by lazy` | `StreamVaultApp.kt:46`, `:52`, `:72-74` | 20-launch time-to-first-frame delta | med | S |
| **A46** `PlayerMovieFallbackSupport` regex inside a comparator key | Hoist `:80`; precompute keys before sorting | `PlayerMovieFallbackSupport.kt` | Assert selector called n times | low | S |
| **A58** Live staging materialises two whole entity lists | Give the live path a `Sequence` entry point mirroring `stageChannelSequence` | `SyncCatalogStore.kt:75-99`, `:253-268`, `:565-594` | Extend `SyncCatalogStoreMemoryTest.kt:60` to 100k rows | med | M |
| **A59** Unchunked `IN (:ids)` can exceed SQLite 999 bind vars on Android 9 | Chunk at 900 in `Daos.kt:469` and the movie/series `getByIds`; check `EpgViewModel.kt:1678`, `PlayerPlaylistActions.kt:226`, `:235` | `Daos.kt` | androidTest with 1000 ids against real SQLite | low | S |

---

## 6. LOW findings

| ID | Change | Files | Effort |
|---|---|---|---|
| **A47** `DashWindow` hands out full list copies | Add a `mediaSize()` accessor; stop calling `mediaSegments()` in the eviction `while` condition (`LiveTimeshiftManager.kt:1131`) | `LiveTimeshiftManager.kt:1039`, `:1043` | S |
| **A48** Per-call `setOf(...)` and unconditional log arguments | Hoist the constant sets to `private val`s; guard `PlayerDataSourceFactoryProvider.kt:220-244` log strings with `BuildConfig.DEBUG`; drop the duplicated `withRequestProfile` application | `Media3PlayerEngine.kt:2080-2085`, `:1043`, `:1122`, `PlayerStatsCollector.kt`, `PlayerDataSourceFactoryProvider.kt`, `RequestIdentity.kt:31-43` | S |
| **A49** Live-dot blink recomposes the whole scrubber every 700 ms | Animate the dot alpha via `graphicsLayer`/`drawBehind`, or hoist the blink into a leaf composable | `PlayerControlsChrome.kt:1723-1729` | S |
| **A50** Per-call regex in error/track/timeshift parsers | Hoist each to a file-level `private val` | `PlayerErrorClassifier.kt:82`, `PlayerTrackController.kt:226`, `:262`, `LiveTimeshiftManager.kt:919`, `:1414-1417` | S |

---

## 7. Test plan — what to add

**Golden-output tests (mandatory before touching hot, behaviour-sensitive code):**

| Test | Guards | Why it matters |
|---|---|---|
| `ChannelNormalizerTest` golden fixture (~200 names → full `ChannelClassification`) | A1, A2, A3 | Output feeds `logicalGroupId`; drift silently re-groups the user's channels |
| Fingerprint golden set | A26, A51 | Any change re-syncs the entire catalog |
| `XmltvParserTest` instants + zero-exception assertion | A5 | Guide times must not shift |
| `stableId` golden ids | A30 | M3U imports would duplicate |

**New tests:** identify-collision test for the memoised classifier (A6/A9); `EXPLAIN QUERY PLAN` assertions for A52/A55 via the existing `ChannelBrowseQueryPlanTest` harness; a Room migration test for A55's new indices; `MockWebServer` truncation for A11; a dribbling-server timeout test for A38; an androidTest with 1000 ids for A59; Compose metrics assertions for A19.

**Reuse, don't rebuild:** `ChannelBrowseQueryPlanTest.kt` (real-SQLite query plans), `SyncCatalogStoreMemoryTest.kt` (peak-heap assertions), `M3uParserBenchmarkTest.kt`, `LiveTimeshiftManagerTest.kt`, `EpgViewModelTest`, and the debug-only `RepositoryTimingReporter` + `RoomSlowQuery` instrumentation.

---

## 8. Risk register

| Risk | Findings | Mitigation |
|---|---|---|
| **Channel grouping drift** — normaliser output feeds user-visible grouping | A1, A2, A3, A6, A9 | Golden fixture with ~200 real names must pass byte-identical |
| **Catalog re-sync storm** — any fingerprint change invalidates every row | A26, A51 | Golden fingerprint set; assert no re-sync on an unchanged catalog |
| **Guide correctness** — date parsing and window changes | A5, A12, A14 | Golden instants; assert guide times unchanged |
| **Playback regression** — network admission, timeshift, player screen | A8, A14, A20, A34, A35 | Full live-TV protocol (AGENTS.md): 61 screenshots, 2 channels, media session `PLAYING`, `error=null` |
| **Disk-quota correctness** — timeshift must never fill the device | A8 | Any incremental counter must over-estimate, never under; keep a periodic full reconciliation |
| **Migration failure** — new indices on a large existing DB | A55 | Room migration test; follow the v62→v63 hardening pattern |
| **Over-gating stalls sync** | A21 | Bound the admission wait; assert sync eventually proceeds |
| **Thread-unsafe formatter sharing** | A10 | `DateFormat` is not thread-safe — cache a prototype and `clone()`, or cache per-thread |
| **Measurement pollution** — later phases unmeasurable while Phase 1 is unfixed | all | Land PR 1 first; re-baseline §2.3 before measuring anything else |

---

## 9. Out of scope

- **Product features and refactors** unrelated to these 59 findings.
- **The soundness of prior remediation.** Recorded as fixed (B1–B13, tasks 4/10/11, M4, M5) and not
  re-litigated; only the two places where a completed fix is demonstrably incomplete (A40, A45) are
  reported.
- **Multi-provider and MultiView tuning.** MultiView already has a device-tier slot policy
  (`MultiViewViewModel.kt:700-735`); no finding was raised against it.
- **Release-only concerns** such as baseline profiles and R8 output — the debug build was measured.
- **Third-party behaviour** (Media3 `DefaultLoadControl` internals, OkHttp pooling) except where
  StreamVault misuses it (A20, A37, A38).

---

## 10. Definition of done

1. Every finding's card is either implemented or explicitly deferred with a recorded reason.
2. §2.3 thresholds met — in particular **idle CPU < 20 %** and **0/16** samples in the presentation pipeline.
3. Golden tests pass and were written **before** the behaviour-sensitive changes (A1–A3, A5, A26, A51).
4. Any finding touching playback, decoder, renderer, timeshift, or network admission passed the full
   live-TV protocol on **two** channels.
5. `docs/performance-audit.md` findings are annotated with their resolution (or a follow-up doc
   records what remains).
