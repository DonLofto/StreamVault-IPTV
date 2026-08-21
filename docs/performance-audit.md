# Android Performance Audit

Static source audit of StreamVault on `buffering-fixes`. No production code changed.

Scope followed module order: `player/`, `data/`, `app/`, then Gradle/package and test review.
`docs/buffering-investigation.md` findings were excluded, including its documented player
reprepare paths, duplicate live capture, token renewal, retry loop, and per-second support
snapshot write. `graphify-out/` was not present in this checkout.

Refs use `file:line`. Runtime-cost statements are source-proven paths unless marked for
profiling. Device tiers:

- **Tier A:** 1-1.5 GB RAM TV dongles, older Fire TV / Android TV, slow eMMC.
- **Tier B:** current mid-range Android TV boxes and televisions.
- **Tier C:** phones and tablets.

Render-path check: `PlayerRenderView` defaults to `AUTO` (`app/src/main/java/com/streamvault/app/ui/components/PlayerRenderView.kt:13-33`), and engine state starts as `SURFACE_VIEW` (`player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt:269-270`). AUTO selects `SurfaceView` except Android <= 25 and a narrowly targeted older Amazon MediaTek path; live HLS on that Amazon path is forced back to `SurfaceView` (`Media3PlayerEngine.kt:1659-1693`). No finding says `TextureView` is the general default.

---

## Performance findings

### HIGH - startup, memory, storage, and network contention

### H1. External backup import copies arbitrary input on main thread before first Compose frame

`app/src/main/java/com/streamvault/app/MainActivity.kt:116-141, 342-357, 399-407`
`app/src/main/java/com/streamvault/app/backup/BackupFileBridge.kt:45-54`

- **Current behavior:** `onCreate()` handles an external import intent before `setContent`. The import path prunes cache files, opens the provider URI, and copies all bytes with `input.copyTo(output)` on the caller thread.
- **Cost and tier:** A slow `content://` provider or large backup blocks first draw and can trigger an ANR. Tier A and B high; Tier C medium. Cache consumption is unbounded by this path.
- **Proposed fix:** Show initial UI, then copy on `Dispatchers.IO` with progress and cancellation. This is behavior-preserving. A maximum import size is a behavior change.
- **Verification:** Trace `MainActivity.onCreate` and main-thread blocked time with a large document-provider backup on Tier A hardware.

### H2. Bulk catalog writes maintain FTS twice: per-row content-sync triggers plus full rebuild

`data/src/main/java/com/streamvault/data/local/entity/Entities.kt:354-369`
`data/src/main/java/com/streamvault/data/local/dao/CatalogSyncDao.kt:851-860`
`data/src/main/java/com/streamvault/data/sync/SyncCatalogStore.kt:87-115, 130-138`
`data/src/main/java/com/streamvault/data/local/StreamVaultDatabase.kt:1867-1872`

- **Current behavior:** Each FTS table declares `@Fts4(contentEntity = ...)`, which creates Room-managed content synchronization. Bulk catalog replacement also executes `INSERT INTO <fts>(<fts>) VALUES('rebuild')` for channels, movies, and series.
- **Cost and tier:** A large sync mutates FTS for every changed row, then rebuilds the whole index. Tier A high due to CPU, WAL, and flash I/O; Tier B high on large providers; Tier C medium.
- **Proposed fix:** Pick one maintenance model. Retain Room content-sync and remove explicit rebuilds, or remove the content-entity strategy and run exactly one explicit rebuild inside the same atomic catalog transaction (or add interruption-safe recovery). The latter matches the migration comment's stated intent. **Fix class:** behavior-preserving if validation covers insert, update, delete, and interrupted sync.
- **Verification:** Benchmark 10k and 50k catalog fixtures with SQLite statement tracing, transaction duration, WAL growth, and post-sync FTS result parity.

### H3. Catalog staging holds every remote key in heap despite catalog limits

`data/src/main/java/com/streamvault/data/sync/CatalogSizeLimits.kt:3-7`
`data/src/main/java/com/streamvault/data/sync/SyncCatalogStore.kt:701-753`

