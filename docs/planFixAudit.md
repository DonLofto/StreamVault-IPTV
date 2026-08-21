# Performance Audit Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Fix every finding in `docs/performance-audit.md` while preserving current behavior unless an explicit capacity, scheduling, or refresh policy changes it.

**Architecture:** Deliver independent, testable workstreams in risk order: timeshift correctness and resource limits; catalog/EPG data paths; player/UI behavior; network/startup/platform work; then package/test hardening. Use bounded state, cancellation ownership, immutable UI state, and background admission control rather than broad rewrites.

**Tech Stack:** Kotlin, Coroutines/Flow, Jetpack Compose, Media3, Room, Hilt, WorkManager, OkHttp, Coil, Android TV TIF.

## Global Constraints

- Read `docs/performance-audit.md` before every task; task labels map to its finding IDs.
- Keep `docs/buffering-investigation.md` fixes separate. Do not regress player preparation, live retry, or token-renewal work already under investigation.
- Add a focused regression test before each behavior fix. Run module tests after every task.
- For player/network/render changes, follow full `AGENTS.md` live-TV validation: `ROTATION_270`, 61 screenshots at 2-second cadence, changing hashes, media session `PLAYING` with `error=null`, and sanitized HLS log evidence on at least two live channels.
- Preserve Room data. Schema changes require a migration, exported schema, and migration test from v62.
- Preserve SurfaceView as default AUTO render surface. Do not widen TextureView usage.
- Do not commit secrets, `.env` files, generated profile captures, or device logs.
- Do not change user-visible capacity/freshness behavior without an explicit setting, message, or release-note entry.

---

## Workstream Order

1. Timeshift correctness and bounded storage: B1, B2, B5, M4.
2. Catalog/Room correctness and memory: H2, H3, M6, L1, M8.
3. EPG data and Compose: H4, H5, M1, B4, B7, B8.
4. Library paging and provider-state isolation: B3, B9.
5. Player decoder/telemetry correctness: B12, M3.
6. Background network admission: H6.
7. Startup and TIF: H1, H7, M5, B6.
8. Player UI, thumbnails, and images: M2, L3, B10, B11, B13.
9. Diagnostics, packaging, and test hardening: L2, M7, audit test observations.
10. Fleet profiling and release acceptance: every performance finding.

---

### Task 1: Repair Timeshift Lifecycle, DASH Pruning, URL Detection, and Storage Budget

**Audit IDs:** B1, B2, B5, M4.

**Files:**
- Modify: `player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt:200-353, 830-942`
- Modify: `player/src/main/java/com/streamvault/player/timeshift/TimeshiftDiskManager.kt:15-66`
- Modify: `app/src/main/java/com/streamvault/app/di/NetworkModule.kt:64-70`
- Modify: `app/src/main/java/com/streamvault/app/StreamVaultApp.kt:135-159`
- Create: `player/src/test/java/com/streamvault/player/timeshift/LiveTimeshiftManagerTest.kt`
- Modify: `player/src/test/java/com/streamvault/player/timeshift/LiveTimeshiftBackendSelectionTest.kt`

**Interfaces:**
- Consumes `TimeshiftConfig`, `LiveTimeshiftState`, `LiveTimeshiftSnapshot`, and `TimeshiftDiskManager`.
- Produces cancellation-safe session state, a bounded DASH rolling window, URI-path stream inference, and a shared cache-space admission calculation.

- [ ] **Step 1: Add failing timeshift regressions.**

```kotlin
@Test
fun dashPruning_keepsInitButEvictsOldMediaSegments() = runTest {
    val window = DashWindow(depthMs = 10_000)
    window.addInit(initSegment)
    window.addMedia(segment(durationMs = 6_000))
    window.addMedia(segment(durationMs = 6_000))

    assertThat(window.mediaSegments()).containsExactly(secondSegment)
    assertThat(window.initSegment()).isEqualTo(initSegment)
}

@Test
fun cancelledSession_doesNotPublishFailedAfterStop() = runTest {
    val manager = managerWithSuspendingCapture()
    manager.startSession(streamInfo, config)
    manager.stopSession()

    assertThat(manager.state.value.status).isEqualTo(LiveTimeshiftStatus.DISABLED)
}

@Test
fun inferType_usesUriPathWhenUrlContainsQuery() {
    assertThat(inferTimeshiftStreamType("https://host/live.m3u8?token=x"))
        .isEqualTo(StreamType.HLS)
}
```

