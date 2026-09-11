# StreamVault Performance Audit

**Date:** 2026-09-11 · **Commit:** `740bd55f` (master, 3 commits ahead of `origin/master`)
**Supersedes** (all deleted in this change): `performance-audit.md`, `planFixAudit.md`, `performance-audit-results.md`, `planFix.md`, `buffering-investigation.md`.
**Companion:** `docs/planFix.md` (per-finding remediation cards, ordering, tests, validation).

---

## 1. Method

This audit is **measurement-first**. Runs of the real app on the real target device produced the
primary evidence; static reading then explained each mechanism and located it precisely. Every
finding below carries either a device measurement or an exact `file:line`, and most carry both.

Findings that could not be confirmed by measurement or by reading are marked **UNVERIFIED** rather
than asserted. A `Verified clean` section records what was checked and found sound, so the next
audit does not repeat that work.

### 1.1 Device under test — the performance bar

| Property | Value |
|---|---|
| Model | Amazon Fire TV Stick **AFTSSS**, codename `sheldonp` |
| OS | Fire OS 9 (Android 9 / API 28) |
| CPU | 4 cores |
| RAM | 922 MB total; **192 MB** per-app heap class |
| ABI | `armeabi-v7a` — **32-bit** |
| Root | none |

This is a weak target, deliberately. Code that is acceptable on a flagship phone can be
pathological here, and the app's own diagnostics confirm the device self-identifies as
low-memory (`isLowMemoryPlaybackDevice`, `Media3PlayerEngine.kt:236-241`).

### 1.2 Techniques used

| Technique | Command |
|---|---|
| Cold start | `adb shell am start -W -n com.streamvault.app/.MainActivity` |
| Time to first frame | `logcat` → `ActivityManager: Displayed` |
| Process CPU | delta of `/proc/<pid>/stat` fields 14+15 over a fixed window |
| Per-thread CPU | `adb shell top -n 1 -b -H -p <pid>` |
| **Java thread dumps** | `adb forward tcp:8700 jdwp:<pid>` then `jdb -attach localhost:8700`, `suspend` + `where all` |
| Allocation churn | ART GC lines in `logcat` |
| Heap trend | `run-as com.streamvault.app cat files/diagnostics/runtime-memory.log` |
| Footprint | `dumpsys meminfo` |

The **JDWP thread-dump technique is the key instrument** and is reusable. `kill -3` does *not*
work on this device — ART writes the dump to `/data/anr/trace_00`, which is `640`
`tombstoned:system` and unreadable by both `shell` and `run-as` on an unrooted Fire OS, and
`debuggerd -b` requires root. `jdb` over a JDWP forward works because the debug build is
debuggable. `suspend` **must** precede `where all`; without it every thread reports
"Current thread isn't suspended".

---

## 2. Headline measurement

The app **never idles.** With no activity in the foreground (`startedActivities=0`) it sustains
roughly **135–320 % of one core**, forever, on a 4-core device.

| Scenario | Process CPU | GC events |
|---|---|---|
| Cold start, first 20 s | — | **24** |
| Idle, backgrounded, no activity | 135 % → 294 % | ~1/sec |
| Live TV browse | ~271 % | 45 |
| EPG guide open | ~260 % | 33 |

Corroborating evidence:

- **Time to first frame: `Displayed com.streamvault.app/.MainActivity: +3s082ms`**; `am start -W`
  TotalTime over 3 cold runs: **3587 / 3043 / 2982 ms** (median ≈ 3.0 s).
- **Allocation churn ≈ 5 MB/s.** ART logs a background GC roughly every second freeing 4–6 MB:
  `Background concurrent copying GC freed 237261(6MB) AllocSpace objects ... paused 182us total 132.433ms`.
- **It is not a leak.** `runtime-memory.log` holds `javaUsedMb` flat at **8–10 MB** across the
  whole session with `lowMemory=false`. Steady heap + high GC rate = allocate-and-discard.
- **19 `DefaultDispatcher` worker threads** exist on a 4-core device. `Dispatchers.Default`
  parallelism is 4 here; this many live workers is the signature of *blocking* work occupying
  Default threads, forcing the scheduler to spawn replacements.
- **`ReferenceQueueDaemon` at 66–80 % CPU**, paired in the same stack with
  `libcore.util.NativeAllocationRegistry$CleanerRunner.<init>` — the cleaner registration that
  `java.util.regex.Pattern.compile` performs.

### 2.1 Where the time actually goes — 16 thread samples

16 thread dumps were taken while the app was foregrounded and working. In **4 of 16**, app code was
actively executing (present within the top 8 frames). **All four were in the same pipeline:**

```
ChannelRepositoryImpl.observeChannels$2.invokeSuspend (ChannelRepositoryImpl.kt:351)
  → buildPresentedChannels (ChannelRepositoryImpl.kt:457)
    → toPresentedRawChannel (ChannelRepositoryImpl.kt:811)
      → toVariant (ChannelRepositoryImpl.kt:784)
        → ChannelNormalizer.classify (ChannelNormalizer.kt:156)
          → buildCanonicalName (ChannelNormalizer.kt:196)
          → containsStandalone (ChannelNormalizer.kt:289)
          → resolveDeclaredHeight / resolveTransportLabel
```

Active app frames observed, by count: `classify` ×5, `buildCanonicalName` ×2,
`resolveDeclaredHeight` ×2, `classify$default` ×2, `resolveTransportLabel` ×1,
`containsStandalone` ×1, `getLogicalGroupId` ×1, plus the repository path above ×1 each.

The full captured stack for the top hit:

```
[1] java.util.regex.Matcher.setInputImpl (native method)
[5] java.util.regex.Matcher.<init> (Matcher.java:187)
[6] java.util.regex.Pattern.matcher (Pattern.java:1010)
[7] kotlin.text.Regex.replace (Regex.kt:180)
[8] ChannelNormalizer.buildCanonicalName (ChannelNormalizer.kt:196)
[10] ChannelRepositoryImpl.toVariant (ChannelRepositoryImpl.kt:784)
[12] ChannelRepositoryImpl.buildPresentedChannels (ChannelRepositoryImpl.kt:457)
[14] ChannelRepositoryImpl$observeChannels$2.invokeSuspend$lambda$2 (ChannelRepositoryImpl.kt:351)
[17] RepositoryTimingReporter.measure (RepositoryTimingReporter.kt:27)
```

And for the compile itself:

```
[1] libcore.util.NativeAllocationRegistry$CleanerRunner.<init>
[2] libcore.util.NativeAllocationRegistry.registerNativeAllocation
[3] java.util.regex.Pattern.compile (Pattern.java:1345)
[6] kotlin.text.Regex.<init> (Regex.kt:93)
[7] ChannelNormalizer.containsStandalone (ChannelNormalizer.kt:289)
[8] ChannelNormalizer.resolveTransportLabel (ChannelNormalizer.kt:232)
```

---

## 3. Cross-cutting defect classes

Six recurring shapes account for nearly every finding. Fixing the *classes* is worth more than
fixing instances.

### C1. Per-call construction of expensive, immutable objects — **the dominant class**

`java.util.regex.Pattern.compile`, `SimpleDateFormat`, `DateTimeFormatter.ofPattern` and
`java.net.URI` are all built per invocation on hot paths. `Pattern.compile` parses the pattern,
builds a node graph, emits a matcher program **and registers a native-allocation cleaner** — which
is precisely the `ReferenceQueueDaemon` + `CleanerRunner` signature measured above.

Known sites (this is the single highest-value cleanup in the codebase):

