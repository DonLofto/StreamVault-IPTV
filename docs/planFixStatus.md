# planFix remediation status

Tracks implementation of the 59 findings in `docs/performance-audit.md`.
Plan: `docs/planFix.md`. Baseline commit: `740bd55f`.

**51 done · 5 partial · 1 superseded · 2 open.** Commits marked DONE are on `master`; the working
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
| A13 | `d0531dfc` | EPG resolution skipped when guide data did not change and mappings exist |
| A57 | `5f971bbf` | Watch Next refresh throttled to once per minute of playback |
| A55 | `8f78a388` | VOD duplicate-resolution indices added (migration 64 -> 65) |
| A8 | `cec7e087` | Progressive-chunk writes no longer force a directory walk every 2 s |
| A14 | `285b330f` | Playback EPG shift/sort moved off Main; redundant third sort removed |
| A19 | `7d874cd2` | EPG grid callbacks capture stable values, not the whole uiState |
| A4 | `76872471` + see below | Xtream decode paths stream with kotlinx; Gson removed entirely |
| A6 / A9 | `fc72267c` | `classify` memoised; catalog reclassification made an O(1) lookup. **Device-verified: 4/16 → 1/16 samples with app code actively executing** (see below). |
| A38 | `e8479530` | All 18 blocking OkHttp `execute()` sites now go through `awaitResponse()` (a local copy in `:player`, which cannot see `:data`); background-sync client bounded by `callTimeout` |
| A27 | `f35468c6` | Xtream URL recognised once: the mapper carries the internal-LIVE flag and container extension across on the entity as transient body fields, so the staging fingerprint no longer re-parses the URL. Guard test pins carried == re-parsed. |
| A25 | `68adb5a2`, `d3ece615`, `99cf4d26`, `47b16bb3` | `app/ui` category filter/sort moved off Main at all five audit sites (Movies, Series x2, Home, Epg) via the `@DefaultDispatcher` binding. The audit's EpgViewModel:1101 pointer is stale line drift; the real site is :1125. |
| A12 | `5d3ed203` | Unchanged-feed skip (migration 66 -> 67): ETag/Last-Modified conditional request, plus a SHA-256 of the decompressed payload; a match with a populated guide discards the staged rows instead of rebuilding the table |
| A52 | `1c95d2f1` | Monotonic `staged_seq` staging watermark (migration 65 -> 66): progress commits merge only the rows staged since the previous commit instead of re-scanning the whole provider catalog every 500 channels. |

## Partial