- **Current behavior:** `stageDistinctRows()` puts every distinct remote key in `HashSet`, while retaining best candidates in a bounded `PriorityQueue`. The priority queue is capped, but `seenKeys` grows with all distinct source rows.
- **Cost and tier:** A provider with hundreds of thousands of VOD/series rows creates a long-lived `O(total distinct rows)` set plus retained entities during import. Tier A high OOM/GC risk; Tier B medium; Tier C low to medium.
- **Proposed fix:** Use database-backed dedupe/staging or bounded spill storage, then rank in SQL. Preserving current "best rows across entire source" semantics requires this; stopping at the limit is a behavior change.
- **Verification:** Measure peak Java heap, GC pause time, and SQLite/WAL size against oversized Xtream fixtures.

### H4. EPG query and mapping paths materialize unbounded guide windows

`data/src/main/java/com/streamvault/data/local/dao/Daos.kt:2896-2958`
`data/src/main/java/com/streamvault/data/repository/EpgRepositoryImpl.kt:107-162`
`data/src/main/java/com/streamvault/data/epg/EpgResolutionEngine.kt:64-119, 262-360`

- **Current behavior:** Program DAO queries have no result limit. Repository code maps all returned entities and groups them in memory; resolved EPG processing flattens all chunk results before grouping.
- **Cost and tier:** A wide time range, many channels, or multi-source XMLTV produces large temporary lists and maps. Tier A high; Tier B medium; Tier C medium for large tablet guide views.
- **Proposed fix:** Fetch and merge only visible channel/page windows, streaming chunks into keyed output rather than flattening all rows. This is behavior-preserving when the UI still requests the same visible window. A hard result/time cap changes behavior and needs explicit UX handling.
- **Verification:** Profile result count, retained heap, GC, and frame time for a 60-channel, 7-hour guide with dense XMLTV data.

### H5. XMLTV accepts a 200 MiB compressed body without a decompressed-size or programme cap

`data/src/main/java/com/streamvault/data/remote/NetworkTimeoutConfig.kt:8-9`
`data/src/main/java/com/streamvault/data/repository/EpgRepositoryImpl.kt:304-339`
`data/src/main/java/com/streamvault/data/repository/EpgSourceRepositoryImpl.kt:316-372`
`data/src/main/java/com/streamvault/data/parser/XmltvParser.kt:539-547`

- **Current behavior:** The input cap is applied before `GZIPInputStream`. Decompression and programme staging have no decompressed-byte, programme-count, or free-space limit. Provider sync can take the EPG path outside `BackgroundEpgSyncWorker`'s low-memory gate.
- **Cost and tier:** A highly compressible or simply very large XMLTV feed can consume heap, CPU, database space, and flash while playback competes for resources. Tier A high; Tier B medium; Tier C low to medium.
- **Proposed fix:** Add decompressed-byte and programme-count limits, require storage-not-low/free-space admission, and route every stale EPG path through one low-memory policy. Limits are a behavior change for oversized feeds; shared admission is behavior-preserving.
- **Verification:** Test realistic compressed feeds with controlled expansion ratios and profile heap, peak cache/DB space, and playback coexistence.

### H6. Playback, catalog sync, EPG, and images share one high-concurrency OkHttp dispatcher

`app/src/main/java/com/streamvault/app/di/NetworkModule.kt:64-83`
`data/src/main/java/com/streamvault/data/sync/ProviderSyncWorker.kt:183-246`
`data/src/main/java/com/streamvault/data/sync/BackgroundEpgSyncWorker.kt:123-142`
`data/src/main/java/com/streamvault/data/repository/EpgRepositoryImpl.kt:76-80`
`player/src/main/java/com/streamvault/player/playback/PlayerDataSourceFactoryProvider.kt:69-98`
`app/src/main/java/com/streamvault/app/StreamVaultApp.kt:55-59`

- **Current behavior:** A singleton client has a 64-request dispatcher and 10-per-host limit. `newBuilder()` clients share its dispatcher and connection pool. Connected-network workers can run catalog/EPG work while player and Coil requests use the same transport.
- **Cost and tier:** On weak Wi-Fi, limited provider connections, or multiview, background calls can occupy host slots, radio time, CPU, and disk while playback needs segments. Tier A and B high under contention; Tier C medium on cellular or weak Wi-Fi.
- **Proposed fix:** Add a low-priority/admission governor for background sync, reserve player capacity, and defer large work during active playback. **Fix class:** results-preserving, but a behavior change in request timing/freshness. **Playback stability validation required** for any change here.
- **Verification:** Capture dispatcher queue/running counts, per-host active calls, bytes, worker state, and player dropped/rebuffer metrics on constrained Wi-Fi.