- [ ] **Step 2: Run player timeshift tests and confirm the new regressions fail.**

Run: `./gradlew :player:testDebugUnitTest --tests '*LiveTimeshiftManagerTest' --tests '*LiveTimeshiftBackendSelectionTest'`

Expected: B1/B2/B5 assertions fail before production changes.

- [ ] **Step 3: Separate DASH init state from media queue.**

```kotlin
private var initSegment: HlsSegmentSnapshot? = null
private val mediaSegments = ArrayDeque<HlsSegmentSnapshot>()

private fun pruneMediaSegmentsLocked() {
    while (runningSegmentDurationMs > effectiveDepthMs && mediaSegments.isNotEmpty()) {
        val removed = mediaSegments.removeFirst()
        seenSegments.remove(removed.remoteUrl)
        removed.file?.delete()
        runningSegmentDurationMs -= removed.durationMs
    }
}
```

Keep init bytes/file only once. Build snapshots from `listOfNotNull(initSegment) + mediaSegments`. Never add zero-duration init data to the duration/prune queue.

- [ ] **Step 4: Make cancellation non-terminal and session-owned.**

```kotlin
session.job = scope.launch {
    try {
        session.capture()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        if (activeSession === session) publishFailure(session, error)
    }
}
```

Cancel active capture before deletion. Await an external capture job before removing its files; never join the current job from itself. Only the active session may publish state.

- [ ] **Step 5: Parse type from URI path.**

```kotlin
internal fun inferTimeshiftStreamType(url: String): StreamType {
    val path = Uri.parse(url).path.orEmpty().lowercase(Locale.ROOT)
    return when {
        path.endsWith(".m3u8") -> StreamType.HLS
        path.endsWith(".mpd") -> StreamType.DASH
        else -> StreamType.PROGRESSIVE
    }
}
```

Retain existing SmoothStreaming, MPEG-TS, and RTSP checks using parsed path/scheme.

- [ ] **Step 6: Introduce one cache-space admission source.**

Compute an app cache quota from `StorageStatsManager` when available, falling back to `cacheDir.usableSpace`. Reserve explicit headroom before timeshift starts. Cap HTTP, Coil, and timeshift budgets from that quota rather than independent maxima. Do not delete active timeshift data.

- [ ] **Step 7: Run tests.**

Run: `./gradlew :player:testDebugUnitTest`

Expected: all player unit tests pass, including new B1/B2/B5 regressions.

- [ ] **Step 8: Validate storage behavior on Tier A.**

Enable disk timeshift, exceed configured depth on DASH, create/release snapshots, then confirm bounded files/bytes and no FAILED state after stop/channel change. Record cache directory size before/after.

---

### Task 2: Remove Duplicate FTS Work, Bound Catalog Staging, and Harden Room Migrations

**Audit IDs:** H2, H3, M6, L1, M8.

**Files:**
- Modify: `data/src/main/java/com/streamvault/data/sync/SyncCatalogStore.kt:75-297, 701-753`
- Modify: `data/src/main/java/com/streamvault/data/local/dao/CatalogSyncDao.kt:851-860`
- Modify: `data/src/main/java/com/streamvault/data/local/StreamVaultDatabase.kt`
- Modify: `app/src/main/java/com/streamvault/app/di/DatabaseModule.kt:23-109`
- Modify: `data/src/main/java/com/streamvault/data/local/dao/Daos.kt:96-196`
- Modify: `data/src/main/java/com/streamvault/data/local/entity/Entities.kt:90-95`
- Modify: `data/src/androidTest/java/com/streamvault/data/local/StreamVaultDatabaseMigrationTest.kt`
- Create: `data/src/test/java/com/streamvault/data/sync/SyncCatalogStoreMemoryTest.kt`
- Create: `data/src/test/java/com/streamvault/data/local/ChannelBrowseQueryPlanTest.kt`

**Interfaces:**
- Consumes Room FTS content entities, staged catalog tables, `CatalogSizeLimits`, and channel browse DAO contracts.
- Produces one FTS maintenance path, bounded catalog dedupe, v62-to-v63 migration, and query-plan coverage.