| Site | Note |
|---|---|
| `domain/.../ChannelNormalizer.kt:288` | **Confirmed on device.** Called per tag per channel |
| `domain/.../ChannelNormalizer.kt:217` | **Inside a `forEach` over 22 `resolutionTags`** |
| `domain/.../ChannelNormalizer.kt:181, :202, :203, :283, :315` | 5 further sites |
| `data/.../epg/EpgNameNormalizer.kt:30` | Neighbouring regex *is* hoisted at `:16` — inconsistent |
| `data/.../parser/XmltvParser.kt:601, :605` | `Regex` **and** `DateTimeFormatter` per fallback call |
| `data/.../dto/LenientJsonSerializers.kt:243` | Once per array element in catalog decode |
| `data/.../sync/SyncCatalogStore.kt:930` | `Regex("\\s+")`, ×3 per channel |
| `data/.../sync/SyncManagerM3uImporter.kt:379` | ×2 per playlist entry |
| `data/.../util/SearchRankingUtils.kt:10-11` | ×2 per search |
| `app/.../time/AppTimeFormatters.kt:19-28` | `DateFormat`/`DateTimeFormatter` per call, from composition |
| `app/.../player/PlayerMovieFallbackSupport.kt:80` | Inside a comparator key |
| `player/.../PlayerErrorClassifier.kt:82` | Once **per cause-chain element** per error |
| `player/.../PlayerTrackController.kt:226, :262`, `LiveTimeshiftManager.kt:919, :1414-1417` | Per track / per playlist poll |

### C2. Exception-driven control flow

`XmltvParser.parseDate` (`XmltvParser.kt:567-616`) probes formats with
`runCatching { ... }.getOrNull()`. Every miss **constructs and throws a
`DateTimeParseException`, capturing a stack trace**, even though a miss is the expected path. A feed
without an offset burns 5 exceptions per date; a fully unmatched one burns 11. `parseDate` is called
**twice per `<programme>`** (`:146/:147`, `:265/:266`, `:415/:416`), and
`EPG_MAX_PROGRAMMES = 2_000_000` — so a large guide can produce millions of stack-trace fills.
Exception construction costs 1–2 orders of magnitude more than a failed comparison.

### C3. Redundant decode / serialize round-trips

`OkHttpXtreamApiService.kt:780` + `:788` (and `:592` + `:600`) decode **every catalog item three
times**: `JsonParser.parseReader` builds a full Gson tree, `element.toString()` re-serializes that
tree into a fresh JSON String, then `json.decodeFromString(deserializer, element.toString())` parses
that String again with kotlinx-serialization. The cheap alternative is already used elsewhere in the
same repo (`json.decodeFromJsonElement(...)`, `LenientJsonSerializers.kt:257`).

### C4. Full-catalog recomputation on every emission

`ChannelRepositoryImpl.observeChannels` (`:330-353`) is a `combine` of **six** flows including
user preferences, and each emission runs: visibility filter → hidden-id filter →
`buildPresentedChannels` (which calls `ChannelNormalizer.classify` for **every** channel) → a
per-channel `copy` map → `applyNumbering`. That is ~5 full passes over the catalog with the most
expensive operation in the middle. A single preference toggle — parental level, grouping mode,
hidden ids — reclassifies every channel. `observeChannels` has **no LIMIT**.

### C5. Unconditional diagnostics work

Work performed for diagnostics that is not gated on diagnostics being visible or enabled:

- `Media3PlayerEngine.kt:397-402` writes a diagnostics file to flash **every second** during
  playback — 3 600 create/truncate/close cycles per hour, plus a `PlaybackLogSanitizer.sanitizeUrl`
  (URI parse + 2 regex passes) per tick. Not conflated, so launches queue under IO saturation.
- `RepositoryTimingReporter.measure` wraps the channel-presentation path (`RepositoryTimingReporter.kt:27`).
- `EpgResolutionEngine.kt:214-218` builds a 4-part interpolated log string eagerly for every
  unresolved channel; `Log.v` level does not prevent the `StringBuilder` work.
- `PlayerScreen.kt:213` **collects** diagnostics state unconditionally in the root of the ~1370-line
  player composable, though it renders only under `showDiagnostics` (`:1246`).
- `PlayerDataSourceFactoryProvider.kt:220-244` builds log strings with no `BuildConfig.DEBUG` guard.

### C6. Comparator selectors recomputed per comparison

`compareBy`/`thenBy`/`sortedBy` selectors run **per comparison**, not per element, so an O(n log n)
sort performs O(n log n) selector invocations. Where the selector allocates (lowercase, regex
compile, string concat) this multiplies allocation by n log n: `SearchRankingUtils.kt:16-27`,
`CategoryDisplayPreferences.kt:14-21`, `PlayerMovieFallbackSupport.kt:56-63`.

---

## 4. Findings

Impact tiers are judged **against the AFTSSS baseline**, not against a flagship.

### CRITICAL

#### A1. `ChannelNormalizer.containsStandalone` compiles a regex on every call
- **Location:** `domain/src/main/java/com/streamvault/domain/util/ChannelNormalizer.kt:288-291`
- **Evidence:** **Measured on device.** Thread dump captured at `Pattern.compile (Pattern.java:1345)`
  ← `Regex.<init>` ← `containsStandalone (ChannelNormalizer.kt:289)`, inside the
  `observeChannels` pipeline, during sustained 135–320 % CPU with ~1 GC/sec.
- **Mechanism:** `val regex = Regex("""(?<![a-z0-9])${Regex.escape(token)}(?![a-z0-9])""", IGNORE_CASE)`
  runs on every call, discarding the result. Called from `resolveCodecLabel` (`:226-228`),
  `resolveTransportLabel` (`:231-235`), `resolveSourceHint` (`:239`) and `resolveLanguageHint`
  (`:262`) — each inside a `firstOrNull` over a tag map (10 + 5 + 11 + 21 entries), twice per entry
  (name and URL). Worst case ≈66 `Pattern.compile` calls per channel.
- **Fix:** hoist per token, or replace with a manual boundary scan (no regex needed).

#### A2. `ChannelNormalizer` compiles ~20–27 further regexes per channel
- **Location:** `ChannelNormalizer.kt:217` (per-call inside a `forEach` over 22 `resolutionTags`),
  plus `:181`, `:202`, `:203`, `:283`, `:315`
- **Evidence:** `resolveDeclaredHeight` appears as an **actively executing frame** in the on-device
  samples; `:217` verified by reading.
- **Mechanism:** Same as A1. A name with no digits compiles ~15 patterns before matching `"hd"`; a
  name with no resolution token compiles all 22. On top of A1 this is roughly
  **300 000–400 000 extra `Pattern.compile` calls per 15 000-channel sync**.
- **Note:** A1 and A2 share a call site but are *different* mechanisms from A3 — fixing only A1
  leaves A2 and A3 burning CPU.

#### A3. `buildCanonicalName` runs ~57 sequential full-string regex passes per channel
- **Location:** `ChannelNormalizer.kt:190-209`, loop at `:195-197` over the 49 patterns built at `:101-109`
- **Evidence:** Captured on device as the top actively-executing frame
  (`Regex.replace (Regex.kt:180)` ← `buildCanonicalName (ChannelNormalizer.kt:196)`).