### H7. TV Input Framework sync loads and mutates a full catalog row by row

`app/src/main/java/com/streamvault/app/tvinput/TvInputChannelSyncManager.kt:43-80, 83-206, 293-296`
`app/src/main/java/com/streamvault/app/MainActivity.kt:134-139`

- **Current behavior:** Television startup schedules a TIF refresh. The manager loads all channels and matching EPG snapshots, reads existing platform channels without an `input_id` selection, then performs existence/program checks and `ContentResolver` mutations per channel.
- **Cost and tier:** Large provider catalogs create database, heap, binder, and provider I/O pressure after launch. Tier A high; Tier B high with many channels; Tier C not applicable unless using TIF.
- **Proposed fix:** Filter existing channels by input ID, diff/fingerprint unchanged rows, batch mutations, and stream channel/program work. **Fix class:** behavior-preserving for the resulting TIF catalog. Deferring or adding a refresh TTL changes freshness behavior.
- **Verification:** Instrument channel count, resolver operation count, wall time, allocations, and first-screen responsiveness on 1k/5k-channel catalogs.

### MEDIUM - Compose, player-adjacent work, cold-start contention, and package size

### M1. EPG composes every programme cell in each visible row and invalidates all cells every 30 seconds

`app/src/main/java/com/streamvault/app/ui/screens/epg/EpgViewModel.kt:282-286`
`app/src/main/java/com/streamvault/app/ui/screens/epg/EpgControlComponents.kt:80-95`
`app/src/main/java/com/streamvault/app/ui/screens/epg/EpgGridComponents.kt:327-535, 542-574`

- **Current behavior:** A seven-hour guide window feeds `programs.forEach` inside each visible row. Each `ProgramItem` reads the shared guide clock, recalculates current state and time strings, and remains a focusable surface. Horizontal programme content is not virtualized.
- **Cost and tier:** Dense schedules inflate composition/layout/focus-tree work. The 30-second tick can create a visible frame-time spike. Tier A high risk; Tier B medium; Tier C medium on large tablet layouts.
- **Proposed fix:** First profile. Cache immutable programme geometry and labels, isolate only current-state invalidation, and consider horizontal virtualization/windowing. Caching is behavior-preserving. Virtualization can alter off-screen D-pad focus traversal and is a behavior change unless focus semantics are preserved.
- **Verification:** Compose Layout Inspector and Perfetto tracing on a 60-channel page with dense schedule data and repeated D-pad navigation.

### M2. Seek thumbnails allocate full-resolution frames and cancellation cannot interrupt a blocking retriever call

`app/src/main/java/com/streamvault/app/ui/screens/player/SeekThumbnailProvider.kt:45-78, 85-88`
`app/src/main/java/com/streamvault/app/ui/screens/player/PlayerPlaybackPreferenceActions.kt:314-358`

- **Current behavior:** Each uncached scrub bucket calls `MediaMetadataRetriever.setDataSource()` and frame extraction on `Dispatchers.IO`; timeout cannot preempt non-suspending framework calls. Cache misses are not single-flight. Scaling allocates another bitmap while the decoded original is still live.
- **Cost and tier:** Rapid VOD scrub can queue expensive frame extraction and cause allocation/GC pressure. Tier A and B high for remote/high-resolution files; Tier C medium.
- **Proposed fix:** Limit thumbnail concurrency to one bounded worker, coalesce in-flight bucket requests, release intermediate bitmaps safely, and cap cache by bytes. Normal preview behavior is preserved; stricter extraction timeout/disablement on weak devices changes behavior. **Playback stability validation required** if player/source sharing changes.
- **Verification:** Record heap, bitmap allocations, CPU, and seek responsiveness while scrubbing 4K local and remote VOD.

### M3. Player read diagnostics do work on every instrumented stream read even when no diagnostic log is emitted