- [ ] **Step 1: Add FTS parity and bounded-staging tests.**

```kotlin
@Test
fun replaceMovieCatalog_updatesFtsWithoutExplicitRebuild() = runTest {
    store.replaceMovieCatalog(providerId, categories, sequenceOf(movie))
    assertThat(searchDao.searchMovies(providerId, "movie").first()).isNotEmpty()
}

@Test
fun stageDistinctRows_doesNotRetainEveryRemoteKeyInHeap() = runTest {
    val result = stageLargeSyntheticCatalog(totalRows = 250_000, limit = 200_000)
    assertThat(result.acceptedCount).isEqualTo(200_000)
    assertThat(result.peakInMemoryKeys).isAtMost(result.boundedKeyCapacity)
}
```

Use an injected staging abstraction/metric in the test; do not rely on JVM heap timing.

- [ ] **Step 2: Keep Room content synchronization and remove redundant explicit rebuild calls.**

Delete `rebuildChannelFts`, `rebuildMovieFts`, and `rebuildSeriesFts` calls from catalog transactions after proving `@Fts4(contentEntity = ...)` handles insert/update/delete parity. Keep rollback atomicity by leaving catalog mutation transaction unchanged. Remove DAO functions only after all callers are gone.

- [ ] **Step 3: Replace unbounded `HashSet` dedupe with database-backed staging.**

Add a unique provider/session/remote-key staging index. Insert candidate rows in batches with conflict-ignore semantics; retain ranking with a bounded SQL query ordered by existing priority fields. Do not change current selected-row ordering or overflow upsert-only behavior.

```kotlin
INSERT OR IGNORE INTO movie_stage (..., remote_key, priority)
VALUES (...)

SELECT ... FROM movie_stage
WHERE provider_id = :providerId AND session_id = :sessionId
ORDER BY priority ASC
LIMIT :limit
```

- [ ] **Step 4: Add channel ordering indexes in a v62-to-v63 migration.**

```sql
CREATE INDEX IF NOT EXISTS index_channels_provider_number
ON channels(provider_id, number);

CREATE INDEX IF NOT EXISTS index_channels_provider_category_number
ON channels(provider_id, category_id, number);
```

Increment database version, export schema 63, register `MIGRATION_62_63`, and test migration from v62. Keep existing non-destructive migration policy.

- [ ] **Step 5: Add sampled end-to-end repository timing in debug/performance builds.**

Keep `SlowQueryLoggingOpenHelperFactory` for SQL setup. Add a small injected reporter around list/EPG repository mapping that records query label, row count, cursor-to-domain elapsed time, and total Flow transformation elapsed time. Disable in normal release builds.

- [ ] **Step 6: Run tests and query-plan checks.**

Run: `./gradlew :data:testDebugUnitTest`

Run: `./gradlew :data:connectedDebugAndroidTest --tests '*StreamVaultDatabaseMigrationTest'`

Expected: FTS parity, bounded staging, v62-to-v63 migration, and both channel ordering plans pass.

---

### Task 3: Make EPG Bounded, Reactive, Deterministic, and Cheap to Recompose

**Audit IDs:** H4, H5, M1, B4, B7, B8.

**Files:**
- Modify: `data/src/main/java/com/streamvault/data/repository/EpgRepositoryImpl.kt:106-162, 304-339`
- Modify: `data/src/main/java/com/streamvault/data/repository/EpgSourceRepositoryImpl.kt:316-372`
- Modify: `data/src/main/java/com/streamvault/data/parser/XmltvParser.kt:539-547`
- Modify: `data/src/main/java/com/streamvault/data/epg/EpgResolutionEngine.kt:64-119, 262-360`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/epg/EpgViewModel.kt:282-286, 319-329, 1475-1495`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/epg/EpgGridComponents.kt:115-228, 327-574`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/epg/EpgControlComponents.kt:80-95`
- Modify: `data/src/test/java/com/streamvault/data/repository/EpgRepositoryImplTest.kt`
- Modify: `data/src/test/java/com/streamvault/data/parser/XmltvParserTest.kt`
- Create: `app/src/androidTest/java/com/streamvault/app/ui/screens/epg/EpgGridBehaviorTest.kt`

**Interfaces:**
- Consumes `Flow<Map<String, List<Program>>>`, guide route arguments, saved guide preferences, and XMLTV input streams.
- Produces reactive multi-chunk EPG results, bounded import input, deterministic route precedence, and stable guide focus/geometry.

- [ ] **Step 1: Add failing multi-chunk Flow and XMLTV-limit tests.**

```kotlin
@Test
fun programsForMoreThan500Channels_reemitsAfterRoomInvalidation() = runTest {
    val values = repository.getProgramsForChannels(providerId, channelIds(501), start, end).take(2).toList()
    insertProgram(channelIds(500))
    assertThat(values).hasSize(2)
}