- **Mechanism:** `removableCanonicalPhrases.forEach { cleaned = cleaned.replace(regex, " ") }` —
  49 precompiled patterns (22 resolution + 10 codec + 5 transport + 11 source-hint + `"fps"`) — then
  `heightRegex`, `frameRateRegex`, `separatorRegex`, `collapseWhitespaceRegex`, `bracketRegex`,
  `leadingRegionRegex`. Each `replace` scans the whole remaining string and returns a new String,
  whether or not it matches. ≈57 scans + ≈57 intermediate Strings per channel; ~855 000 of each per
  15 000-channel sync. The patterns *are* correctly precompiled here — the cost is the scanning and
  allocation, so this survives an A1/A2 fix.

#### A4. Every catalog item is JSON-decoded three times
- **Location:** `data/src/main/java/com/streamvault/data/remote/xtream/OkHttpXtreamApiService.kt:780` + `:788`; same shape at `:592` + `:600`
- **Evidence:** Verified by reading; sits in the same Xtream sync pass as A1–A3, which the thread dump measured at 135–320 % CPU.
- **Mechanism:** Gson tree parse → `element.toString()` (new `StringWriter` + `JsonWriter` + full re-emit) → kotlinx `decodeFromString` reparses that String. Three traversals plus a transient String per item. At ~500 bytes/item × 15 000 items that is ~7 MB of throwaway strings per sync plus a full object tree and ~375 000 `JsonPrimitive` wrappers.
- **Fix (corrected 2026-09-11):** the originally proposed `decodeFromJsonElement` **does not compile** — `JsonParser.parseReader` yields a **Gson** `JsonElement`, not a kotlinx one. The fix must remove Gson from the per-item path entirely (kotlinx `decodeFromStream` / a `JsonDecoder` over a reader), which also changes lenient-parsing behaviour. See `docs/planFix.md` A4 for the corrected approach and the tests it needs.

#### A5. `XmltvParser.parseDate` throws up to 11 exceptions per call, twice per programme
- **Location:** `data/src/main/java/com/streamvault/data/parser/XmltvParser.kt:567-616`; call sites `:146-147`, `:265-266`, `:415-416`
- **Mechanism:** See **C2**.
- **Fix:** `DateTimeFormatter.parse(CharSequence, ParsePosition)` — returns an error index and throws nothing; plus hoist the `:601`/`:605` fallback objects.

#### A6. `observeChannels` reclassifies the entire catalog on every emission
- **Location:** `data/src/main/java/com/streamvault/data/repository/ChannelRepositoryImpl.kt:330-353`, `:450-469`
- **Evidence:** Measured — this pipeline is where **all 4** actively-executing samples were caught (see §2.1).
- **Mechanism:** See **C4**. `buildPresentedChannels` maps every entity through `toPresentedRawChannel` → `toVariant` → `ChannelNormalizer.classify` (the full A1+A2+A3 cost), then a second `map` allocates a `copy` per channel, then `applyNumbering` makes another pass. Triggered by any of six combined flows.
- **Fix:** classify once and cache per channel; move to `Dispatchers.Default` (the flow already uses `.flowOn(Dispatchers.Default)` at `:353`, but the classification itself is the cost).

#### A7. `PlayerScreen` recomposes at frame rate for the whole duration of a recording
- **Location:** `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt:952-984`
- **Mechanism:** `rememberInfiniteTransition` (`:953`) drives `animateFloat` (`:954-962`) whose value is read **in the composition phase** and again at `:975` as `Color(0xFFFF4D4F).copy(alpha = recordingAlpha)`. A composition-phase animated-State read invalidates the restart scope of the reading composable — which is the entire ~1370-line `PlayerScreen` body (`:113-1487`, 56 `collectAsStateWithLifecycle` calls, 36 `remember`/`LaunchedEffect` sites). `tween(750)` + `RepeatMode.Reverse` never stops while recording.
- **Fix:** move the alpha into `Modifier.graphicsLayer { alpha = ... }` (draw-phase read) or hoist the indicator into its own leaf composable.
- **Caveat:** the recomposition *rate* is certain from the code; the per-frame ms cost is **UNVERIFIED** on device (recording was not exercised).

#### A8. Timeshift invalidates its disk-usage cache on every chunk, forcing a full directory stat-walk every 2 seconds
- **Location:** `player/.../timeshift/LiveTimeshiftManager.kt:734-737` → `TimeshiftDiskManager.kt:73-77` → `:89-110` (walk) → `:97-107` (per-file `Os.stat` + `length()`); `LiveTimeshiftManager.kt:566-574`
- **Mechanism:** Each finalized chunk calls `recordFileMutation()` (sets `cachedUsageBytes = -1`), then immediately `checkDiskAndBudget()` → `isWithinBudget()` → `currentUsageBytesLocked()`, which sees the invalidated cache and re-walks the whole `cacheDir/timeshift` tree. Per file: 2 stat syscalls + ~5–7 objects. `PROGRESSIVE_CHUNK_MS = 2_000L` (`:1435`), default 30-min depth ⇒ up to **900 `chunk-*.ts` files**. The walk holds `accountingLock` (`TimeshiftDiskManager.kt:74, :84, :154`), serializing budget checks.
- **Caveat:** mechanism and file-count arithmetic are certain from the code; on-device rewind was not exercised at depth, so the wall-clock cost is **UNVERIFIED**.

#### A51. Catalog fingerprinting compiles 4–10 regexes, parses 1–4 URIs and builds 32 `Formatter`s **per catalog row**
- **Location:** `data/src/main/java/com/streamvault/data/sync/SyncCatalogStore.kt:930` (`Regex` per call), `:926` (`normalizeText`), `:923` (32× `String.format`), `:939` (`URI` per call), `:916` (`fingerprint`); callers `:591`, `:627`, `:663`, `:785`, `:817`
- **Mechanism:** `normalizeText` is `value.orEmpty().trim().replace(Regex("\\s+"), " ").lowercase()` — the `Regex` literal is built **inside** the function, so every call compiles a fresh `Pattern`. `fingerprint()` renders SHA-256 with `joinToString("") { "%02x".format(it) }`, i.e. **32 `java.util.Formatter` instances plus 32 boxed `Byte`s and 32 `String`s per digest**. `normalizeUrl()` constructs a `java.net.URI` per URL field.
- **Cost:** per item — `channelFingerprint` Xtream branch 4 compiles + 1 URI, generic branch 4 + 3, `movieFingerprint` 10 + 4, `seriesFingerprint` 8 + 3, plus 32 `Formatters`. A 50k-channel Xtream sync ≈ **200 000 `Pattern.compile` + ~150 000 URI parses + 1.6 M `String.format`**; a 100k-movie catalog ≈ 1 M compiles + 400k URIs + 3.2 M formats — all while the same worker writes to SQLite. `CatalogSizeLimits` allows 100k/200k/100k (`CatalogSizeLimits.kt:4-6`).
- **Fix:** hoist the regex; replace `"%02x".format` with a hex lookup table; carry the parsed URL forward instead of re-parsing.