`player/src/main/java/com/streamvault/player/playback/PlayerDataSourceFactoryProvider.kt:99-107, 170-208`
`player/src/main/java/com/streamvault/player/playback/PlayerDataSourceReadStats.kt:19-25, 39-40, 49, 63-67`
`player/src/main/java/com/streamvault/player/stats/PlayerStatsCollector.kt:110-179`

- **Current behavior:** HLS/MPEG-TS data sources are wrapped for read statistics. The wrapper sanitizes/records targets and evaluates debug-log state on positive reads. Active statistics also create immutable state snapshots at fixed cadence before `StateFlow` can suppress equal emissions.
- **Cost and tier:** Per-read bookkeeping is most visible for high-bitrate live streams and multiview on Tier A; Tier B medium; Tier C low.
- **Proposed fix:** Construct the wrapper only for an explicit diagnostics session, or defer sanitization until an emitted sample. Compare scalar values before creating snapshot objects. Restricting always-on telemetry changes diagnostic coverage; scalar allocation reduction is behavior-preserving. **Playback stability validation required**.
- **Verification:** Allocation sampling and CPU traces during HLS/MPEG-TS playback with diagnostics on and off.

### M4. Cache budgets are independent and can overcommit constrained `cacheDir` storage

`app/src/main/java/com/streamvault/app/di/NetworkModule.kt:64-70`
`app/src/main/java/com/streamvault/app/StreamVaultApp.kt:135-159`
`player/src/main/java/com/streamvault/player/timeshift/TimeshiftDiskManager.kt:15-19, 63-66`
`player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt:292-300`

- **Current behavior:** HTTP cache allows 256 MiB, Coil allows 100 MiB plus up to 15% memory, and timeshift allows up to 2 GiB. These maxima are not preallocated but no common quota accounts for combined occupancy.
- **Cost and tier:** Low-storage TV devices can fill cache space during timeshift plus image/catalog use, causing cleanup churn or failed writes. Tier A high; Tier B medium; Tier C low unless offline use is heavy.
- **Proposed fix:** Derive a shared app cache budget from available/cache quota space and partition it among timeshift, HTTP, and images. This changes capacity/cache-hit behavior but preserves content behavior.
- **Verification:** Track cache-directory occupancy, eviction rate, failed writes, and free space during timeshift and browse workloads.

### M5. Cold start eagerly schedules work and constructs a broad singleton graph

`app/src/main/java/com/streamvault/app/StreamVaultApp.kt:60-97`
`app/src/main/java/com/streamvault/app/MainActivity.kt:61, 78-141`
`app/src/main/java/com/streamvault/app/di/DatabaseModule.kt:25-109`
`app/src/main/java/com/streamvault/app/di/NetworkModule.kt:95-119`

- **Current behavior:** Application startup starts diagnostics, cleanup, app-update work, periodic workers, launch stale checks, and an immediate recording reconcile. Application/Activity injection reaches preferences, Room-related DAOs, protocol clients, Cast/plugin state, and image dependencies before first interaction.
- **Cost and tier:** Work runs off main where shown, but database/storage/network contention overlaps cold start. Dependency creation increases CPU and allocation before user work. Tier A medium to high; Tier B medium; Tier C low to medium.
- **Proposed fix:** Use `Lazy`/`Provider` for protocol- and feature-specific dependencies; coalesce/defer non-urgent launch work after first interaction. Dependency deferral is behavior-preserving if initializers have no required side effects. Deferring reconcile/sync changes recovery/freshness timing.
- **Verification:** Baseline Profiles plus Macrobenchmark startup traces, Hilt/Room init slices, binder work, and storage I/O on Tier A.

### M6. Historical migrations can rewrite large tables; no production-size migration benchmark protects update time

`app/src/main/java/com/streamvault/app/di/DatabaseModule.kt:44-105`
`data/src/main/java/com/streamvault/data/local/StreamVaultDatabase.kt:249-569, 665-929, 1417-1596, 2220-2522`
`data/src/androidTest/java/com/streamvault/data/local/StreamVaultDatabaseMigrationTest.kt:90-137, 1195-1205`