@Test
fun compressedXmltv_exceedingExpandedLimit_returnsControlledFailure() = runTest {
    assertThat(parse(gzipExpandingBeyondLimit())).isFailure()
}
```

Assert exact two retained programmes in malformed-XML partial-result test; remove permissive `0..2` assertion.

- [ ] **Step 2: Replace one-shot large-channel snapshot with merged Room flows.**

```kotlin
return preferencesRepository.epgTimeShiftMinutes(providerId).flatMapLatest { minutes ->
    combine(chunks.map { ids ->
        programDao.getForChannels(providerId, ids, shiftedStart(minutes), shiftedEnd(minutes))
    }) { chunkRows ->
        chunkRows.asSequence().flatten().map(::toShiftedProgram).groupBy(Program::channelId)
    }
}
```

Limit each request to current EPG page and visible time window. Stream resolved chunks into the output map instead of flattening a whole provider result.

- [ ] **Step 3: Add controlled XMLTV admission.**

Pass a decompressed-byte limit and programme limit to parser input. Reject over-limit feeds with a typed, user-safe error. Require free-space and low-memory admission before staging. Use one shared policy for worker and inline stale-EPG paths.

- [ ] **Step 4: Define and implement route precedence.**

Route arguments win when supplied; saved preferences fill only missing route fields. Represent optional route booleans with nullable state so explicit `false` differs from absent input. Wait for one restore result before applying defaults, then prevent late preference writes from replacing route values.

- [ ] **Step 5: Stop pagination from reinitializing focus.**

```kotlin
LaunchedEffect(guideSessionKey, resolvedInitialChannelId) {
    verticalListState.scrollToItem(initialFocusIndex.coerceAtLeast(0))
    initialFocusRequester.requestFocus()
}
```

Use a guide-session/category key, not `channels.size`. Keep `onRequestMoreChannels()` independent from initial focus state.

- [ ] **Step 6: Reduce guide tick fan-out without changing layout semantics.**

Cache programme start/end labels by programme ID and time format. Compute current programme once per row. Keep only current-state colors/progress dependent on guide clock. Do not introduce horizontal virtualization until D-pad focus behavior has an instrumentation baseline.

- [ ] **Step 7: Run tests.**

Run: `./gradlew :data:testDebugUnitTest --tests '*EpgRepositoryImplTest' --tests '*XmltvParserTest'`

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*EpgGridBehaviorTest'`

Expected: 501-channel update re-emits, input limits fail safely, deep link wins, and page append preserves focus/scroll.

---

### Task 4: Fix Library Paging and Provider-Scoped Continue Watching

**Audit IDs:** B3, B9.

**Files:**
- Modify: `data/src/main/java/com/streamvault/data/repository/MovieRepositoryImpl.kt:830-946, 1030-1110`
- Modify: `data/src/main/java/com/streamvault/data/repository/SeriesRepositoryImpl.kt:722-724, 988-1095`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/movies/MoviesViewModel.kt:376-397`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/series/SeriesViewModel.kt:380-401`
- Modify: `data/src/test/java/com/streamvault/data/repository/MovieRepositoryImplTest.kt`
- Modify: `data/src/test/java/com/streamvault/data/repository/SeriesRepositoryImplTest.kt`
- Create: `app/src/test/java/com/streamvault/app/ui/screens/library/ContinueWatchingProviderSwitchTest.kt`

- [ ] **Step 1: Add offset-200 paging regressions.**

```kotlin
@Test
fun favoritesPage_afterFirst200_returnsRequestedRows() = runTest {
    seedFavorites(220)
    val page = repository.browseMovies(query(filter = FAVORITES, offset = 200, limit = 20))
    assertThat(page.items.map { it.id }).containsExactlyElementsIn(ids(201..220))
}
```