| ID | State |
|---|---|
| **A5** | Parse-time half DONE (`3b8cb2c5`: fallback `Regex` and `DateTimeFormatter` hoisted). **The exception-driven format probing itself is still open** — `parseDate` still constructs and throws up to 11 `DateTimeParseException`s per date, twice per programme. **ATTEMPTED AND REVERTED 2026-09-11 — read this before retrying.** Swapping the three helpers to `DateTimeFormatter.parse(CharSequence, ParsePosition)` with a `position.index == text.length` full-consumption check **changed behaviour**: 5 `XmltvParserTest` cases failed, all of them offset-less timestamps (`20250101140000` with pattern `yyyyMMddHHmmss`) that previously parsed and now returned null, plus a `DateTimeParseException` escaping for genuinely malformed input (so the ParsePosition overload can still throw). The naive swap is **not** behaviour-preserving. Retry only by first writing failing tests that pin the offset-less case, then establishing empirically what the ParsePosition overload does to `position.index` and to field resolution for these patterns. The single-`DateTimeFormatterBuilder`-with-`optionalOffset()` route may be the better shape. |
| **A58** | Part 1 done (`1fb250fc`): staged channel rows are built as a `Sequence` and written in 500-row chunks, so the second whole-provider list is gone; the `List`-returning builder stays for the two fresh-session callers. Pinned by a 100k-channel test asserting chunk size, total count and ordinal contiguity. **Open:** the larger half - streaming the Xtream/M3U ingest chain itself, which is upstream of the staging boundary - is untouched. **Note:** this is explicitly *not* the fix for the live-TV freeze; round 24 measured that at a 22 MB Java heap against 89 MB of graphics and a fully consumed device swap. |
| **A20** | Implemented (`7d2eeb27`): Stalker gets a dedicated `@StalkerClient` and the EPG client is genuinely isolated, both via `newIsolatedClient` (a plain `newBuilder()` shares Dispatcher and ConnectionPool by reference). Unit tests pin both the defect and the fix, plus configuration inheritance. **Open:** the card rates this high risk because it changes network admission, so it needs the full live-TV protocol - blocked on the same memory-pressure freeze as A34. |
| **A18** | Implemented (`737f5e1d`): guide rows compose only the programmes intersecting the visible time range plus a 30 minute overscan, read through `derivedStateOf`, so a per-pixel scroll does not recompose the row. Item layout, marker layer and focus callbacks are untouched. Range arithmetic extracted and unit-tested (4 cases). **Open:** the composition-count Compose test the card asks for, and an explicit D-pad traversal check on a TV - the card flags grid focus as delicate (prior audit B7). **The card's LazyRow prescription does not fit this component** - see the A18 re-analysis above. |
| **A34** | Implemented (`fd6b1859`): the MEMORY backend now evicts on a byte ceiling derived from the heap class (quarter of the heap, clamped to 8-48 MB) in addition to the wall-clock depth, for both progressive chunks and HLS segments. **Open:** the plan's validation is `dumpsys meminfo` across a 5-minute live session, and this is a playback/timeshift change, so it still needs the full AGENTS.md live-TV protocol (61 screenshots, 2 channels, media session `PLAYING`, `error=null`). Unit-tested only so far. |
| **A25** | Moved to Done - see the Done table. |
| **A38** | Moved to Done - see the Done table. |
| **A38 site count** | 18 OkHttp sites found repo-wide; the audit's list of 9 missed `PlayerViewModel`, `GitHubReleaseChecker`, `StreamVaultPluginManager` and `LiveTranslationClient` entirely. All are converted as of `e8479530`. The 19th grep hit, `SlowQueryLoggingOpenHelperFactory.delegate.execute()`, is a SQLite API and correctly excluded. |
| **A17** | Moved to Done (`84833e08`, `a6c12378`). Both halves: the search passes run in `withContext(guideWorkDispatcher)`, and the two category-visibility filters run in SQL via `ChannelRepository.getGuideSearchScopeChannels` (which reuses `observeChannels`, so parental and hidden-channel visibility are unchanged). Covered by `ChannelGuideScopeDaoTest` against a real in-memory Room database for all three filter shapes. **Two premises in the plan card were wrong** - see the A17 analysis above: the metadata predicate cannot be pushed, and the base snapshot cannot stand in for the load because `allChannels` is capped at `MAX_CHANNELS` (60). |
| **A27** | Moved to Done (see the Done table). The reflective-codec half was **withdrawn as wrong** — see below. |

## A18 re-analysis (round 25) - the plan's prescribed fix does not fit the component

The card says "replace the inner `Row` with a `LazyRow` (add `key` per programme)". That cannot be done
as written, and the reason is structural rather than cosmetic:

- The grid is **one shared horizontally scrolled strip**, not one scroller per row.
  `EpgGridComponents.kt:496-500` is a single `Row` of `totalTimelineWidth` with
  `horizontalScroll(scrollState)`, and the same `scrollState` is passed into every channel row so all
  rows stay aligned. A `LazyRow` owns its scroll position through a `LazyListState`; it cannot be
  driven from an externally shared `ScrollState`. Making the rows lazy means either giving up the
  shared scroll or re-implementing scroll syncing across N lazy lists - a large, focus-sensitive
  change on the one screen the previous audit (B7) already flagged for fragile D-pad traversal.
- `ProgramItem` is not a list cell. It positions itself inside a full-width `Box` using
  `padding(start = ...)` computed from the programme's start time (`:535-546`), so a lazy list has no
  item widths to measure.

**The viable route is the card's option 2, windowing**, and it is a much smaller change:

1. Derive the visible time range from the already-shared `scrollState` plus `timelineViewportWidth`
   and `totalTimelineWidth`, wrapped in `remember { derivedStateOf { ... } }` - the same pattern A40
   used in this file to keep a per-pixel scroll read out of composition.
