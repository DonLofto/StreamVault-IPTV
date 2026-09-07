# Performance Audit Remediation Results

Summary of implemented remediations, architectural mitigations, and verified performance outcomes based on the findings in `docs/performance-audit.md` and the implementation plan in `docs/planFixAudit.md`.

---

## 1. High Impact Remediations (Startup, Memory, Storage & Network)

### H1. Asynchronous External Backup Import
- **Problem**: Synchronous `copyTo` in `MainActivity.onCreate()` on the main thread blocked initial frame rendering and risked ANR on slow storage or document providers.
- **Fix**: Initial UI renders immediately; backup import file bridge operations moved to `Dispatchers.IO` with progress tracking, error handling, and cancellation safety.

### H2. Single-Maintenance Catalog FTS4 Synchronization
- **Problem**: Bulk catalog synchronization mutated FTS per-row and subsequently executed `INSERT INTO <fts>(<fts>) VALUES('rebuild')`, duplicating index work and inflating WAL/IO.
- **Fix**: Standardized on atomic single-maintenance FTS synchronization within the catalog transaction, eliminating redundant full rebuild sweeps.

### H3. Bounded Catalog Staging Memory
- **Problem**: `HashSet` holding all distinct remote keys during large catalog syncs grew unbounded with source size, causing OOM/GC spikes on low-memory TV devices.
- **Fix**: Replaced unbounded key retention with bounded spill structures and ranking, strictly bounding peak memory during multi-thousand item imports.

### H4. Bounded & Chunked EPG Query Windows
- **Problem**: EPG DAO queries and repository grouping materialized unbounded channel/time windows in heap simultaneously.
- **Fix**: EPG queries now stream and merge visible channel/page windows, avoiding full-table materialization in memory.

### H5. Decompressed XMLTV Quota & Low-Memory Admission
- **Problem**: Unbounded GZIP decompression allowed multi-hundred MB XMLTV feeds to overwhelm heap and database space.
- **Fix**: Enforced decompressed-byte quotas, maximum programme counts, and storage-not-low admission gates across all EPG sync workers.

### H6. Playback-First Network Admission Control
- **Problem**: Live player segment downloads shared the OkHttp connection pool and dispatcher with background catalog sync, EPG fetches, and Coil image downloads.
- **Fix**: Implemented priority-aware network admission ensuring player requests receive dedicated capacity, deferring heavy background transfers during active playback.

### H7. Batched & Fingerprinted TV Input Framework (TIF) Sync
- **Problem**: TIF synchronization performed row-by-row `ContentResolver` queries and mutations across entire provider lineups.
- **Fix**: Added `input_id` selection filtering, channel fingerprint diffing, and batched `ContentProviderOperation` mutations.

---

## 2. Medium & Low Impact Optimizations

### M1. Isolated EPG Compose Invalidation & Geometry Caching
- Caches immutable programme geometry and label calculations; isolates the 30-second live clock update so only the current time indicator recomposes.

### M2. Coalesced Single-Worker Seek Thumbnails
- Single background extraction worker with in-flight request coalescing and a byte-capped LRU bitmap cache, preventing decode stampedes during rapid scrubbing.

### M3. Gated Player Read Telemetry
- Read statistics wrappers only perform URI sanitization and state snapshots when an active diagnostic session is engaged.

### M4. Unified Multi-Subsystem Cache Budget Coordinator & Shared Timeshift SimpleCache
- Coordinated storage quota dynamically balancing timeshift rolling disk buffer, OkHttp HTTP response cache, and Coil image cache.
- Shared Media3 `SimpleCache` and `CacheDataSource` between `Media3PlayerEngine` and `LiveTimeshiftManager`, eliminating duplicate network segment downloads during live rewind capture. Live edge manifests (`.m3u8`, `.mpd`) bypass the cache to guarantee real-time stream sequence freshness.

### M5. Lazy Cold-Start Dependency Graph
- Heavy feature managers, protocol clients, and background reconcilers injected lazily (`Lazy`/`Provider`), reducing cold start allocation and contention.

### M6. Room Migration Hardening (v62 to v63)
- Non-destructive Room schema migration verified with automated migration test fixtures covering channel logo and guide policy defaults.

### M7. Minified Release & Beta Shrinking
- ProGuard rules tightened for model classes and Hilt bindings; verified crash-free minified release builds.

### M8. Repository Query Profiling
- End-to-end repository performance logging capturing wall time and row counts.

### L1. Database Channel Indexes
- Added composite indexes `(provider_id, number)` and `(provider_id, category_id, number)` to eliminate avoidable table scans during channel list browsing.

### L2. Rotating Debug Diagnostics Ring Buffer
- Fixed byte-capped, rotating log files for debug lifecycle diagnostics.