- **Current behavior:** Version 1 through 62 migrations are contiguous and non-destructive. Several historical migrations copy/remap whole tables, build FTS, loop through legacy rows, or backfill index tables. Tests titled "latest" stop at v42 or v61 and omit v62.
- **Cost and tier:** Only old installs pay the full chain, but a large existing catalog on slow flash can make first open after update long. Tier A high for affected upgrades; Tier B medium; Tier C low.
- **Proposed fix:** Extend migration coverage to 62 and add device/instrumented benchmark fixtures representing large legacy catalogs. Test coverage is behavior-preserving. Any destructive fast-path is a behavior change and risks data loss.
- **Verification:** Measure open-to-ready time, DB/WAL temporary size, free space, and migration correctness from representative old schemas.

### M7. Beta builds retain unshrunk code/resources; broad keep rules reduce release shrinking

`app/build.gradle.kts:104-125`
`app/proguard-rules.pro:12-13, 40-45, 53-55, 68-71`
`player/build.gradle.kts:40-114`

- **Current behavior:** Release enables R8 and resource shrinking. Beta explicitly disables both. Release rules keep all Hilt and Gson packages plus several broad model families. FFmpeg is bundled locally and the app restricts install ABIs to ARM (`app/build.gradle.kts:67-69`).
- **Cost and tier:** Beta download/install/storage footprint is larger for every tester. Broad keeps can leave removable release code. ARM filtering prevents unused x86 native code from shipping in ARM installs, but intentionally excludes pure x86/x86_64 devices. Tier A/B storage impact medium; Tier C medium for beta users.
- **Proposed fix:** Enable shrinking for beta distribution after stack-trace/debug needs are agreed. Narrow keep rules only after a minified-release regression suite. Both preserve app behavior but alter diagnostics/debuggability. Retaining ARM-only delivery is a product compatibility choice; adding x86 increases delivery footprint.
- **Verification:** Compare APK/AAB size reports, R8 `usage.txt`, native ABI contents, startup, and reflection/serialization tests.

### M8. Slow-query logging cannot identify slow cursor materialization or mapping

`app/src/main/java/com/streamvault/app/di/DatabaseModule.kt:23, 34-42`
`app/src/main/java/com/streamvault/app/di/SlowQueryLoggingOpenHelperFactory.kt:45-82, 94-128`

- **Current behavior:** Debug-only logging warns at 100 ms around `query`, `execSQL`, and statement calls. `query()` timing ends when Room receives a `Cursor`, before generated code iterates rows and before domain mapping/grouping.
- **Cost and tier:** Existing logs can expose slow SQL setup/DML but cannot falsify the unbounded EPG/list materialization findings. This is a diagnostic blind spot, not proof of a current slow query. Tier A/B impact is indirect.
- **Proposed fix:** Add sampled end-to-end repository timing with row counts and query labels, or use Perfetto/SQLite tracing in performance builds. Behavior-preserving if telemetry is sampled/off by default.
- **Verification:** Correlate Room query start, cursor iteration, mapping, and UI collection time for EPG and large catalog pages.

### LOW - targeted UI and query improvements

### L1. Channel browse ordering likely forces avoidable SQLite sorts on large channel lists

`data/src/main/java/com/streamvault/data/local/dao/Daos.kt:96-110, 162-196`
`data/src/main/java/com/streamvault/data/local/entity/Entities.kt:90-95`

- **Current behavior:** Channel browse filters by provider/category and orders by channel number, while declared indexes omit the final `number` column.
- **Cost and tier:** Large all-channel/category lists may scan and sort rather than satisfy ordering through an index. Tier A medium; Tier B low; Tier C low.
- **Proposed fix:** Validate with target-device `EXPLAIN QUERY PLAN`, then add `(provider_id, number)` and `(provider_id, category_id, number)` only if plans and write cost justify them. Index addition is behavior-preserving.

### L2. Debug runtime diagnostics append forever while app is foregrounded

`app/src/main/java/com/streamvault/app/diagnostics/RuntimeDiagnosticsManager.kt:41-76, 88-126, 169-172`
`app/src/main/java/com/streamvault/app/StreamVaultApp.kt:38-40, 63-64`