2. Filter `programs` to those intersecting that range plus an overscan margin, before the
   `programs.forEach`. Everything else - the `Box`, the padding-based positioning, the marker layer,
   the focus callbacks - stays byte-identical, which is what keeps the D-pad behaviour safe.

That drops per-row composition from ~2.3x the visible cells to ~1x (the window is 7 h, only 3 h is
visible), which is the audit's actual complaint. The Compose test the card asks for - `ProgramItem`
compositions bounded by visible cells rather than window size - applies unchanged to this route.

Nothing here is implemented yet.

## Live-TV validation attempts (round 23) - FAILED, live playback freezes

First successful run of the AGENTS.md protocol on the AFTSSS after the round-22 launch-command fix.
The outcome is a **failure of the current build**, not a pass, and it is recorded here rather than
quietly retried.

Setup: debug build installed from HEAD (`84833e08` + docs), app launched via
`com.streamvault.app.debug/com.streamvault.app.MainActivity`, library sync completed, channel started
from `Live TV -> 24/7 CineMania` (full-screen playback of "CineMania Gold 4K", "Playing now: Do Qaidi
(1989)").

| Evidence | Result |
|---|---|
| Media session before capture | `state=3` (PLAYING), `error=null` |
| Screenshots | 61 at 2 s, as the protocol requires |
| Unique frame hashes | **14** |
| Frames that differ from the previous one | indices **00-13 only**; 14-60 are byte-identical |
| Media session after capture | `state=1` (STOPPED), `error=null` |

So playback advanced for roughly the first **26-28 seconds** and then froze for the remaining
~94 seconds of the window, with no player error reported on the media session. A frame captured at
index 30 is a genuine video frame of the movie, i.e. the surface was showing a stale picture rather
than a blank one.

Caveats that a follow-up must close before this is called a product regression:
- `adb exec-out screencap` does not always capture hardware-composited video planes faithfully. The
  first 14 frames *did* advance, which argues against a pure capture artefact, but a second run
  against a different channel is needed to confirm.
- A background catalog sync had just finished (15598 channels indexed); contention is possible.
- The player's own log lines were absent from `logcat -d` by the time it was read - the 2-minute
  capture plus system logging had rotated the buffer. Capture logs *during* the run next time.

Two further facts observed several minutes after the capture, which sharpen the diagnosis:
- The screen was **still showing the same frozen frame** (identical byte size to the index-30 capture),
  so this is not a transient stall that recovered.
- `XtreamIndexWorker` was logging `Worker result RETRY` in a loop throughout, i.e. a background index
  worker was failing and rescheduling while playback died. That is the first thread to pull.

**This does not invalidate A34's unit tests, but A34 must stay Partial: its live-TV validation did
not pass, and nothing about the current build should be marked validated on the strength of this run.**

### Round 26: what is actually holding the memory

`dumpsys meminfo` for the whole device, taken while the frozen session was still resident:

| Process | PSS |
|---|---|
| `com.streamvault.app.debug` | **270,938K** |
| `media.codec` | **172,878K** |
| `system` | 65,812K |
| `surfaceflinger` | 60,449K |
| `com.amazon.device.services` | 34,180K |

`MemFree 50,952 kB`, `MemAvailable 117,456 kB`, and **swap is exhausted: 393,212 kB total with 2,696 kB
free**.

So during live playback the app plus the hardware decoder account for ~434 MB of PSS on a 922 MB
device, and the device has swapped itself out. `media.codec` is the video decoder holding buffers for
a 1080p stream, and the app's own largest single term is Graphics at 89 MB.

This reframes the freeze one final time, and it is the most useful conclusion of the last four rounds:

- It is **not** a Java-heap problem, so it is not something A58 would fix.
- It is **not** in the app alone - the decoder process is a peer, not an overhead.
- It is a **capacity** problem: a 1080p live stream decoded alongside a 15,598-channel catalogue does
  not fit on an AFTSSS with 922 MB and a fully consumed swap.

The audit's acceptance metrics - idle CPU, GC count, cold start - never covered memory during
playback, which is why this surfaced only when the live-TV protocol was finally run. Any future
performance work on this device should treat "app + media.codec + swap" as the budget, not the app's
Java heap.

### Round 24 follow-up: the freeze is memory pressure, and it is NOT the Java heap

A second validation run on a fresh process, with logs captured *during* the window, moved this from
"player bug" to something narrower:

| Measurement | Value |
|---|---|
| Session at start of capture | `state=6` (BUFFERING) |
| Session at end of capture | `state=1` (STOPPED), `error=null` |
| Unique frames | 11 of 45 |
| Device memory | `MemTotal 922272 kB`, `MemFree 51092 kB`, `MemAvailable 99436 kB` |
| App PSS | **285782 kB** total, swap PSS 112731 kB |
| App breakdown | Graphics **89013** (EGL mtrack 74284, GL mtrack 14729), Code 23316, Java Heap **22376**, Private Other 16248, Native Heap 15556 |
| Repeated warning | `W/XtreamIndexWorker: Deferring Xtream index work: device low on memory` |

Two conclusions that change the remaining work:

1. **The dominant term is Graphics (89 MB), not the Java heap (22 MB).** A58 is framed as "live staging
   materialises two whole entity lists", and the staging lists are exactly a Java-heap cost. At the
   moment playback died, the Java heap was 22 MB. A58 is still worth doing, but it is **not** the
   explanation for this freeze, and claiming otherwise would send the next round down the wrong path.
2. **The device is genuinely out of memory**, swapping 112 MB, with the index worker explicitly
   deferring itself because of it. Playback dying under that pressure, with `error=null`, is
   consistent with decoder/render starvation rather than a player state-machine bug.

The audit and this remediation effort have both been measuring the Java side, because that is what the
instrumentation reaches. This run says the binding constraint on an AFTSSS is graphics and system
memory. Any further "fix the freeze" work should start from `dumpsys meminfo` during playback, not
from the finding list.

## Device validation recipe (corrected round 22)

Two rounds recorded "the Firestick was unreachable, then held by another app" as the reason live-TV
validation could not run. **That was wrong**, and the cause was a bad launch command:

- The debug build installs as `com.streamvault.app.debug`, not `com.streamvault.app`. Every
  `am start -n com.streamvault.app/.MainActivity` silently started nothing, and the launcher kept
  focus - which read as "another app has focus".
- `monkey -p <pkg> -c android.intent.category.LAUNCHER` lands on **LeakCanary**, which registers its
  own LAUNCHER alias in the debug build. The explicit component is required.

Working sequence on the AFTSSS (192.168.0.2:5555):

```bash
adb connect 192.168.0.2:5555
adb shell am force-stop com.streamvault.app.debug
adb shell am start -n com.streamvault.app.debug/com.streamvault.app.MainActivity
adb shell dumpsys window | grep -m1 mCurrentFocus     # expect com.streamvault.app.debug/...MainActivity
adb shell dumpsys media_session | grep -A5 com.streamvault.app.debug
```

A first run after a data clear shows "Preparing your library" and must finish before any channel can
play; `adb logcat -d | grep "Xtream live category"` shows progress. A34/A35/A20 are all unblocked by
this - none of them needs a workaround, only the correct component name.

## A38 path decision (2026-09-11, implemented as `e8479530`)

Decision 3 was to move `awaitResponse` into `:domain` so the two `LiveTimeshiftManager` sites
could use it. That is the wrong trade: `:domain` is a pure JVM module
(`javax.inject` + `coroutines-core`, no more) and `remote/http/CancellableHttp.kt` is an OkHttp
adapter, so the move would make the domain layer network-aware for a 16-line helper. `:player` already
depends on `libs.okhttp` directly, so the cheaper answer is a local copy of the helper in the player
timeshift package, keeping `:domain` clean. Recorded here so the next round does not repeat the analysis.

The remaining work on A38 is then two independent halves:
1. Convert the 10 remaining `execute()` sites that live in `:data`/`:app` (GoogleDriveBackupSyncManager 3,
   RecordingSourceResolver 2, RecordingCaptureEngine 2, JellyfinProvider, EmbyProvider,
   InternetSpeedTestRunner) to the existing `:data` helper - several are in non-suspend functions, so each
   needs its own suspend cascade.
2. Add the local player helper and convert `LiveTimeshiftManager`'s 2 sites - `fetchText` is non-suspend, so
   that cascades into the HLS/DASH poll path.
Plus the `callTimeout` decision (per-client, background-sync client only).

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

## Open (2)

A35, A54. A34, A18, A20 and A58 moved to Partial - all four are implemented and unit-tested; A34 and
A20 await the AGENTS.md live-TV protocol (which this build currently fails for memory-pressure reasons
unrelated to either change), A18 awaits the composition-count Compose test plus a D-pad check on a TV,
and A58 has had only its staging-boundary half done.

Grouped by why they are still open:

- **Needs a multi-file streaming refactor** — **A58**, half done in `1fb250fc`. A `Sequence` at the staging boundary is too
  late; the whole Xtream/M3U ingest chain has to stream.
- **Needs an upstream signal the code does not have** — **A35** (DASH timeshift must start at the live
  edge).

### A54 - what the obvious fixes do and do not buy (analysed 2026-09-11, not implemented)

Both options in the plan card were re-examined against the actual queries (`Daos.kt:526-575`). Neither
short form works:

- **Debounce/coalesce downstream of the DAO flow does not help.** `channelDao.getGroupedCategoryCounts`
  is a Room `Flow`; Room re-executes the SQL itself on every `channels` invalidation and only then
  emits. A `debounce`/`sample` placed after it suppresses downstream recomposition, not the query.
  Moving the debounce upstream means observing the table through `InvalidationTracker.createFlow` and
  driving a one-shot query from it - a real restructure, and one that only coalesces writes that land
  inside the same window. Progress commits are seconds apart, so it would coalesce almost nothing.
- **Dropping `CAST(id AS TEXT)` is not behaviour-preserving.** Replacing the `ELSE` branch with the raw
  `id` looks free, because SQLite's `DISTINCT` treats INTEGER 5 and TEXT '5' as different values
  rather than equal - but that is exactly the difference: today a grouped channel whose
  `logical_group_id` is the text `'5'` and an ungrouped channel with `id = 5` collapse to one count,
  and afterwards they would not. Counts drive UI badges, so that is a user-visible change to make on a
  guess.
- **Incremental counts** (the card's option 2) is the only fix that actually removes the work, and it
  is a table plus write-path maintenance plus its own invalidation story.

So A54 is open pending a decision between "restructure the count flows behind a debounced invalidation
signal and accept minimal coalescing" and "maintain counts incrementally on write".
- **Needs the `AGENTS.md` live-TV protocol** (61 screenshots, two channels, media session
  `PLAYING`, `error=null`) — **A20**, and **A34**, which is implemented but validated by unit test
  only.
- **Needs visual review on a TV** — **A18** (LazyRow conversion; correctness is testable, the
  scroll/DPad feel is not).

Partial items and their remaining halves are in the table above.

## A6 / A9 device acceptance measurement (2026-09-11)

The audit defined the acceptance test as the thread-dump histogram: how many of 16 samples catch
app code actively executing (present within the top 8 frames).

| Build | Samples with app code executing |
|---|---|
| Baseline (`740bd55f`) | **4 / 16** |
| After memoisation (`fc72267c`) | **1 / 16** |

Method identical to the audit: `adb forward tcp:8700 jdwp:<pid>` then `jdb -attach` with
`suspend` + `where all`, 16 samples, app foregrounded and settled for 90 s.

**Honest reading.** This is a 75% reduction, not the 0/16 the audit implied as the target. The single
remaining hit is still the same pipeline — and specifically `ChannelNormalizer.classifyUncached`,
i.e. a genuine cold cache entry rather than a redundant recomputation:

```
classifyUncached -> classify -> ChannelRepositoryImpl.toVariant -> toPresentedRawChannel
  -> buildPresentedChannels -> observeChannels\$2.invokeSuspend\$lambda\$2
```

So memoisation removed the *re*classification but not the first classification, which is expected:
the first emission after a cold start still classifies the visible catalog once. Driving it to 0/16
would need the classification persisted at ingest (the "classify once and store" half of A6/A9),
which the LRU deliberately does not do — it trades memory for CPU, it does not eliminate the work.

**A first attempt at this measurement was discarded as invalid**: the Firestick dropped off ADB
mid-run, the `pidof` came back empty, and the harness reported a meaningless `0/16` (there was no
device to sample). The script now checks connectivity per iteration and counts valid samples, so a
drop cannot masquerade as a result. Note that this device drops off wireless ADB readily — treat any
sample count below 16 as invalid rather than as data.

## Plan corrections found while attempting A12 and A58 (2026-09-11)

Both were attempted and both have **incorrect remediation guidance** in `docs/planFix.md`. Recorded
here so the next implementer does not follow the plan off a cliff. Neither is implemented.

### A12 — the suggested `INSERT … SELECT` swap would make it WORSE

The plan says: "stage under the real provider id and swap via an `INSERT … SELECT` carrying the target
id (avoiding the second index-maintenance pass)."

Current design (`EpgRepositoryImpl.kt:281`, `:373-374`): staged rows are written to the **same**
`programs` table under `stagingProviderId = -providerId`, then the swap runs
`deleteByProvider(providerId)` + `moveToProvider(stagingProviderId, providerId)`, where
`moveToProvider` is `UPDATE programs SET provider_id = :target WHERE provider_id = :source`.

Per staged row that is **two** index-maintenance passes: the staging INSERT, then the UPDATE's
index delete+reinsert across the six indices on `programs`.

The suggested alternative is INSERT (staging) → INSERT … SELECT (target id) → DELETE (staging) =
**three** passes per row. It is worse, not better. The staging indirection exists so the guide stays
populated while a refresh downloads; it is a deliberate liveness trade, not an oversight.

The change that would actually pay is the plan's own second suggestion — **skip the rewrite entirely
for an unchanged feed**, since it currently re-runs on every 6 h TTL regardless. That needs a stored
feed identity (a content hash column, or HTTP ETag/Last-Modified plumbed through the download), i.e. a
schema or download-layer change. **Needs a decision.**

### A58 — the method it says to mirror does not exist

The plan says to "give the live path a `Sequence` entry point mirroring `stageChannelSequence`".
There is **no** `stageChannelSequence`. The existing analogous methods are
`stageMovieSequence`/`stageSeriesSequence` (`SyncCatalogStore.kt:689`, `:706`), both **private**, both
delegating to `stageDistinctRows`. The live path uses the public list-based `stageChannelBatch`
(`:358`).

More importantly, adding a `Sequence`-taking staging method would **not** reduce peak memory on its
own: the live path materialises `liveResult.items` in the **provider** and maps it to entities long
before staging, so a `Sequence` at the staging boundary is already too late. The whole live ingest
chain — Xtream/M3U provider → entities → `buildChannelStages` → stage — has to stream, which is a
multi-file refactor against `SyncCatalogStoreMemoryTest`'s existing peak-heap assertions.

## Decisions taken 2026-09-11 (all recommendations accepted)

Recorded so these are not re-litigated. Each unblocks work that was previously gated.

| # | Finding | Decision |
|---|---|---|
| 1 | **A52** watermark (also unblocks **A54**) | **Explicit monotonic column on the stage tables.** Rejected the implicit `rowid` watermark because a row upserted with REPLACE gets a new rowid, so a watermark could skip a genuinely-changed row — silent catalog data loss. |
| 2 | **A12** unchanged-feed skip | **Content hash of the feed, stored per provider.** Deterministic, and catches identical content served from different URLs or with reordered attributes. |
| 3 | **A38** remaining 12 sites | ~~Move `awaitResponse` into `:domain`, convert all 12.~~ **SUPERSEDED by `e8479530`**: `:domain` is a pure JVM module with no OkHttp, so the helper was copied into the `:player` timeshift package instead (see the A38 section above). |
| 4 | **A38** callTimeout | **Cap the background-sync client only.** The main client keeps its uncapped EPG path deliberately — a 200 MB feed on a slow link can legitimately outlast any reasonable total-call budget. |
| 5 | **A34** MEMORY timeshift | **Cap the MEMORY window to a fixed byte budget (~48 MB)** against the 192 MB heap class, rather than removing the backend. |
| 6 | **A18** guide layout | **Implement the LazyRow conversion; the user reviews the guide visually on the TV.** |
| 7 | Live-TV validation | **Run the full AGENTS.md protocol (61 screenshots, ~2 min, two channels) per affected finding** — A20, A35, and anything else touching playback or timeshift. |
| 8 | **A5** date parsing | **Retry `ParsePosition`, tests-first** — pin the offset-less and malformed cases as failing tests, then establish empirically what `ParsePosition` does to `position.index` and field resolution for these patterns. |
| 9 | Priority | **A4 next**, ahead of A58. |

## A4 progress: guard tests written, and a premise corrected (2026-09-11)

The A4 remediation is tests-first by decision. The guard tests are now in
`OkHttpXtreamApiServiceTest` (14 tests, all passing) and pin:

- **a malformed element mid-array aborts the stream** with `XtreamParsingException` carrying the
  descriptor hint, and the good row before it was ALREADY emitted — i.e. the path really does stream,
  and it does not silently skip bad rows. The refactor must preserve both.
- **unquoted values are REJECTED**, which corrects the plan.

**Correction:** the A4 notes assumed the Gson reader's `isLenient = true` makes the thin path
tolerate non-strict JSON, and warned that a kotlinx streaming decoder would have to match that
leniency. Measured, it does **not**: `[{ "stream_id": "101", "name": Live One }]` fails today with
`XtreamParsingException`, because `JsonParser.parseReader` rebuilds the node and
`element.toString()` re-emits **strict** JSON before kotlinx ever parses it. The leniency flag buys
nothing on this path. So the refactor has **less** to preserve than the plan claimed — good news, and
the opposite of what the plan warned about.

**Feasibility established:** `Json.decodeToSequence` resolves and compiles against the current
dependency set (`kotlinx-serialization-json-jvm:1.9.0`, no json-io artifact needed). That matters
because the obvious alternative — `decodeFromStream<List<…>>` — would materialise the whole array
and **regress memory** on the 15k-channel live catalog, which is exactly what the element-by-element
Gson loop was introduced to avoid. `decodeToSequence` streams element by element and removes both the
Gson tree parse and the `toString()` round-trip.

## Environment notes

- `CancellableHttpTest` is **flaky and pre-existing**: different test cases fail on different runs when
  the class runs alongside others, and it passes 2/2 in isolation. `CancellableHttp` is untouched by
  any commit in this work. Do not chase it.
- `kill -3` does not work on this unrooted Fire OS device; thread dumps need the JDWP + `jdb`
  recipe in `docs/planFix.md` §2.2.
- `:app:compileDebugUnitTestKotlin` **was broken pre-existing and is now FIXED** (`d3ece615`):
  `StartupCoordinatorTest` passed an `ioDispatcher` parameter that `StartupCoordinator` no longer
  accepted, so no `app/` unit test compiled or ran at all. That hid a regression - the guide-search
  work had moved `buildSearchGuideSnapshot` onto a hard-coded `Dispatchers.Default`, a real background
  thread a coroutine test scheduler cannot advance. Both are fixed and `:app:testDebugUnitTest` now
  runs **291 tests, 0 failures**. Findings verified before this fix were checked by compile plus the
  `domain`/`data`/`player` suites only and should be re-checked against the app suite where they touch
  `app/`.
- **Do not name `Dispatchers.Default` in an `app/` ViewModel.** Use the `@DefaultDispatcher` binding
  (`app/.../di/DispatcherModule.kt`) and pass the test dispatcher in tests; a hard-coded `Default`
  silently hangs every `waitForUiState`-style test that drives Main with a `StandardTestDispatcher`.
- **Cold start is ~17 s and pre-existing**, confirmed by a controlled A/B (stash, rebuild, reinstall,
  measure): +18.8s/+17.8s/+17.3s before, +17.3s/+17.4s after PR 1. The audit's original +3s082ms
  reading was not reproducible. Thread dumps place startup inside `NetworkModule.provideOkHttpClient`
  and Hilt graph construction.