Add matching movie/series cases for favorite, unwatched, and in-progress paths.

- [ ] **Step 2: Pass source offset when SQL path can honor it.**

Use `query.offset` in DAO page calls. For branches requiring in-memory duplicate/history filtering, fetch enough ordered windows to fill `offset + limit`, then stop; never cap source result at 200 before applying `drop(offset)`.

- [ ] **Step 3: Replace nested continue-watching child launches with one provider switch Flow.**

```kotlin
providerRepository.getActiveProvider()
    .filterNotNull()
    .distinctUntilChangedBy { it.id }
    .flatMapLatest { provider -> getContinueWatching(provider.id, 20, ContinueWatchingScope.MOVIES) }
    .onEach(::publishContinueWatching)
    .launchIn(viewModelScope)
```

Mirror for Series. Ensure provider A cannot update state after provider B becomes active.

- [ ] **Step 4: Run tests.**

Run: `./gradlew :data:testDebugUnitTest --tests '*MovieRepositoryImplTest' --tests '*SeriesRepositoryImplTest'`

Run: `./gradlew :app:testDebugUnitTest --tests '*ContinueWatchingProviderSwitchTest'`

Expected: pages beyond 200 return data; old provider Flow emissions are ignored.

---

### Task 5: Make Decoder Recovery Effective and Gate Player Read Telemetry

**Audit IDs:** B12, M3.

**Files:**
- Modify: `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt:1216-1231, 2259-2270, 2345-2365`
- Modify: `player/src/main/java/com/streamvault/player/playback/CodecPreference.kt:17-20, 82-86`
- Modify: `player/src/main/java/com/streamvault/player/playback/PlayerDataSourceFactoryProvider.kt:69-107, 170-208`
- Modify: `player/src/main/java/com/streamvault/player/playback/PlayerDataSourceReadStats.kt`
- Modify: `player/src/main/java/com/streamvault/player/stats/PlayerStatsCollector.kt:110-179`
- Modify: `player/src/test/java/com/streamvault/player/playback/DecoderPreferencePolicyTest.kt`
- Modify: `player/src/test/java/com/streamvault/player/playback/PlayerDataSourceFactoryProviderTest.kt`
- Modify: `player/src/test/java/com/streamvault/player/playback/PlayerDataSourceReadStatsTrackerTest.kt`

- [ ] **Step 1: Add effective-policy selector tests.**

```kotlin
@Test
fun autoRequest_withSoftwareRecovery_usesManagedSoftwareSelector() {
    val selector = codecSelectorFor(requested = AUTO, effective = SOFTWARE_PREFERRED)
    assertThat(selector).isNotEqualTo(MediaCodecSelector.DEFAULT)
}
```

- [ ] **Step 2: Key selector choice on effective recovery policy.**

Keep ordinary AUTO on Media3 default hardware-preferred selection. When error recovery changes effective audio/video mode to software/compatibility, build managed selector from effective mode for that axis only.

- [ ] **Step 3: Make read telemetry opt-in or allocation-light.**

Create `PlayerReadDiagnosticsEnabled` state supplied by explicit diagnostics session. Do not wrap HLS/MPEG-TS data sources when disabled. When enabled, defer URL sanitization until a sample is emitted and compare scalar stats before allocating immutable `PlayerStats` values.

- [ ] **Step 4: Run tests and live validation.**

Run: `./gradlew :player:testDebugUnitTest`

Then use two affected live channels, complete `AGENTS.md` 61-frame validation, and record selected decoder names plus no regression to HLS first-frame/read logs.

---

### Task 6: Prioritize Playback Over Background Network Work

**Audit IDs:** H6.

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/di/NetworkModule.kt:44-119`
- Modify: `data/src/main/java/com/streamvault/data/sync/ProviderSyncWorker.kt:107-246`
- Modify: `data/src/main/java/com/streamvault/data/sync/BackgroundEpgSyncWorker.kt:32-84, 123-142`
- Modify: `data/src/main/java/com/streamvault/data/remote/stalker/StalkerTrafficCoordinator.kt`
- Modify: `data/src/main/java/com/streamvault/data/sync/SyncManager.kt`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerLifecycleActions.kt`
- Create: `data/src/main/java/com/streamvault/data/sync/PlaybackNetworkAdmissionGate.kt`
- Create: `data/src/test/java/com/streamvault/data/sync/PlaybackNetworkAdmissionGateTest.kt`