#### A52. Stage-merge `UPDATE`s use 15–21 correlated subqueries per row and re-scan the whole catalog every 500 channels
- **Location:** `data/src/main/java/com/streamvault/data/local/dao/CatalogSyncDao.kt:203-320` (channels: 15 column subqueries + 1 in `WHERE EXISTS`), `:391-545` (movies: 20 + 1), `:636-774` (series: 18 + 1)
- **Mechanism:** Each statement is `UPDATE t SET col_a = (SELECT stage.col_a FROM stage WHERE session_id=? AND provider_id=? AND stream_id=t.stream_id), col_b = (SELECT … same predicate), …`. SQLite cannot share the stage lookup across scalar subqueries, so each changed row costs one index probe **per column** instead of one row read. Worse, `commitStagedLiveCatalogProgress` (`SyncCatalogStore.kt:208-214`) re-runs the statement with no watermark and a `WHERE` of only `provider_id`, so it re-scans every channel each time; the stage table is never cleared between passes.
- **Cost:** `LIVE_PROGRESS_COMMIT_CHANNEL_INTERVAL = 500` (`SyncManagerXtreamLiveStrategy.kt:28`) ⇒ for a 30k-channel provider ~60 executions × 30k rows = **~1.8 M row visits**, plus ~15 extra probes per changed row, inside a single write transaction. Effectively **O(catalog²/500)**.
- **Verification available today:** DEBUG builds already time statements via `SlowQueryLoggingOpenHelperFactory` (tag `RoomSlowQuery`, 100 ms threshold, `DatabaseModule.kt:24`) — count `UPDATE channels` entries during a sync. `ChannelBrowseQueryPlanTest.kt:63-74` is an existing `EXPLAIN QUERY PLAN` harness to extend.
- **Fix:** SQLite row-value `UPDATE t SET (a,b,…) = (SELECT stage.a, stage.b, …)` (SQLite 3.15+, fine on Android 9), plus a monotonic watermark so only newly staged rows merge.

### HIGH

#### A9. `ChannelRepositoryImpl` classifies per row, and grouped mode classifies the whole pool per emission
- **Location:** `ChannelRepositoryImpl.kt:781-806` (`classify` at `:784`), `buildGroupedChannels` `:471-483`, `observeChannels` `:95`, `:715-720`
- **Mechanism:** `observeChannels` has no LIMIT, so a 15 000-channel category re-runs the full A1–A3 cost on every emission on the collector's dispatcher. This is the read-path twin of A6.

#### A10. `createDateTimeFormat` builds a `DateFormat` per call, from composition
- **Location:** `app/src/main/java/com/streamvault/app/ui/time/AppTimeFormatters.kt:19-23`
- **Evidence:** **Measured.** Thread dump shows the full construction path —
  `DateFormat.getDateTimeInstance` → `SimpleDateFormat.<init>` → `NumberFormat.getIntegerInstance` →
  `DecimalFormat.<init>` → `DecimalFormatSymbols.getIcuDecimalFormatSymbols` — reached from
  `rememberSettingsScreenLabels (SettingsScreenState.kt:83)` inside `SettingsScreen` composition.
- **Mechanism:** `DateFormat.getDateTimeInstance(...)` / `SimpleDateFormat(...)` performs ICU symbol
  loading and `NumberFormat` initialisation on every call. `createTimeFormatter` (`:25-29`) builds a
  `DateTimeFormatter` per call with the same problem.

#### A11. Whole XMLTV refresh is retried as a unit, with no size awareness
- **Location:** `data/.../sync/SyncManager.kt:4305-4307`, `:4340-4342`, `:4384-4389`; policy `SyncManagerXtreamSupport.kt:114-140`
- **Mechanism:** `retryTransient` (`maxAttempts = 3`, fixed 700 ms / 1.4 s, no jitter) wraps the entire `refreshEpg` — download, gunzip, XML parse and staging insert. A reset at 90 % of a 50–200 MB feed restarts from byte 0. The health-scaled `retryDelayFor` (`XtreamAdaptiveSyncPolicy.kt:159-174`) is not used here.

#### A12. EPG refresh rewrites the whole `programs` table every cycle
- **Location:** `data/.../repository/EpgRepositoryImpl.kt:364-371`
- **Mechanism:** `deleteByProvider(providerId)` then `moveToProvider(staging → real)` = DELETE N + UPDATE N in one transaction, on a table with six indices including a UNIQUE one (`StreamVaultDatabase.kt:239-244`), so every row pays index delete+reinsert in all of them — repeated every 6 h TTL (`ContentCachePolicy.kt:6`) even for a byte-identical feed.

#### A13. Full EPG re-resolution runs even when the download was skipped
- **Location:** `data/.../epg/EpgResolutionEngine.kt:61-251`; invoked at `SyncManager.kt:4404` (and `:4391`, `:4401`, `:4419`, `:4439`, `:4443`, `:4469`, `:4508`, `:4511`)
- **Mechanism:** On every sync, all channels are loaded, every `ChannelEpgMappingEntity` is rebuilt and `replaceForProvider` (`Daos.kt:3725`) does a transactional DELETE-all + INSERT-all. When the XTREAM TTL gate at `SyncManager.kt:4293-4295` is false, control falls through and `:4404` still runs the whole pass.
- **Secondary, correctness:** `EpgResolutionEngine.kt:110-112` passes every `epgChannelId` in **one unchunked `IN (:channelIds)`** list, while every other EPG call site chunks at 500 (`EpgRepositoryImpl.kt:123`, `:214`; `EpgResolutionEngine.kt:275`). Android 9 ships SQLite 3.22 with `SQLITE_MAX_VARIABLE_NUMBER = 999`, so >999 populated ids should fail to prepare. **UNVERIFIED at runtime** — flagged as a latent crash, not only a perf issue.

#### A14. Live playback re-queries a 30-hour EPG window every 30 seconds and sorts it on the main thread
- **Location:** `app/.../ui/screens/player/PlayerEpgActions.kt:7` (30 s) and `:28-53`; `EpgRepositoryImpl.kt:432`, `:438-440`; `PlayerProgramTimelineSupport.kt:21-31`
- **Mechanism:** An infinite `viewModelScope` loop requests `now-24h .. now+6h` every 30 s. After the `withContext(IO)` returns, `shiftAll().sortedBy{}` runs on the **caller's dispatcher (Main)**. `buildProgramTimeline` then sorts the same list **three times** and calls `isArchivePlayable` per programme, allocating an `ArchivePlaybackCapability` each time (`ArchivePlayback.kt:28-72`) — also on Main. 120 iterations per hour of viewing, on top of an active decoder.

#### A15. Per-second diagnostics file write during playback
- **Location:** `player/.../Media3PlayerEngine.kt:397-402`, gate `:2260-2263`, `PlaybackSupportSnapshotStore.kt:17-21`
- **Mechanism:** See **C5**. 3 600 create/truncate/close cycles per hour into `filesDir/diagnostics/crash/latest-playback-support.txt` — a file observed on the device — sharing flash with the timeshift writer and Media3 `SimpleCache`.

#### A16. `PlayerScreen` recomposes once per second during all playback
- **Location:** `PlayerScreen.kt:213` (reader), `PlayerViewModel.kt:743-757` (feeder), `Media3PlayerEngine.kt:359-383` (1 Hz ticker)
- **Mechanism:** `lastVideoFrameAgoMs` is recomputed as `now - lastFrameAt` (`VideoStallDetector.kt:41-44`), so it differs every tick, the `StateFlow` always emits, and the ~1370-line root body re-executes each second for the entire session regardless of whether diagnostics are shown.

#### A17. EPG guide search loads the entire channel table and does three O(N) main-thread passes per keystroke
- **Location:** `app/.../ui/screens/epg/EpgViewModel.kt:2007-2013`, `:1949-1996`, `:1437-1453`; DAO `data/.../local/dao/Daos.kt:96-110`
- **Mechanism:** `getChannels(providerId).first()` hits `ChannelDao.getByProvider` which has **no LIMIT**. The continuation resumes on `Dispatchers.Main.immediate` (via `viewModelScope`), so `mapNotNull{}` → `toMap()`, `filter { contains(ignoreCase = true) }`, `toSet()` and a third `buildList` pass all run on the UI thread over every channel. Debounced only 150 ms.