- **Current behavior:** Debug builds append lifecycle/memory snapshots every 30 seconds with no byte cap or rotation. Release exits early, but the manager is still forced from a lazy property during Application startup.
- **Cost and tier:** Long debug soak runs accumulate cache/disk writes and eventually a large diagnostics file. Tier A/B low; release impact is only small startup allocation.
- **Proposed fix:** Use bounded rotation/ring retention and avoid constructing diagnostics until a debug session starts. Rotation is behavior-preserving for current diagnostic utility; retention horizon changes.

### L3. Channel-logo fallback remounts the image request after success/failure state changes

`app/src/main/java/com/streamvault/app/ui/components/ChannelLogo.kt:49-80`
`app/src/main/java/com/streamvault/app/ui/screens/epg/EpgGridComponents.kt:419-426`

- **Current behavior:** `ChannelLogo` selects different `AsyncImage` call sites when fallback visibility changes, unlike poster cards that retain one image node under a fallback layer.
- **Cost and tier:** Cold-cache or failing logos can recreate painters in EPG/Home/dashboard rows. Tier A/B low to medium; Tier C low.
- **Proposed fix:** Keep one `AsyncImage` mounted and layer initials/fallback behind it. Behavior-preserving. Profile Coil cache-hit rate and recomposition first.

---

## Bugs found

### B1. DASH timeshift init segment permanently blocks pruning

`player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt:847-852, 934-942`

- **Reproduction:** Enable disk or memory timeshift on a live DASH fMP4 stream long enough to exceed configured rewind depth.
- **Expected:** Old media segments are pruned, preserving the configured rolling depth.
- **Actual:** The zero-duration init segment is first in `segments`; `pruneSegmentsLocked()` breaks on it and never removes later media segments. Memory/files/seen keys can grow for the session.
- **Severity:** Crash/data-loss risk through OOM or storage exhaustion.
- **Fix:** Store init separately or skip it while pruning; retain it for snapshot creation. Behavior-preserving.

### B2. Stopping or replacing timeshift can publish a stale FAILED state

`player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt:208-220, 225-230, 261-265, 350-353`

- **Reproduction:** Start live timeshift, then change channel, disable rewind, reset player, or release while capture is running.
- **Expected:** Cancelled session remains disabled or new session state remains authoritative.
- **Actual:** `Session.stop()` cancels its job, but outer `catch (Throwable)` turns `CancellationException` into `FAILED`; no active-session identity check prevents an old session overwriting newer state.
- **Severity:** Incorrect behaviour.
- **Fix:** Rethrow cancellation, check `activeSession === session` before state publication, and coordinate cancellation/deletion before allowing replacement session work. Behavior-preserving.

### B3. Movie and series filtered paging returns empty pages at offset 200+

`data/src/main/java/com/streamvault/data/repository/MovieRepositoryImpl.kt:101, 830-946, 1030-1031, 1085-1110`
`data/src/main/java/com/streamvault/data/repository/SeriesRepositoryImpl.kt:722-724, 988-989, 1056-1095`

- **Reproduction:** Browse a provider with more than 200 favorite, unwatched, in-progress, or similarly filtered movies/series; request offset 200.
- **Expected:** Page begins with item 201.
- **Actual:** Source queries use `OFFSET 0`, fetch limit is capped at `SEARCH_RESULT_LIMIT = 200`, then the repository drops `query.offset`; result becomes empty while count can still report more rows.
- **Severity:** Incorrect behaviour.
- **Fix:** Pass the requested offset to filtered DAO page queries, or iteratively fetch source windows when post-query filtering requires overscan. Behavior-preserving intended paging semantics.

### B4. EPG Flow silently stops observing updates for more than 500 channels

`data/src/main/java/com/streamvault/data/repository/EpgRepositoryImpl.kt:106-124, 127-150`

- **Reproduction:** Collect `getProgramsForChannels()` for 501+ IDs, then insert/replace an affected programme.
- **Expected:** Room invalidation produces a new map, as it does for 500 or fewer IDs.
- **Actual:** Multiple chunks take a one-shot `flow { emit(snapshot) }` path; collection completes and later updates never arrive.
- **Severity:** Incorrect behaviour.
- **Fix:** Combine chunked DAO flows then merge results, or expose a clearly named snapshot-only API. Restoring a reactive Flow changes current semantics but makes behavior consistent with the method contract.