- [ ] **Step 1: Add admission-gate tests.**

```kotlin
@Test
fun activePlayback_defersBackgroundCatalogWork() = runTest {
    gate.onPlaybackStarted()
    assertThat(gate.admissionFor(BACKGROUND_CATALOG)).isEqualTo(Admission.DEFER)
    assertThat(gate.admissionFor(PLAYBACK)).isEqualTo(Admission.ALLOW)
}
```

- [ ] **Step 2: Introduce a process-scoped playback admission gate.**

Expose `onPlaybackStarted`, `onPlaybackStopped`, and `awaitBackgroundAdmission`. Wire player lifecycle start/stop to it. Reuse the same signal to activate existing Stalker traffic coordination rather than creating a second unrelated playback counter.

- [ ] **Step 3: Split clients by traffic class.**

Provide a playback client retaining current capacity and a background sync client with bounded dispatcher/host limits. Background workers await admission before large catalog/EPG calls and return `Result.retry()` when deferral exceeds the allowed window. Keep authentication, TLS, cache, and timeouts consistent unless a documented policy intentionally differs.

- [ ] **Step 4: Add cancellation binding for blocking OkHttp work.**

Use a suspend call adapter that calls `Call.cancel()` when coroutine/WorkManager cancellation occurs. Apply it to EPG and Stalker paths, matching existing Xtream cancellation behavior.

- [ ] **Step 5: Run tests and full playback validation.**

Run: `./gradlew :data:testDebugUnitTest :app:testDebugUnitTest`

Validate active playback plus forced provider/EPG sync on constrained Wi-Fi. Confirm background work defers, player stays healthy, and deferred work resumes after stop.

---

### Task 7: Move Startup-Critical Work Off Main and Make TIF Tuning/Sync Deterministic

**Audit IDs:** H1, H7, M5, B6.

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/MainActivity.kt:116-141, 342-407`
- Modify: `app/src/main/java/com/streamvault/app/backup/BackupFileBridge.kt:45-54`
- Modify: `app/src/main/java/com/streamvault/app/StreamVaultApp.kt:38-97`
- Modify: `app/src/main/java/com/streamvault/app/tvinput/TvInputChannelSyncManager.kt:43-206, 293-296`
- Modify: `app/src/main/java/com/streamvault/app/tvinput/StreamVaultTvInputService.kt:50, 69-118, 185-204`
- Modify: `data/src/main/java/com/streamvault/data/manager/recording/RecordingReconcileWorker.kt:45-60`
- Create: `app/src/test/java/com/streamvault/app/tvinput/StreamVaultTvInputServiceTest.kt`
- Modify: `app/src/test/java/com/streamvault/app/tvinput/TvInputChannelSyncManagerTest.kt`

- [ ] **Step 1: Add main-thread import and latest-tune regressions.**

```kotlin
@Test
fun newerTuneWinsWhenOlderResolutionFinishesLast() = runTest {
    session.onTune(channelA)
    session.onTune(channelB)
    completeResolution(channelB)
    completeResolution(channelA)

    assertThat(player.lastMediaItem).isEqualTo(channelB)
}
```

- [ ] **Step 2: Make import copying asynchronous after first composition.**

Store pending external URI during intent parsing. Render initial content first, then launch import copy on `Dispatchers.IO`, expose progress/error state, and navigate only after successful copy. Preserve URI permission handling and process recreation behavior.

- [ ] **Step 3: Give TIF sessions a tune generation and bounded client cache.**

```kotlin
private var tuneGeneration = 0L
private var tuneJob: Job? = null