#### A18. EPG grid composes every programme in the window, non-lazily
- **Location:** `app/.../ui/screens/epg/EpgGridComponents.kt:533-545`, markers `:514-523`, viewport `:170`
- **Mechanism:** `programs.forEach { ProgramItem(...) }` inside a plain `Row`/`Box` of full timeline width — no `LazyRow`/viewport culling. Window is `now-1h .. now+6h` (`EpgViewModel.kt:284-285`) but only 3 h is visible, so ~2.3× the needed cells are composed per row (~14–28 programmes + ~15 markers), for every visible row.

#### A19. EPG grid callbacks are keyed on the whole `EpgUiState`
- **Location:** `app/.../ui/screens/epg/EpgScreen.kt:455-486`; `EpgGridComponents.kt:118-135`, `:196-229`, `:333-354`
- **Mechanism:** The lambdas close over the entire `uiState`, so any field change — including ones the grid never displays (`isPreviewLoading`, `previewErrorMessage`, `isRefreshing`, `lastUpdatedAt`, `isGuideStale`) — recreates them and defeats skipping down the chain into every visible `EpgRow` and `ProgramItem`.

#### A20. The H6 network isolation does not actually cover Stalker or EPG
- **Location:** `app/.../di/NetworkModule.kt:82-86` (main client) vs `:138-142` (background client); `:99-104` (the comment claiming isolation)
- **Mechanism:** Every "dedicated" client is built with `OkHttpClient.newBuilder()`, which **shares the same `Dispatcher` and `ConnectionPool` by reference** — playback (`PlayerDataSourceFactoryProvider.kt:86-91`), Coil (`StreamVaultApp.kt:55-59`), EPG (`EpgRepositoryImpl.kt:85-88`), Xtream (`OkHttpXtreamApiService.kt:104-118`), Stalker (`OkHttpStalkerApiService.kt:1456-1472`). `@BackgroundSyncClient` is referenced **only** by `SyncManager` for M3U/Xtream (`SyncManager.kt:216`, `:239-248`); Stalker sync uses the main client (`NetworkModule.kt:148` → `SyncManager.kt:5579-5581`) and all EPG downloads use the main-derived client (`EpgRepositoryImpl.kt:64`, `:85-88`). EPG carries a 200 MB / 120 s budget.

#### A21. The playback admission gate is never consulted by the main sync path
- **Location:** gate `data/.../sync/PlaybackNetworkAdmissionGate.kt:64-77`; call sites only `StalkerIndexWorker.kt:50`, `BackgroundEpgSyncWorker.kt:56`
- **Mechanism:** `ProviderSyncWorker` (`:99-107`) declares no gate and syncs every provider; inside `SyncManager` the only playback check is Stalker-only (`:1971`, `:2321`). Xtream catalog and every EPG refresh run unthrottled during live playback on the same client and radio.

#### A53. External XMLTV import inserts 500-row batches with **no enclosing transaction**
- **Location:** `data/src/main/java/com/streamvault/data/repository/EpgSourceRepositoryImpl.kt:387-390`, `:397-402`; contrast the correct pattern at `EpgRepositoryImpl.kt:283-291`
- **Mechanism:** The streaming parse callback calls `epgProgrammeDao.insertAll(...)` directly every 500 rows (`PROGRAMME_BATCH_SIZE = 500`, `:83`); only the staging→live swap at `:405-411` is transactional. Each unwrapped DAO insert is its own implicit SQLite transaction — in WAL mode a commit, a WAL frame write and an **fsync** (Android WAL sync mode is FULL). With `EPG_MAX_PROGRAMMES = 2_000_000` that is up to **4 000 commit+fsync cycles** per source refresh (a realistic 200k-programme source: ~400). The `.toList()` at `:388`/`:401` also copies a 500-element list per flush.
- **Fix:** wrap both flushes in `transactionRunner.inTransaction { }` exactly as `EpgRepositoryImpl` already does.

#### A54. Channel count/category-count Flows re-run `GROUP BY COUNT(DISTINCT CAST(...))` over the whole table on every sync write
- **Location:** `data/src/main/java/com/streamvault/data/local/dao/Daos.kt:506`, `:509-519`, `:521-537`, `:539-556`, `:558-569`; consumers `ChannelRepositoryImpl.kt:355-372`, `:86-90`
- **Mechanism:** These are Room `Flow` queries, so Room re-executes each one on **every `channels` table invalidation**. The grouped variants compute `COUNT(DISTINCT CASE WHEN logical_group_id IS NOT NULL AND logical_group_id != '' THEN logical_group_id ELSE CAST(id AS TEXT) END) … GROUP BY category_id` — SQLite builds an ephemeral B-tree keyed on a per-row **derived text value**, so every channel row allocates a string inside the aggregate. Since the sync writes channels roughly every 500 accepted (A52), the aggregate re-runs dozens of times per sync, competing for the same single SQLite writer and WAL.
- **Fix:** maintain counts incrementally, or debounce/coalesce the aggregate flow.

#### A55. VOD duplicate resolution scans every movie/series of a provider, unindexed, materialising full rows
- **Location:** `data/src/main/java/com/streamvault/data/local/dao/Daos.kt:1569` (`tmdb_id`), `:1572` (`year`), `:1575` (`release_date LIKE`); series twins `:2574`, `:2577`; callers `MovieRepositoryImpl.kt:583-599`, `SeriesRepositoryImpl.kt:709-718`
- **Mechanism:** `SELECT * FROM movies WHERE provider_id = ? AND tmdb_id = ?` (plus the year and release_date-prefix variants, up to three queries per call) has only `provider_id` indexed, so SQLite walks every movie row of the provider and materialises whole `MovieEntity` rows — including the large `plot`/`cast`/`director` TEXT columns — for each match. On a 200k-movie provider that is up to 200k scattered rowid lookups and tens of MB of transient allocation for one detail-screen open.
- **Fix:** add `(provider_id, tmdb_id)` and `(provider_id, year)` indices; select identity columns only instead of `SELECT *`.

#### A56. Movie/series browse loads the entire source list and the entire playback history **twice** per page fetch
- **Location:** `SeriesRepositoryImpl.kt:1091-1092`, `:1131-1132` (and `:1029-1036` on search); `MovieRepositoryImpl.kt:1092-1093`, `:1119-1120`; unbounded history at `Daos.kt:3321`
- **Mechanism:** After building the page, when `duplicateHandlingMode != SHOW_ALL` the code re-runs `seriesBrowseSource(query).first()` **and** `playbackHistoryDao.getByProvider(...).first()` purely to compute `totalCount` via `.size`. That history query is `SELECT * FROM playback_history WHERE provider_id = ? ORDER BY last_watched_at DESC` with **no LIMIT**. So the whole filter/group/sort pipeline and the provider-wide history are each materialised twice — a constant-factor doubling on the most frequent user-facing query path.
- **Fix:** compute the filtered list once and reuse its `.size`.