### B5. Query-string HLS/DASH URLs can be captured as progressive timeshift streams

`player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt:314-325`

- **Reproduction:** Start timeshift with `StreamInfo.streamType == UNKNOWN` and URL `https://host/live.m3u8?token=...` or `.mpd?token=...`.
- **Expected:** Type inference chooses HLS/DASH capture.
- **Actual:** Raw `endsWith(".m3u8")`/`endsWith(".mpd")` fails after a query string, chooses progressive capture, and can store playlist text as a media segment.
- **Severity:** Incorrect behaviour.
- **Fix:** Infer from parsed URI path or container extension. Behavior-preserving.

### B6. Latest TIF channel tune can lose to an older asynchronous tune

`app/src/main/java/com/streamvault/app/tvinput/StreamVaultTvInputService.kt:69-75, 93-118`

- **Reproduction:** Tune channel A, immediately tune B, and make A's database/stream resolution finish after B.
- **Expected:** B remains selected.
- **Actual:** Every `onTune` launches an untracked coroutine. A can finish last and call `player.setMediaSource()` after B.
- **Severity:** Incorrect behaviour.
- **Fix:** Cancel/replace previous tune `Job` or attach a monotonic tune generation and check it after each suspension. Behavior-preserving final-tune semantics.

### B7. EPG pagination resets focus and scroll to first channel

`app/src/main/java/com/streamvault/app/ui/screens/epg/EpgGridComponents.kt:115-157, 196-198`
`app/src/main/java/com/streamvault/app/ui/screens/epg/EpgViewModel.kt:574-580, 597-605`

- **Reproduction:** Open a category with more than one page, navigate near its tail, and allow next-page append.
- **Expected:** Current focus and viewport remain near the channel being browsed.
- **Actual:** Append changes `channels.size`; `LaunchedEffect(channels.size, ...)` scrolls near first row and requests initial focus after 140 ms.
- **Severity:** Incorrect behaviour.
- **Fix:** Key initialization to guide/session/category entry, not appended list size. Behavior-preserving.

### B8. EPG route arguments race restored guide preferences after process death

`app/src/main/java/com/streamvault/app/ui/screens/epg/EpgViewModel.kt:319-329, 1475-1495`
`app/src/main/java/com/streamvault/app/ui/screens/epg/EpgScreen.kt:242-247`
`app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt:648-660`

- **Reproduction:** Persist a category/favorite/anchor preference, kill process, then open an EPG deep link with explicit route state.
- **Expected:** Defined route-versus-saved-state precedence.
- **Actual:** Asynchronous `first()` reads can overwrite navigation state after screen arguments are applied; scheduler timing decides destination/filter/anchor.
- **Severity:** Incorrect behaviour.
- **Fix:** Define precedence, model absent versus explicit `favoritesOnly=false`, and serialize restoration with route application. This needs a product-state decision before implementation.

### B9. Movies and Series leave old-provider continue-watching collectors active

`app/src/main/java/com/streamvault/app/ui/screens/movies/MoviesViewModel.kt:376-397`
`app/src/main/java/com/streamvault/app/ui/screens/series/SeriesViewModel.kt:380-401`

- **Reproduction:** Open Movies or Series on provider A, switch to B, then cause A history to emit.
- **Expected:** Only B history updates B's screen.
- **Actual:** A child `launch` starts inside `collectLatest` but belongs to the outer `viewModelScope`; the action returns immediately, so provider change does not cancel the child collector. Old data can overwrite UI and collectors accumulate.
- **Severity:** Incorrect behaviour.
- **Fix:** Use `flatMapLatest` directly from active provider to `getContinueWatching`, or retain/cancel the child job. Behavior-preserving.

### B10. Full transparent player guide has state/actions but no screen integration

`app/src/main/java/com/streamvault/app/ui/screens/player/PlayerOverlayActions.kt:57-75`
`app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerTransparentGuideOverlay.kt:55`
`app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt:1212-1309`

- **Reproduction:** From live player, invoke GUIDE/full-guide action.
- **Expected:** Transparent guide is rendered and focused.
- **Actual:** `openFullGuideOverlay()` only flips state. `PlayerScreen` does not collect/render `showFullGuideOverlay`; `EpgOverlay` is not passed its existing `onOpenFullGuide` callback.
- **Severity:** Incorrect behaviour.
- **Fix:** Wire state and callbacks into one exclusive overlay host. Behavior-preserving intended feature behavior.