override fun onTune(uri: Uri): Boolean {
    val generation = ++tuneGeneration
    tuneJob?.cancel()
    tuneJob = scope.launch { tune(uri, generation) }
    return true
}
```

Check generation after each suspend point before applying player state. Replace unbounded `playbackClients` map with an LRU keyed by proxy configuration and clear it when session/service releases.

- [ ] **Step 4: Batch TIF synchronization.**

Query existing channels with `input_id` selection, calculate a fingerprint for channel/program content, skip unchanged rows, and apply inserts/updates/deletes through `ContentProviderOperation.applyBatch`. Bound channel/program batches to avoid binder transaction limits.

- [ ] **Step 5: Defer non-urgent startup work behind first interaction.**

Keep crash reporting and required lifecycle setup immediate. Make diagnostics/image/cast/protocol dependencies lazy where no startup side effect exists. Coalesce launch stale checks and one-shot record reconciliation behind a post-first-frame scheduler. Document changed freshness/recovery timing.

- [ ] **Step 6: Run tests and startup benchmarks.**

Run: `./gradlew :app:testDebugUnitTest`

Measure cold launch with no intent, slow external import, and a 5k-channel TIF catalog on Tier A. Confirm first frame precedes copy/sync work.

---

### Task 8: Repair Player Overlay Input, Scrub State, Thumbnail Work, and Logo Composition

**Audit IDs:** M2, L3, B10, B11, B13.

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt:569-622, 1212-1309`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerOverlayActions.kt:47-75`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerControlsChrome.kt:1121-1133, 1197-1213, 1292-1314, 1851-1854`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/SeekThumbnailProvider.kt:45-88`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerPlaybackPreferenceActions.kt:314-358`
- Modify: `app/src/main/java/com/streamvault/app/ui/components/ChannelLogo.kt:49-80`
- Create: `app/src/test/java/com/streamvault/app/ui/screens/player/PlayerOverlayBehaviorTest.kt`
- Create: `app/src/test/java/com/streamvault/app/ui/screens/player/SeekThumbnailProviderTest.kt`

- [ ] **Step 1: Add overlay/input/slider tests.**

```kotlin
@Test
fun dpadDown_withControlsVisible_movesFocusWithoutZapping() { /* Compose key test */ }

@Test
fun playbackTick_duringDrag_doesNotResetSliderValue() { /* state reducer test */ }

@Test
fun fullGuideAction_rendersExclusiveTransparentGuide() { /* Compose semantics test */ }
```

- [ ] **Step 2: Render full guide from one exclusive overlay state.**

Collect `showFullGuideOverlay`, render `PlayerTransparentGuideOverlay`, pass `onOpenFullGuide` from `EpgOverlay`, and close channel/category/EPG panels before opening it. Restore focus to invoking control on close.

- [ ] **Step 3: Let child controls own DPAD navigation.**

When controls are visible, root preview handler returns false for DPAD Up/Down. Preserve root zap behavior only when no focusable control/overlay is visible.

- [ ] **Step 4: Decouple drag state from playback position.**

Hold `dragPositionMs` and `isDragging` in state keyed by media session/content ID. Apply `currentPosition` only when not dragging; commit user seek once on release.

- [ ] **Step 5: Bound thumbnail extraction.**

Maintain one in-flight request per `(url, bucket)`, cancel obsolete queued jobs, execute at most one retriever per provider, and use byte-bounded bitmap cache. Recycle/dispose raw bitmap if scaling creates a distinct bitmap. Preserve current safe null result for failed extraction.

- [ ] **Step 6: Keep channel logos mounted.**

Render one `AsyncImage` with initials/fallback beneath it, rather than swapping image call sites. Preserve error/fallback visual behavior.

- [ ] **Step 7: Run UI tests and VOD/live validation.**

Run: `./gradlew :app:testDebugUnitTest`

Run relevant Compose/instrumentation tests. Scrub 4K and remote VOD; live-validate any overlay/player lifecycle interaction under `AGENTS.md` protocol.

---

### Task 9: Bound Diagnostics and Harden Package/Test Configuration