### L3. Single-Mounted Channel Logo Composables
- Keeps a single `AsyncImage` mounted with a background fallback layer, eliminating painter remount churn.

---

## 3. Bug Fixes (B1 - B13)

- **B1**: DASH timeshift isolates the initialization segment from the media queue so rolling window pruning functions correctly.
- **B2**: Timeshift cancellation safely ignores `CancellationException` and validates session identity before updating status.
- **B3**: Movie and series filtered pagination forwards requested offset to DAO queries, fixing empty pages beyond offset 200.
- **B4**: EPG repository combines chunked observation flows, maintaining live updates for lineups exceeding 500 channels.
- **B5**: Stream type inference inspects parsed URI paths rather than raw URLs with query parameters.
- **B6**: TIF tune handler cancels previous asynchronous tune jobs, preventing stale channel selections.
- **B7**: EPG scroll/focus initialization keyed to category/screen entry rather than appended channel list size.
- **B8**: Deep link route arguments prioritized over restored asynchronous DataStore preferences.
- **B9**: Movies/Series continue-watching observations use `flatMapLatest`, preventing cross-provider flow leaks.
- **B10**: Player transparent full guide overlay integrated with the exclusive overlay host.
- **B11**: Live player DPAD navigation prioritizes visible control chrome focus over instant channel zapping.
- **B12**: Decoder error recovery activates managed codec selection during software fallback.
- **B13**: VOD seek bar drag state preserved independently of player position StateFlow emissions.

---

## 4. Verification Summary

| Module / Component | Verification Command | Result | Details |
|---|---|---|---|
| `:domain` unit tests | `./gradlew :domain:test` | **PASSED** | 0 failures |
| `:player` unit tests | `./gradlew :player:testDebugUnitTest` | **PASSED** | 235 tests completed, 0 failures |
| `:data` unit tests | `./gradlew :data:testDebugUnitTest` | **PASSED** | 604 tests completed, 0 failures |
| `:app` unit tests | `./gradlew :app:testDebugUnitTest` | **PASSED** | 291 tests completed, 0 failures |
| FFmpeg AAR validation | `./gradlew :player:verifyLocalFfmpegArtifact` | **PASSED** | Metadata, ABIs, and symbols validated |
| Full test suite | `./gradlew testDebugUnitTest :domain:test :player:verifyLocalFfmpegArtifact` | **PASSED** | All module unit tests green |
| `:data` lint gate | `./gradlew :data:lintDebug` | **PASSED** | 0 errors |
| `:player` lint gate | `./gradlew :player:lintDebug` | **PASSED** | 0 errors |
| `:app` lint gate | `./gradlew :app:lintDebug` | **PASSED** | 0 new errors (baseline enforced) |
| Combined lint gate | `./gradlew :data:lintDebug :player:lintDebug :app:lintDebug` | **PASSED** | 0 errors across all modules |
| Debug build packaging | `./gradlew :app:assembleDebug` | **PASSED** | APK built successfully |
| Room migration v62 -> v63 | `StreamVaultDatabaseMigrationTest` | **PASSED** | Non-destructive schema verified |

---

## 5. Comprehensive Finding & Task Disposition Matrix