### B11. Live-player root preview handler zaps channels before controls receive DPAD Up/Down

`app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt:569-622`
`app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerControlsChrome.kt:1197-1213, 1851-1854`

- **Reproduction:** Play live content, show controls, press DPAD Up or Down.
- **Expected:** Focus moves through visible controls.
- **Actual:** Root `onPreviewKeyEvent` sees live content with no listed overlay and invokes `playNext()`/`playPrevious()` before child control key handlers run.
- **Severity:** Incorrect behaviour.
- **Fix:** Exclude visible controls from zap interception or move zap handling after child focus navigation declines the event. Behavior-preserving intended remote behavior.

### B12. Automatic decoder recovery records software preference but does not select a software MediaCodec

`player/src/main/java/com/streamvault/player/playback/DecoderPreferencePolicy.kt:29-32`
`player/src/main/java/com/streamvault/player/playback/CodecPreference.kt:17-20, 82-86`
`player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt:2259-2270, 2345-2365, 1216-1231`

- **Reproduction:** Use AUTO decoding on a device/stream that triggers decoder-error recovery.
- **Expected:** Recovery's `SOFTWARE_PREFERRED` policy changes renderer decoder ordering.
- **Actual:** Selector eligibility uses original requested AUTO mode, disables managed selector, and builds renderers with `MediaCodecSelector.DEFAULT`; the failed hardware codec can be selected again.
- **Severity:** Incorrect behaviour.
- **Fix:** Enable managed selection when effective recovery policy is non-AUTO, preserving stock AUTO for ordinary playback. **Playback stability validation required** on affected hardware.

### B13. VOD slider value resets during a drag when playback position updates

`app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerControlsChrome.kt:1121-1133, 1292-1314`

- **Reproduction:** Drag VOD seek control while player position Flow updates.
- **Expected:** Thumb follows drag until release.
- **Actual:** `remember` state is keyed by `currentPosition`; a new playback position resets slider state before the drag guard can preserve it.
- **Severity:** Incorrect behaviour.
- **Fix:** Keep drag state independent of playback-position key and synchronize only when not dragging. Behavior-preserving.

### Test observations

- No `@Ignore` or `@Disabled` tests were found in player/data/app unit-test source. Two launcher-provider instrumentation tests use `assumeTrue` on non-TV runs (`app/src/androidTest/java/com/streamvault/app/tv/LauncherProviderInstrumentationTest.kt:30-32, 83-85`), so non-TV runs provide no coverage for that feature.
- `XmltvParserTest` accepts zero through two partial programmes after malformed input, allowing regression that drops all accumulated results (`data/src/test/java/com/streamvault/data/parser/XmltvParserTest.kt:250-271`; parser contract `XmltvParser.kt:219-227`). Assert exact retained programmes.
- Migration tests named "latest" stop before schema 62 (see M6). Add v61-to-v62 coverage for guide/logo defaults.
- EPG >500 reactive updates, offset-200 library pages, TIF tune ordering, guide focus after append, and stale provider collectors have no direct regression coverage.

---

## Bottom line

Top five expected fleet-wide performance wins:

1. **H2:** Remove duplicate FTS maintenance during large catalog sync.
2. **H3:** Bound catalog staging memory for oversized VOD/series providers.
3. **H4/H5:** Bound and page EPG/XMLTV work before it can overwhelm low-RAM devices.
4. **H6:** Reserve network/CPU capacity for playback over background sync and image work.
5. **H1/H7:** Remove launch-critical import blocking and reduce TIF full-catalog work on TV.

Fix before broad performance work:

- **B1:** DASH timeshift can grow until memory/storage exhaustion.
- **B2:** Timeshift cancellation can overwrite a valid replacement session with FAILED state.
- No other bug is statically confirmed as direct persistent user-data loss or an immediate crash. H5 and B1 remain resource-exhaustion risks and should receive low-RAM/low-storage validation early.

Changes to H6, M2, M3, or B12 can affect playback pipeline behavior and require the existing long live-playback validation protocol after implementation.