**Audit IDs:** L2, M7, audit test observations.

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/diagnostics/RuntimeDiagnosticsManager.kt:41-172`
- Modify: `app/src/main/java/com/streamvault/app/StreamVaultApp.kt:38-64`
- Modify: `app/build.gradle.kts:67-69, 104-125`
- Modify: `app/proguard-rules.pro:12-13, 40-45, 53-55, 68-71`
- Modify: `build.gradle.kts:21-55`
- Modify: `data/build.gradle.kts:25-27`
- Modify: `app/src/androidTest/java/com/streamvault/app/tv/LauncherProviderInstrumentationTest.kt:30-32, 83-85`
- Modify: `data/src/test/java/com/streamvault/data/parser/XmltvParserTest.kt:250-271`
- Create: `app/src/test/java/com/streamvault/app/diagnostics/RuntimeDiagnosticsManagerTest.kt`

- [ ] **Step 1: Add diagnostics retention test.**

```kotlin
@Test
fun appendSnapshot_rotatesWhenByteLimitReached() {
    writeSnapshotsUntilLimit()
    assertThat(diagnosticFiles()).hasSizeAtMost(MAX_DIAGNOSTIC_FILES)
    assertThat(totalBytes()).isAtMost(MAX_DIAGNOSTIC_BYTES)
}
```

- [ ] **Step 2: Implement bounded debug diagnostic files.**

Use fixed count/byte rotation. Construct diagnostics lazily only in debug builds. Keep diagnostic event payload and interval unchanged until retention behavior is tested.

- [ ] **Step 3: Measure before changing beta/R8 rules.**

Build beta and release, archive APK/AAB size reports and R8 `usage.txt`, then enable beta shrinking if stack traces and test distribution remain acceptable. Replace broad Hilt/Gson keep rules only with minimal rules proven by minified-release tests. Do not change ARM ABI policy without a product decision.

- [ ] **Step 4: Make test signal strict.**

Remove `unitTests.isReturnDefaultValues = true` only after missing Android dependencies are supplied through fakes/Robolectric/instrumentation. Enable Kover report verification with an agreed project threshold. Run launcher-provider tests on an Android TV shard instead of treating non-TV assumptions as coverage.

- [ ] **Step 5: Run verification.**

Run: `./gradlew :app:testDebugUnitTest :data:testDebugUnitTest :player:testDebugUnitTest`

Run: `./gradlew :app:assembleRelease :app:assembleBeta`

Expected: diagnostics rotate, exact XMLTV partial-result assertion passes, minified release passes regression suite, and size delta is recorded.

---

### Task 10: Profile, Re-rank, and Release by Device Tier

**Audit IDs:** all H/M/L findings.

**Files:**
- Modify: `docs/performance-audit.md` only to append measured results after each completed workstream.
- Create: `docs/performance-audit-results.md`

- [ ] **Step 1: Define three fixtures.**

Use Tier A Fire TV Stick-class device, Tier B Android TV box, Tier C phone/tablet. Use one 5k-channel provider, one 50k+ VOD fixture, dense seven-hour XMLTV, 4K VOD, and two live HLS channels.

- [ ] **Step 2: Capture before/after metrics.**

Measure cold first-frame/interactive time, peak heap/GC, EPG Compose frames and D-pad latency, catalog/EPG transaction/WAL time, cache use, dispatcher queues, APK/AAB size, and TIF sync operation count.

- [ ] **Step 3: Run live playback acceptance after every playback-sensitive workstream.**

Use exact `AGENTS.md` orientation and 61-frame cadence. Record channel names, screenshot count, unique hash count, media-session result, HLS logs, selected decoder, and any recovery event.

- [ ] **Step 4: Update ranking from measured impact.**

Keep only improvements that meet the following gates: no behavior regression, no failed regression test, no live validation failure, and measurable Tier A improvement or clear correctness/resource-safety result.

---

## Coverage Matrix

| Audit finding | Plan task |
| --- | --- |
| H1, H7, M5, B6 | Task 7 |
| H2, H3, M6, L1, M8 | Task 2 |
| H4, H5, M1, B4, B7, B8 | Task 3 |
| H6 | Task 6 |
| M2, L3, B10, B11, B13 | Task 8 |
| M3, B12 | Task 5 |
| M4, B1, B2, B5 | Task 1 |
| M7, L2, test observations | Task 9 |
| B3, B9 | Task 4 |

## Execution Gates

1. Finish Task 1 before any shared-cache or timeshift feature expansion.
2. Finish Task 2 before importing large provider fixtures in performance runs.
3. Finish Task 3 before Compose EPG optimization beyond cached geometry.
4. Finish Task 5 and Task 6 only with full live-TV validation.
5. Finish Task 7 before claiming startup/TIF improvement.
6. Finish Task 10 before release decisions or priority re-ranking.