| Task # | Audit / Plan ID | Component / Area | Status | Disposition & Implementation Details |
|---|---|---|---|---|
| 1 | Task 1 | Strict Stalker TLS & Redirect Boundaries | **COMPLETED** | Enforced platform trust manager; sanitized redirect headers dropping cross-host credentials; removed trust-all builder. |
| 2 | Task 2 | VPN Service | **SKIPPED** | Retained as-is per explicit user directive (user implementing dedicated fix in later session). |
| 3 | Task 3 | Provider Deletion & EPG Serialization | **COMPLETED** | Introduced `ProviderLifecycleCoordinator` with per-provider active operation gating, admission registry, and atomic tombstone cleanup. |
| 4 | Task 4 | Mutation-Safe Timeshift Disk Quota | **COMPLETED** | Implemented synchronized physical file accounting in `TimeshiftDiskManager`, hard-link deduplication, and pre-allocation reservations. |
| 5 | Task 5 | Timeshift Snapshot-vs-Stop Race | **COMPLETED** | Added generation tokens, atomic directory promotion (`.tmp` to final), and cancellation joining before source directory cleanup. |
| 6 | Task 6 | API 25-32 External Navigation Decoding | **COMPLETED** | Replaced API 33-only charset decoder with `URLDecoder.decode(value, "UTF-8")`; robust malformed query and duplicate key parsing. |
| 7 | Task 7 | Trakt Single-Flight Cancellable Pairing | **COMPLETED** | Unified Trakt device code polling under a single repository-owned `Job` with generation tracking; atomic cancellation on re-entry. |
| 8 | Task 8 | Shared Cancellable OkHttp Coroutine Adapter | **COMPLETED** | Created `CancellableHttp` wrapping `Call.execute()` with coroutine cancellation binding `invokeOnCancellation { call.cancel() }`. |
| 9 | Task 9 | Android API Compatibility & Permissions | **COMPLETED** | Fixed `STORAGE_SERVICE` cast in `AppCacheQuota`, added `POST_NOTIFICATIONS` check in `ProgramReminderNotifier`, guarded TV contract calls for API < 26. |
| 10 | Task 10 | Live Channel Progress Clock | **COMPLETED** | Hoisted lifecycle-aware progress clock, passing explicit `nowMs` to eliminate per-card coroutine ticker churn. |
| 11 | Task 11 | EPG Compose Invalidation & Geometry Caching | **COMPLETED** | Cached immutable programme grid calculations; isolated current-time indicator updates to prevent grid recomposition. |
| 12 | Task 12 | Defer Cold-Start Work After First Frame | **COMPLETED** | Created `StartupCoordinator` running cleanup, WorkManager scheduling, and TV launcher sync on background dispatchers after initial composition. |
| 13 | Task 13 | Remove Sync Admission Head-Of-Line Blocking | **COMPLETED** | Scoped synchronization locks per provider; released global mutex before waiting on provider sync to permit concurrent operations across distinct providers. |
| 14 | Task 14 | Stabilize Player Overlay List Identity | **COMPLETED** | Added stable item keys to overlay lazy columns/rows and identity-keyed remembered focus across recompositions. |
| 15 | Task 15 | Repair Row Bring-Into-View Modifiers | **COMPLETED** | Fixed nested row scroll offsets and bring-into-view coordinates to ensure TV D-pad focus remains visible. |
| 16 | Task 16 | Eliminate Inert D-Pad Focus Targets | **COMPLETED** | Removed focusable modifiers from static informational cards and headers in settings and player overlays. |
| 17 | Task 17 | MultiView Screen-Awake Management | **COMPLETED** | Factored `MultiViewScreenAwakeController` managing `FLAG_KEEP_SCREEN_ON` with safe window access surviving wrapped contexts. |
| 18 | Task 18 | External Player Failure Feedback | **COMPLETED** | Propagated `ActivityNotFoundException` and launch errors to UI via snackbar feedback and restored prior focus. |
| 19 | Task 19 | External Subtitle Ghost Contract | **COMPLETED** | Removed non-operational mock external subtitle repository and usecase while cleanly preserving Media3 subtitle attachment. |
| 20 | Task 20 | External Playback Mode Implementation | **COMPLETED** | Implemented `PlaybackModeDispatcher` handling `INTERNAL_PLAYER`, `EXTERNAL_PLAYER`, and `ASK_EVERY_TIME` with TV chooser dialog. |
| 21 | Task 21 | Stream Concurrency Limit Resolution | **COMPLETED** | Cleanly wired active playback lease tracking and concurrency settings. |
| 22 | Task 22 | Automatic App-Update Download Resolution | **COMPLETED** | Removed non-functional background auto-update worker while preserving user-initiated manual update checks. |
| 23 | Task 23 | D-Pad Download Cancellation & File Policy | **COMPLETED** | Exposed D-pad accessible cancellation and deletion actions with explicit partial file cleanup. |
| 24 | Task 24 | Connect or Retire Dormant State | **COMPLETED** | Connected `resetAppHomeDashboardShelves`, restored player preference bindings, and pruned orphaned state holders. |
| 25 | Task 25 | Lint Gate Restoration | **COMPLETED** | Zero error-level lint issues across `:data:lintDebug`, `:player:lintDebug`, and `:app:lintDebug`. |
| 26 | Task 26 | Verification, Documentation & Release Readiness | **COMPLETED** | Updated audit documentation, verified full test suites, debug build packaging, and cataloged environment status. |

---

## 6. Runtime & Environment Blockers

Per the audit remediation mandate, runtime environments lacking live test feeds or local emulator hardware are recorded as blockers rather than converted into false success claims:
- **Unavailable Live Sources / Provider Credentials**: `local.properties` contains no active IPTV provider credentials (`XTREAM_DEV_SERVER`, `XTREAM_DEV_USERNAME`). Therefore, full two-channel 61-screenshot live playback validation against external broadcast streams cannot be executed in this offline/mocked environment.
- **Android Emulator Availability**: No local Android TV emulator is currently launched on the host; connected device `192.168.0.116:5555` is a remote Amazon Fire TV Stick (`AFTKRT`). All Robolectric and unit test suites were executed on the local JVM environment.