#### A57. Every 5 seconds of VOD playback: a Room write transaction plus up to 40 ContentResolver writes
- **Location:** `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerLifecycleActions.kt:17-24` (`while(true)` + `delay(5000)`), `:34`, `:36`, `:37`; `app/src/main/java/com/streamvault/app/tv/WatchNextManager.kt:35-46`, `:83-91`, `:63-76`; `PlaybackHistoryRepositoryImpl.kt:185-211`
- **Mechanism:** Each 5-second tick opens a Room transaction (`insertOrUpdate` + an `UPDATE` on movies/episodes) and then **unconditionally** calls `WatchNextManager.refreshWatchNext()`, which has **no throttle**: it re-queries the active provider and recent history, queries `TvContract.WatchNextPrograms` with a **null selection** (unbounded read of every row the app ever published), then re-issues an insert or update for each of up to 40 entries — up to 40 binder IPC calls into the TV provider every 5 s even when nothing changed. (`refreshRecommendations` *is* throttled to 15 min and is not a problem.)
- **Cost:** ~8 cross-process ContentResolver writes per second plus a WAL commit+fsync, on the same 4 weak cores decoding video. 12 times per minute, continuous during any VOD/series playback.
- **Fix:** gate `refreshWatchNext` on an actual state change; bound the existing-entry query.

### MEDIUM

- **A22 — `EpgResolutionEngine` eager log strings.** `EpgResolutionEngine.kt:214-218` builds a 4-part interpolated string per unresolved channel plus two nested `enabledAssignments.any{}` scans; `Log.v` level does not prevent the work.
- **A23 — `getProgramsForChannelsSnapshot` maps and groups on the caller's dispatcher.** `EpgRepositoryImpl.kt:156-160` has no `withContext`, so `entities.map { toDomain().shifted() }.groupBy{}` over ~840–1700 rows runs on Main (`EpgViewModel.kt:1717`). Contrast `EpgResolutionEngine.kt:267`, which wraps correctly.
- **A24 — `buildGuideDisplaySnapshot` is O(channels × programmes) with a per-programme allocation, on Main.** `EpgViewModel.kt:1915-1930`; `ARCHIVE_READY` evaluates `isArchivePlayable` per programme, allocating an `ArchivePlaybackCapability` each time. Re-runs on every `combine` emission at `:1439-1445`, including UI-only toggles like `selectedDensity`.
- **A25 — No ViewModel in `app/ui` ever switches dispatcher.** Zero `flowOn`/`withContext` in `app/.../ui/screens/**/*ViewModel*.kt`, so `combine` transforms building UI state run on `Dispatchers.Main.immediate`. `CategoryDisplayPreferences.kt:14-21` sorts with a `lowercase()` **per comparison**; `MovieRepositoryImpl.kt:357-368` has no `flowOn` (contrast `ChannelRepositoryImpl.kt:353`).
- **A26 — `channelFingerprint` per channel: ~5 `Pattern.compile` + a `URI` + 32 `String.format` calls.** `SyncCatalogStore.kt:821-853`, `:916-932`, `:934-959`. The `"%02x".format` hex emission allocates a `Formatter` + `StringBuilder` and re-parses the format string 32× per channel (~480 000 throwaway Strings per sync).
- **A27 — The same stream URL is parsed twice per channel.** `EntityMappers.kt:196` and `SyncCatalogStore.kt:822` both call `parseInternalStreamUrl`, so ~45 000 `URI` objects are built per 15k-channel sync where half would do.
  **Correction (2026-09-11): the reflective-codec half of this finding is WRONG for the target device.** `XtreamUrlCodec` probes for `URLEncoder/URLDecoder.encode/decode(String, Charset)` reflectively because those overloads only exist from **API 33**, while the app is `minSdk = 25` (`app/build.gradle.kts:54` → `compileSdk = 36`, `:58` → `minSdk = 25`). On the AFTSSS (API 28) the probe returns `null`, so `?.let { }` short-circuits and the plain `URLEncoder.encode(value, "UTF-8")` fallback runs — **no reflective invoke happens per call at all**. The reflection cost is real only on API 33+, which is *not* the measured baseline.
  **Do not "fix" this by calling the `Charset` overload directly** — it compiles against SDK 36 but throws `NoSuchMethodError` on API 25–32, i.e. on the target device. Any change here must keep the runtime probe. Tier should be treated as **Low** on the AFTSSS baseline.
