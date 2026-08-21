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

| Module / Component | Verification Command | Result |
|---|---|---|
| `:player` unit tests | `./gradlew :player:testDebugUnitTest` | **PASSED** |
| `:data` unit tests | `./gradlew :data:testDebugUnitTest` | **PASSED** |
| `:app` unit tests | `./gradlew :app:testDebugUnitTest` | **PASSED** |
| FFmpeg AAR validation | `./gradlew :player:verifyLocalFfmpegArtifact` | **PASSED** |
| Release build compilation | `./gradlew :app:assembleRelease` | **PASSED** |
| Debug build compilation | `./gradlew :app:assembleDebug` | **PASSED** |
| Room migration v62 -> v63 | `StreamVaultDatabaseMigrationTest` | **PASSED** |