- **A28 — `SearchRankingUtils` recomputes selectors per comparison and compiles 2 regexes per call.** `SearchRankingUtils.kt:10-11`, `:16-27`; ~6 000 redundant `lowercase`+`trim` derivations for 200 results.
- **A29 — `EpgNameNormalizer` compiles a regex per call.** `EpgNameNormalizer.kt:30`; the neighbouring `nonAlphanumericRegex` *is* hoisted at `:16`. Once per XMLTV `<channel>` (10k–100k per feed) and once per channel in resolution (`EpgResolutionEngine.kt:169`).
- **A30 — M3U `stableId` compiles a regex twice per entry.** `SyncManagerM3uImporter.kt:379` via `:345-346`; plus a `URI` parse and a SHA-256 per entry (~30 000 compiles for a 15 000-entry playlist).
- **A31 — `CatalogSyncDao.updateChangedChannelsFromStage` repeats the same lookup 18×.** `CatalogSyncDao.kt:201-320` — 17 correlated scalar subqueries plus an `EXISTS` probe. Indexes *do* cover it (`Entities.kt:470-481`, `:93`), so this is 18 index probes per changed row, not a scan.
- **A32 — EPG override search is un-debounced and does a `%LIKE%` full scan per keystroke.** `EpgViewModel.kt:868-878` → `EpgSourceRepositoryImpl.kt:493-516` → `Daos.kt:3626-3635`: three `LOWER(col) LIKE LOWER('%…%')` predicates, unindexable by construction, per assigned source, per keystroke.
- **A33 — `LiveAudioTapAudioSink` allocates per audio buffer even with no tap.** `LiveAudioTapAudioSink.kt:32-35` takes `asReadOnlyBuffer()` before the `tap ?: return` guard at `:45`; installed unconditionally at `Media3PlayerEngine.kt:1295-1298`. ~50 allocations/sec on the playback thread for nothing.
- **A34 — MEMORY-backend rewind retains minutes of transport stream in byte arrays.** `LiveTimeshiftManager.kt:711`, `:722-733`, `:741-749`. A 5-minute window at 4–8 Mbps is 150–300 MB against a **192 MB** heap class. Reached when `cacheDir.usableSpace < 200 MB` (`:361-369`) — precisely the Fire TV Stick case. Peak **UNVERIFIED** without a live rewind session.
- **A35 — DASH timeshift enumerates and serially downloads the whole retention window on first poll.** `LiveTimeshiftManager.kt:1376-1390`, `:1124-1141`. ~900 segments at default depth, most pruned immediately by `DashWindow.prune()`; every poll also rebuilds every URI (4 `String.replace` + a `java.net.URI` + `resolve()` per segment). **UNVERIFIED** against a real DASH panel.
- **A36 — Jellyfin image interceptor decrypts via Keystore on every image request.** `JellyfinImageAuthInterceptor.kt:36` calls `credentialCrypto.decryptIfNeeded` per request (no token cache); `CredentialCrypto.kt:55-84` does `KeyStore.load(null)` + `getKey` + `Cipher.init/doFinal` — a hardware-backed op — for a value that cannot change. Plus a `toHttpUrlOrNull()` per candidate provider per request.
- **A37 — Two `Cache` instances over the same directory.** `NetworkModule.kt:69-74` and `:125-130` both use `File(cacheDir, "streamvault_http_cache")`, so two `DiskLruCache` writers maintain one journal; a corrupted journal triggers `rebuildJournal()`, a full-directory scan. Little is bought: OkHttp never caches POST and Stalker posts every call.
- **A38 — Blocking `execute()` sites bypass dispatcher caps, and the shared client sets no `callTimeout`.** `NetworkModule.kt:75-77` sets only connect/read/write. `maxRequests`/`maxRequestsPerHost` govern `enqueue()` only; the synchronous sites (`OkHttpStalkerApiService.kt:976`, `:1083`, `:1222`; `JellyfinProvider.kt:308`; `EmbyProvider.kt:296`; `StremioProvider.kt:143`; `RecordingCaptureEngine.kt:80`; `DownloadManagerImpl.kt:289`; `InternetSpeedTestRunner.kt:114`) are bounded only by `Dispatchers.IO`, which Coil's `limitedParallelism(6)` view also draws from. Only the derived Xtream clients set `callTimeout`.
- **A39 — `runCatching` around suspend repository calls swallows `CancellationException`.** `EpgViewModel.kt:1704-1706`, `:1715-1718`. Inside a `collectLatest` (`:1157-1189`) whose purpose is to cancel the previous load, cancellation becomes "no data": the superseded load continues and publishes an **empty** `programsByChannel` with `baseGuideStale = true` before the new load lands. Wasted work plus a visible stale flash on rapid D-pad navigation.
- **A40 — The 30-second guide clock invalidates every visible row and cell, via three independent tickers.** `EpgControlComponents.kt:83-105`; readers `EpgGridComponents.kt:356` (per row) and `:674` (per cell); providers at `EpgScreen.kt:411`, `:454`, `:647`, plus `PlayerTransparentGuideOverlay.kt:82`. The overlay reads `currentGuideNow()` at the **root** of its content lambda (`:82-83`), invalidating the entire overlay including the embedded grid — contradicting the file's own design intent, since `ProgramItem`/`ProgramItemCell` were written specifically to avoid this.
- **Prior-remediation note (verified by reading, 2026-09-11):** a previous pass claimed to have "isolated current-time indicator updates to prevent grid recomposition". It is only **partial**: `EpgGridComponents.kt:357-359` does wrap the derived *program* in `derivedStateOf`, but the raw `val now = currentGuideNow()` read at `:356` (per row) and `:674` (per cell) remains, so the invalidation still happens. Fixing those two reads is the remaining work.
- **A41 — `EpgViewModel.requestMoreChannels` copies growing collections on Main per page.** `EpgViewModel.kt:577-579` (`drop(...).take(60)` copies the whole remaining list), `:590`, `:594`, `:613`, `:618` (`Map.plus` copies every accumulated entry). O(N²) aggregate over a scroll.
- **A42 — `ChannelLogoBadge` recomputes initials in composition.** `ChannelLogo.kt:52` calls `channelInitials` un-remembered; `:75-91` does `trim` + `split` + `filter` + `uppercase` — ~5 allocations per badge per recomposition, and a badge appears in every EPG row, list row and card. (The regex itself is correctly hoisted at `:73`.)
- **A43 — `StalkerProvider.resolveCategory` linearly scans the category list per item.** `StalkerProvider.kt:1479-1483`, `:1333`. No id→index map: O(items × categories), ~7.5 M predicate evaluations for 500 categories × 15 000 items.
- **A44 — `FallbackCategoryCollector.record` allocates a throwaway `CategoryEntity` per channel.** `SyncManagerSupport.kt:119-136` builds the candidate at `:119` *before* the cache lookup at `:127`, then allocates a second via `copy(...)` at `:131`. ~30 000 avoidable allocations per sync; the fix is reordering three lines.
- **A45 — Network graph built on the Application main thread at cold start.** `StreamVaultApp.kt:43-53` field-injects `okHttpClient` and `appCacheQuota`, so Hilt constructs both in `Application.onCreate` — **before** the `startDeferredStartup()` checkpoint at `:72-74` that exists to keep this off the cold-start path. Two `Cache` journal reads plus a `StorageStatsManager.getFreeBytes` binder call; the rebuild branch scales with a 256 MB budget.
- **Prior-remediation note (verified by reading, 2026-09-11):** a previous "Lazy Cold-Start Dependency Graph" pass converted several heavy managers to `Lazy`/`Provider`, but missed these two — `StreamVaultApp.kt:46` and `:52` are still direct `@Inject lateinit var`, while `imageOkHttpClient` immediately below (`:54`) correctly uses `by lazy`. The lazy pattern was applied inconsistently.
- **A46 — `PlayerMovieFallbackSupport` compiles a regex inside a comparator key.** `PlayerMovieFallbackSupport.kt:80`, used by `sortedWith(compareByDescending { movieCodecFallbackPriority(it.name) })` at `:56-63` → O(n log n) compiles per call. (A hoisted regex already exists at `:10`.)

- **A58 — Full-catalog channel staging materialises two whole entity lists (movies/series use a bounded `Sequence`).** `SyncCatalogStore.kt:75-99`, `:253-268`, `:565-594`, `:961-982`; inputs `SyncManager.kt:5333`, `:5363`, `:5388-5391`. Unlike `stageMovieSequence`/`stageSeriesSequence` (`:668-700`, `:710-744`), the live path holds the caller's `List<Channel>` **and** `buildChannelStages`' `List<ChannelImportStageEntity>` simultaneously, each entity string-heavy with a 64-char fingerprint. At 100k channels that is tens of MB of transient heap against a ~128 MB heap class (no `android:largeHeap`) while Room holds CursorWindows. `mergeHiddenChannelsIntoStaging` additionally re-fingerprints channels just staged from the same source. `SyncCatalogStoreMemoryTest.kt:60` is an existing template to extend.
- **A59 — Unchunked `IN (:ids)` can exceed the SQLite bind-variable ceiling on Android 9.** `Daos.kt:469` (`WHERE c.id IN (:ids)`), reached from `ChannelRepositoryImpl.kt:280-282`, with user-proportional id lists from `EpgViewModel.kt:1678` and `PlayerPlaylistActions.kt:226`, `:235`. Room expands `IN (:ids)` to one bind parameter per element and does not chunk; Android 9 ships **SQLite 3.22 with `SQLITE_MAX_VARIABLE_NUMBER = 999`** (32766 only from SQLite 3.32 / Android 12), so a list above 999 raises *too many SQL variables* rather than returning rows. This independently corroborates the secondary note in **A13**. The EPG paths already chunk at 500 (`EpgRepositoryImpl.kt:123`, `:151`, `:214`, `:244`; `EpgResolutionEngine.kt:275`, `:285`, `:303`, `:337`) — the channel/movie/series `getByIds` paths do not. Reachability above 999 ids is **UNVERIFIED**; a hard failure, not a slowdown, and device-specific.
### LOW

- **A47 — `DashWindow` hands out full list copies.** `LiveTimeshiftManager.kt:1039` (`media.toList()`) called at `:1131` **inside a `while` condition** just to read `.size`; `:1043`, `:1159`.
- **A48 — Per-call `setOf(...)` and unconditional log arguments on playback paths.** `Media3PlayerEngine.kt:2080-2085` (twice per second from the tick), `:1043`, `:1122`; `PlayerStatsCollector.kt:153-156`; `PlayerDataSourceFactoryProvider.kt:220-244` (7 header lookups + sanitize + concat per matching request, no `BuildConfig.DEBUG` guard). The interceptor also duplicates `withRequestProfile` (`RequestIdentity.kt:31-43`) applied at `OkHttpXtreamApiService.kt:362`.
- **A49 — Live-dot blink recomposes the whole timeshift scrubber every 700 ms.** `PlayerControlsChrome.kt:1723-1729` toggles state read in the same composable that computes the entire scrubber (`:1710-1721`). Only a 1 dp dot changes.
- **A50 — Per-call regex in `PlayerErrorClassifier` / `PlayerTrackController` / timeshift playlist parsers.** `PlayerErrorClassifier.kt:82` (once per cause-chain element, ×~5 call sites), `PlayerTrackController.kt:226`, `:262`, `LiveTimeshiftManager.kt:919`, `:1414-1417` (four regexes per poll).

---

## 5. Prior remediation status

The audit documents that this one replaces were deleted, but their recorded outcomes still matter for
sequencing. From the removed `performance-audit-results.md` (2026-09-07), the following were
recorded as **COMPLETED** and should **not** be redone:

- **Bugs B1–B13**, including the timeshift pair: *B1* (DASH timeshift isolates the init segment so
  rolling-window pruning works) and *B2* (timeshift cancellation ignores `CancellationException`
  and validates session identity). **B1/B2 are fixed** — any guidance to "start with timeshift
  safety" is obsolete.
- *Task 4* mutation-safe timeshift disk quota (synchronized physical file accounting in
  `TimeshiftDiskManager`). **Note:** finding **A8** is the *cost* of that fix, not a re-report of it —
  the accounting is correct, but it is invalidated on every chunk and re-walks the directory.
- *M4* shared Media3 `SimpleCache`/`CacheDataSource` between engine and timeshift.
- *M5* lazy cold-start dependency graph — **incomplete**, see A45.
- *Task 10* live channel progress clock (per-card ticker churn removed).
- *Task 11* EPG Compose invalidation & geometry caching — **incomplete**, see A40.

Anything from the old list **not** re-derived here by measurement or reading should be treated as
unknown rather than fixed; the old documents are recoverable from git history (`128b6249`) if a
specific item needs checking.

---

## 6. Post-PR-1 measurement notes (2026-09-11)

Recorded after implementing the Phase-1 / PR-1 object-construction fixes (A1, A2, A3, A10, A28, A29,
A30, A46, A50, A51 and the parse-time half of A5).

**Idle CPU after PR 1 — meets the targets, but not yet a controlled A/B.**
The PR-1 build idles at **0 % of one core with 0 GC events in 20 s**, against the targets in
`planFix.md` §2.3 (< 20 % CPU, < 5 GC / 30 s) and the original baseline of 135–320 % with ~1 GC/s.
That reading was taken after a **180 s settle**, and no matched post-settle baseline was captured on
the pre-change build — so it is *consistent with* PR 1 but **not proven to be caused by it**. A
controlled A/B (same settle, same foreground state, both builds) is required before claiming it.

**Cold start is ~17 s — pre-existing, and worse than anything this audit originally found.**
A controlled experiment (changes stashed, rebuilt, reinstalled, measured identically) showed the cost
is the same with and without PR 1:

| Build | Time to first frame |
|---|---|
| Pre-change (stashed) | +18s849ms · +17s833ms · +17s328ms |
| With PR 1 | +17s274ms · +17s431ms |

The single +3s082ms reading in §2 came from a different device state and is **not reproducible**;
treat ~17 s as current truth. Thread dumps during the slow window place the app inside
`NetworkModule.provideOkHttpClient` and Hilt graph construction — making **A37** (two `Cache`
instances over one directory) and **A45** (network graph built on the Application main thread) the
prime suspects. Clearing the 22.9 MB HTTP cache did **not** help, ruling out a journal rebuild and
pointing at construction cost and/or a startup-triggered provider sync blocking the first frame.
**This needs its own investigation and is not yet a numbered finding.**

**PR 1 verification status:** all modules compile; `:domain:test`, `:data:testDebugUnitTest` and
`:player:testDebugUnitTest` pass; the 201-case `ChannelNormalizerGoldenTest` passes byte-identical,
proving the normaliser refactor preserved output exactly.

**Pre-existing blocker:** `:app:compileDebugUnitTestKotlin` fails at `StartupCoordinatorTest.kt:50`
(`No parameter with name 'ioDispatcher'`). `StartupCoordinator` no longer takes it
(`StartupCoordinator.kt:45` uses `Dispatchers.IO` directly), so **no `:app` unit test can compile or
run**. This predates this work and blocks verification of every `app/`-module finding.

---

## 7. Verified clean — checked, no finding

Recorded so a future audit does not repeat the work.

- **No main-thread blocking in `data/` ingest.** `rg 'runBlocking|GlobalScope|Dispatchers\.Main|allowMainThreadQueries|blockingFirst|blockingGet'` over `data/.../{sync,remote,parser,repository,local}` returns **zero** matches; every ingest entry point is wrapped in `withContext(Dispatchers.IO)` (`SyncManager.kt:682`, `OkHttpXtreamApiService.kt:231/354/442`, `EpgSourceRepositoryImpl.kt:223`).
- **No `runBlocking` or `GlobalScope` anywhere under `app/.../ui`** either.
- **Xtream retry/backoff is adaptive and bounded** (`XtreamAdaptiveSyncPolicy.kt:44-101`, `:206-223`; `SyncManagerXtreamSupport.kt:53-68`) — not a retry storm. (EPG retry is the exception: A11.)
- **Staging tables are correctly indexed** for every correlated subquery in `CatalogSyncDao` (`Entities.kt:470-481`, `:90-98`); the SQL is set-based, not N+1; batches are 500.
- **`AdultContentClassifier` is not a regex-per-call offender** — patterns precompiled (`:10-11`), LRU cache of 4096 (`:70-76`).
- **`M3uParser` attribute parsing is hand-rolled char scanning** with no per-line regex (`:317-400`).
- **`XmltvParser.parseStreaming*` genuinely streams**, and `MaxBytesInputStream`/`EpgInputLimiter` bound the decompressed size.
- **`replaceForProvider` and `swapPriorities` are `@Transaction`-wrapped**; the DB runs in WAL mode (`DatabaseModule.kt:34`).
- **EPG body handling streams with hard byte ceilings** (`EpgRepositoryImpl.kt:323-354`) — no whole-file materialization.
- **`epg_programmes` indices adequately cover `EpgProgrammeDao.getForChannels`** (`Daos.kt:3673`).
- **`XtreamUrlFactory.sanitizeLogMessage` uses class-level precompiled regexes** (`:61-71`, `:327-349`).
- **Stalker auth is session-cached** (`StalkerProvider.kt:770-810`); Stalker sync concurrency is semaphore-capped (`SyncManager.kt:363-372`).
- **Coil image models are memoized** (`AsyncImageModels.kt:10-22`).
- **FavoritesScreen derived state is properly remembered** (`:152-253`); **SearchScreen debounces 300 ms with `flatMapLatest`** (`:151-157`); **Home/Epg channel paging uses LIMIT/OFFSET** (200/300, `MAX_CHANNELS = 60`); **MultiView has a device-tier slot policy** (`MultiViewViewModel.kt:700-735`); **ProviderSetupScreen file import runs on `Dispatchers.IO`** (`:193-233`).
- **Dead code found while reviewing** (not perf, but worth removing): `ProgramDao.getForCategory` has no production caller; `EpgRepositoryImpl.getProgramsForChannels`/`getNowPlayingForChannels` are unlimited Room Flows with **no production subscribers** — if a screen ever subscribes, each becomes a High finding; `buildLiveTsFallbackUrl`'s per-call regex is unreachable because its guard returns `false` unconditionally (`LiveTsFallbackUrl.kt:23-26`).
- **Not reported as regressions:** `PolicyAwareLoadControl.PlayerIdFilteringAllocator` (`:298-344`) mirrors upstream `DefaultLoadControl`; `PlayerDataSourceReadStats` is off by default.
