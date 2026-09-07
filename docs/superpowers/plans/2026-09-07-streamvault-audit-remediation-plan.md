# StreamVault Audit Remediation Implementation Plan

> **For agentic workers:** Execute this plan task-by-task with `superpowers:executing-plans`. Steps use checkbox syntax for tracking.

**Goal:** Implement and verify the supplied StreamVault security, lifecycle, persistence, playback, Compose, Android TV, dormant-contract, lint, and performance remediation mandate.

**Architecture:** Use strict-by-default transport, provider-scoped lifecycle admission, cancellable OkHttp operations, serialized timeshift file/accounting mutations, and narrow Compose dependencies. Remove unsupported VPN/subtitle/capacity/update surfaces when no safe backend or authoritative semantics exist. Work in the supplied checkout without committing or pushing.

**Tech Stack:** Kotlin, Coroutines/Flow, Jetpack Compose for TV, Media3, Room, Hilt, WorkManager, OkHttp, DataStore, Android TV TIF, Gradle lint and connected tests.

**Spec:** `docs/superpowers/specs/2026-09-07-streamvault-audit-remediation-design.md`

## Global Constraints

- Revalidate every finding against the current checkout before changing it.
- Preserve unrelated user changes and do not commit, push, add secrets, signing files, provider credentials, `.env` files, generated captures, or device logs.
- Add a focused regression before each behavior fix, run the expected failing test, implement the smallest fix, rerun it, run the owning module, and inspect the diff.
- Never weaken/delete tests, add blanket lint suppressions, disable lint issue IDs, or use arbitrary sleeps in tests.
- Keep `StreamInfo.allowInvalidSsl` false by default and never select it implicitly for Stalker; never forward credentials across a cross-host redirect.
- Keep the Android minimum SDK behavior compatible with API 25; use explicit guards and permission contracts for newer APIs.
- Player, timeshift, decoder, renderer, TLS/playback-network, and stream-admission changes require the complete two-channel live-TV protocol in `AGENTS.md`.
- Schema changes require an exported schema, a non-destructive migration, and a migration test from the previous version.
- Report command exit codes, lint counts, benchmark/runtime blockers, and residual risks from current-session evidence only.

---

### Task 1: Restore strict Stalker TLS and redirect credential boundaries

**Finding:** Supplied Task 1.

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/di/NetworkModule.kt`
- Modify: `data/src/main/java/com/streamvault/data/remote/stalker/StalkerProvider.kt`
- Modify: `data/src/main/java/com/streamvault/data/remote/stalker/OkHttpStalkerApiService.kt`
- Modify: `player/src/main/java/com/streamvault/player/playback/PlayerDataSourceFactoryProvider.kt`
- Modify: `player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt`
- Modify: `app/src/main/java/com/streamvault/app/tvinput/StreamVaultTvInputService.kt`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerViewModel.kt`
- Test: existing Stalker/player/timeshift tests plus focused redirect-header tests.

**Interfaces:** Stalker resolution produces `StalkerPlaybackInfo.allowInvalidSsl == false`; strict OkHttp clients use the platform trust manager; request-header policy receives the source and effective URL and returns only headers safe for that origin.

- [ ] Add tests proving valid TLS succeeds, self-signed/hostname-mismatched TLS fails, Stalker playback defaults strict, and a cross-host redirect drops `Authorization`, `Cookie`, `X-User-Agent`, and portal-only headers.
- [ ] Run the focused Stalker/provider/player tests and capture the expected failures from hard-coded unsafe TLS and `allowInvalidSsl = true`.
- [ ] Remove the singleton Stalker trust-all builder and hard-coded playback unsafe flag; retain no hidden user preference.
- [ ] Make player, timeshift, TIF, probe, and cast paths honor only the final explicit flag, with Stalker-generated values strict and no unsafe retry after TLS failure.
- [ ] Add/verify a redirect interceptor or origin-aware header rebuild that cannot carry credentials to another host; preserve same-host cookies and authorization where required.
- [ ] Rerun focused tests, `./gradlew :data:testDebugUnitTest :player:testDebugUnitTest :app:testDebugUnitTest`, and the full two-channel live-TV protocol.

### Task 2: Remove the false built-in VPN feature

**Finding:** Supplied Task 2.

**Files:**
- Delete: `app/src/main/java/com/streamvault/app/vpn/StreamVaultVpnService.kt`
- Delete: `data/src/main/java/com/streamvault/data/vpn/VpnRepositoryImpl.kt`
- Delete: `data/src/main/java/com/streamvault/data/vpn/WireGuardConfigParser.kt` when no remaining approved consumer exists.
- Delete: `domain/src/main/java/com/streamvault/domain/vpn/VpnModels.kt` when no remaining approved consumer exists.
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsVpnSection.kt`, settings content/navigation/ViewModel, Hilt bindings, manifest, strings/docs.

**Interfaces:** No app component can start a default-route TUN service or report VPN connected; old VPN preference data is removed through a one-time, key-only retirement migration without logging private keys.

- [ ] Add tests proving the unavailable feature has no start action, no connected state without a service event, and service-start failure cannot become success.
- [ ] Run the focused VPN tests and inspect the expected failure caused by the placeholder service/repository.
- [ ] Remove the service, manifest registration, Hilt binding, settings category/controls, repository state, and dead parser/model only after confirming no live consumer remains.
- [ ] Add the one-time preference retirement path and a test that clears only the old VPN preference namespace.
- [ ] Add user-facing documentation stating embedded VPN protection is unavailable; run app unit tests and a manifest/resource compile check.

### Task 3: Serialize provider deletion, sync, and EPG refresh

**Finding:** Supplied Task 3 and part of Task 13.

**Files:**
- Create: `data/src/main/java/com/streamvault/data/lifecycle/ProviderLifecycleCoordinator.kt`
- Modify: `data/src/main/java/com/streamvault/data/repository/ProviderRepositoryImpl.kt`
- Modify: `data/src/main/java/com/streamvault/data/repository/EpgRepositoryImpl.kt`
- Modify: `data/src/main/java/com/streamvault/data/sync/SyncManager.kt`
- Modify: `data/src/main/java/com/streamvault/data/local/dao/Daos.kt` if cleanup queries are missing.
- Test: provider/EPG/SyncManager tests with barriers and cancellation.

**Interfaces:** `withProviderOperation(providerId, block)` rejects new work after deletion begins; `withProviderDeletion(providerId, block)` marks a tombstone, joins active work, runs cleanup, and releases/removes the state only after all rows and metadata are gone.

- [ ] Add a deterministic staged-EPG/deletion race test: stage rows, block before promotion, begin deletion, release the barrier, join both jobs, and assert provider, positive rows, negative rows, refresh state, and mutex state are absent.
- [ ] Run that test before implementation and record the race failure.
- [ ] Implement the coordinator with a registry mutex, per-provider active-operation count, deletion tombstone, and completion signal; make cancellation unwind counts in `finally`.
- [ ] Wrap public provider sync/EPG-refresh entry points with operation admission and run provider deletion through the deletion gate.
- [ ] Recheck provider existence immediately before EPG promotion in the same transaction and delete both staging and committed rows during deletion.
- [ ] Fix provider lock acquisition so the global admission mutex is released before waiting for a provider-specific mutex; add the provider-A/provider-B deterministic concurrency test.
- [ ] Rerun focused data tests and `./gradlew :data:testDebugUnitTest`.

### Task 4: Make timeshift quota accounting mutation-safe

**Finding:** Supplied Task 4 and audit B1/B2/B5/M4 revalidation.

**Files:**
- Modify: `player/src/main/java/com/streamvault/player/timeshift/TimeshiftDiskManager.kt`
- Modify: `player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt`
- Test: `player/src/test/java/com/streamvault/player/timeshift/TimeshiftDiskManagerTest.kt` and `LiveTimeshiftManagerTest.kt`.

**Interfaces:** `TimeshiftDiskManager` exposes synchronized `reserve`, `recordMutation`, `release`, `currentUsageBytes`, and `prune` operations; usage is physical-file based and snapshot hard links do not double-charge.

- [ ] Add deterministic tiny-budget tests for write, delete, prune-before-fail, HLS/DASH limits, hard-link accounting, concurrent reservations, and genuine insufficient storage.
- [ ] Run the new tests before implementation and confirm stale TTL accounting or missing reservations causes the expected failures.
- [ ] Replace the ten-second decision cache with synchronized incremental accounting plus forced physical reconciliation after uncertain mutations; account for temporary/staging files and inode identity.
- [ ] Make every segment/chunk/snapshot/link/copy/prune/delete path call the accounting API and prune expired rolling data before rejecting a write.
- [ ] Keep DASH initialization data outside the media-prune queue and preserve it only for snapshot construction.
- [ ] Rerun player tests and record the exact result; do not claim storage safety without device evidence.

### Task 5: Eliminate the snapshot-versus-stop race

**Finding:** Supplied Task 5.

**Files:**
- Modify: `player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt`
- Test: `LiveTimeshiftManagerTest.kt` with injectable file-copy/link operation.

**Interfaces:** A session owns a generation, snapshot job, and stable source list; `stopSession` cancels/joins snapshots before deleting sources; snapshot promotion returns only an atomically complete directory.

- [ ] Add a blocking copy/link fake and a test that pauses snapshot creation, stops/replaces the session, resumes the fake, and asserts cancellation cleanup or complete atomic promotion with no partial playlist.
- [ ] Run the race test before implementation and retain its current failure.
- [ ] Add per-session snapshot mutex/job tracking and stable generation checks; write to `snapshot-N.tmp`, fsync/close files through existing safe abstractions, then rename to the final directory.
- [ ] Ensure retired snapshot cleanup cannot delete an in-flight snapshot and stale results cannot replace a newer session.
- [ ] Rerun focused player tests and the owning module tests.

### Task 6: Fix API 25–32 external navigation parsing

**Finding:** Supplied Task 6.

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/navigation/ExternalNavigationRequest.kt`
- Modify: `app/src/main/java/com/streamvault/app/MainActivity.kt`
- Test: navigation unit tests and minimum-SDK/Robolectric coverage where configured.

- [ ] Add tests for encoded provider import URI, movie/series return routes, malformed percent input, blank values, duplicate keys, explicit exported MainActivity intent, and API-25-compatible decoding.
- [ ] Run focused tests and observe the API-33 `NewApi` lint failure/current unsafe parse behavior.
- [ ] Replace the charset-object decoder with the API-compatible charset-name overload and catch malformed encoding so parsing returns null/home safely.
- [ ] Define duplicate-key behavior explicitly (last valid value wins) and reject unsafe blank/invalid IDs without crashing activity intent handling.
- [ ] Rerun app navigation tests and app unit tests.

### Task 7: Make Trakt pairing single-flight and cancellable

**Finding:** Supplied Task 7.

**Files:**
- Modify: `data/src/main/java/com/streamvault/data/remote/trakt/TraktRepositoryImpl.kt`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsViewModel.kt`
- Test: Trakt repository/ViewModel tests.

- [ ] Add tests for replacement cancellation, immediate cancel, expired/denied/cancelled/success terminal states, stale-generation suppression, teardown cancellation, and exactly-once state clearing.
- [ ] Run focused tests before implementation and record overlapping poll behavior.
- [ ] Introduce one repository-owned `Job` plus generation token/mutex; cancel and join the previous attempt before starting the next; keep ViewModel as a lifecycle caller.
- [ ] Ensure every network wait is cancellable and terminal state is published only by the current generation.
- [ ] Rerun data and app Trakt tests.

### Task 8: Add a shared cancellable OkHttp coroutine adapter

**Finding:** Supplied Task 8 and H6 cancellation requirement.

**Files:**
- Create or modify the shared HTTP adapter under `data/src/main/java/com/streamvault/data/remote/http/`.
- Modify: `data/src/main/java/com/streamvault/data/repository/EpgRepositoryImpl.kt`, `EpgSourceRepositoryImpl.kt`, `SyncManagerM3uImporter.kt`, and Stalker/worker callers that block on `execute()`.
- Test: MockWebServer cancellation tests.

- [ ] Add tests cancelling before headers, during an endless body, during parsing, and during restart; assert `Call.cancel`, closed response/body/temp files, no staged promotion, and preserved `CancellationException`.
- [ ] Run focused cancellation tests first and confirm current code waits or translates cancellation incorrectly.
- [ ] Implement `suspendCancellableCoroutine` around `Call.execute()` with `invokeOnCancellation { call.cancel() }`; perform body parsing in cancellable loops and close all resources with `use`.
- [ ] Make promotion conditional on successful completion and provider/session generation; allow a new request to succeed after cancellation.
- [ ] Rerun data tests and worker tests.

### Task 9: Fix Android service/API compatibility and permissions

**Finding:** Supplied Task 9 plus the lint API/permission subset.

**Files:**
- Modify: `player/src/main/java/com/streamvault/player/cache/AppCacheQuota.kt`
- Modify: all source files reported for `NewApi`, `ServiceCast`, `MissingPermission`, and receiver flags.
- Modify: `data/build.gradle.kts`, `player/build.gradle.kts`, and app manifest permissions/contracts as needed.
- Test: focused Robolectric/unit tests for cache quota, receiver registration, notification permission, and connectivity helpers.

- [ ] Extract all current lint errors by ID/file from the retained reports and group them without suppressing them.
- [ ] Add a failing cache-quota test proving `STORAGE_SERVICE` is not cast as `StorageStatsManager`; add API-guard tests for API 26/28 calls.
- [ ] Fix the service lookup, guards, receiver export/not-export flags, POST_NOTIFICATIONS checks, and library manifest connectivity permissions at source.
- [ ] Rerun each affected focused test and module lint; retain remaining warning categories separately.

### Task 10: Repair progress clocks and isolate EPG invalidation

**Finding:** Supplied Tasks 10–11 and audit M1/L3.

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/ui/components/Cards.kt`
- Modify: `app/src/main/java/com/streamvault/app/ui/components/shell/AppMediaCards.kt`
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/epg/EpgControlComponents.kt`, `EpgScreen.kt`, `EpgGridComponents.kt`.
- Test: Compose clock/recomposition tests using a test clock.

- [ ] Add tests proving progress changes after advancing one scoped clock, stops when inactive, and static EPG rows do not recompose on a 30-second tick.
- [ ] Run them before implementation and record per-card ticker/state-flow misuse.
- [ ] Hoist a lifecycle-aware clock per row/screen, pass `nowMs` to cards, and keep immutable program geometry/labels above the narrow current-state dependency.
- [ ] Keep one `AsyncImage` mounted with fallback underneath and avoid unrelated recomposition.
- [ ] Rerun app UI/unit tests and any configured Compose instrumentation test.

### Task 11: Move cold-start work after first frame and remove admission HOL blocking

**Finding:** Supplied Tasks 12–13 and audit H1/H6/M5.

**Files:**
- Create: startup coordinator and playback network admission classes in existing app/data packages.
- Modify: `StreamVaultApp.kt`, `MainActivity.kt`, backup bridge, WorkManager/TV integration, `SyncManager.kt`, provider sync workers, player lifecycle actions, and network DI.
- Test: startup-order and provider-A/provider-B admission tests.

- [ ] Add coordinator tests showing first content precedes optional cleanup/update/Watch Next/recommendation/TIF work, duplicate checks coalesce, and no arbitrary delay is required.
- [ ] Add admission tests showing provider B enters while provider A is blocked and background calls defer during active playback.
- [ ] Run both test groups before implementation and preserve current ordering/lock failures.
- [ ] Move external backup copying to cancellable `Dispatchers.IO` work after first composition; make optional graph construction lazy and WorkManager scheduling idempotent.
- [ ] Hold the sync registry mutex only through provider-lock lookup/creation; wait outside it; retain explicit deletion/tombstone gating.
- [ ] Split background/playback clients without changing TLS/auth/timeouts and bind worker cancellation to calls.
- [ ] Rerun app/data/player tests and complete live-TV validation for network-admission changes.

### Task 12: Repair Android TV navigation, focus, and awake behavior

**Finding:** Supplied Tasks 14–18 and audit B6/B7/B10/B11/B13.

**Files:**
- Modify: `PlayerAuxiliaryOverlays.kt`, `RowScrollFix.kt`, `CategoryRow.kt`, `ContinueWatchingRow.kt`, `SelectionChipRow.kt`.
- Modify: settings row/dashboard cards, `MultiViewScreen.kt`, `PlayerScreen.kt`, `PlayerOverlayActions.kt`, `PlayerControlsChrome.kt`, `ExternalPlayerLauncher.kt`.
- Modify: TIF tune service and sync manager if still unverified.
- Test: Compose/TV unit and instrumentation tests for stable keys, focus restoration, D-pad behavior, keep-screen-on ownership, and external-player results.

- [ ] Add tests for insertion/reorder/removal around focused identity, nested-row bring-into-view, informational-row traversal, multiview flag ownership, and `InvalidUrl`/`NoHandler`/`Failed` feedback/focus restoration.
- [ ] Run tests before implementation and preserve current failures.
- [ ] Add stable lazy-list keys and identity-keyed remembered focus; make row height state survive recomposition; remove inert clickable/focusable surfaces.
- [ ] Use Activity access that survives localized `ContextWrapper`; clear only multiview-owned keep-screen-on flags.
- [ ] Wire full transparent guide into the exclusive overlay host, let visible child controls consume DPAD Up/Down before zap interception, and decouple drag seek state from playback position.
- [ ] Add TIF tune generation/cancellation and bounded client cache if B6 remains confirmed.
- [ ] Rerun app tests/instrumentation and live-validate player overlay interactions.

### Task 13: Resolve subtitle, external playback, stream admission, update, download, and dormant-state contracts

**Finding:** Supplied Tasks 19–24.

**Files:**
- Remove or complete `domain/usecase/SearchExternalSubtitles.kt` and `domain/repository/ExternalSubtitleRepository.kt` based on approved backend evidence; preserve local Media3 subtitle attachment.
- Modify external playback mode models/routes/ViewModels/screens and `ExternalPlayerLauncher.kt`.
- Modify/remove maximum-concurrent-stream preference/model/UI and add a shared lease controller only if authoritative semantics exist.
- Modify/remove auto-update-download preference/UI/installer paths.
- Modify `DownloadManager`, active-download UI, and related tests.
- Modify/remove `PlayerViewModel.showZapOverlay`, `liveTranslationDetectedLanguage`, `playerPreferencesUiState`, and `SettingsViewModel.resetAppHomeDashboardShelves`.

- [ ] Add a usage map and tests for each persisted/public contract before changes; classify every one as fully implemented or deliberately removed.
- [ ] Run focused tests before implementation and identify each no-op state/setting.
- [ ] Wire `INTERNAL_PLAYER`, `EXTERNAL_PLAYER`, and `ASK_EVERY_TIME` across live/VOD/recommendation/search/deep-link/resume routes, with TV-readable failures and focus restoration.
- [ ] If no authoritative stream-limit semantics exist, remove setting/key/UI; otherwise implement lifecycle leases with cleanup on all listed failure/stop paths and live validation.
- [ ] If safe verified download-only update automation exists, implement it with integrity/network/storage/permission/cancellation tests; otherwise remove its setting/key.
- [ ] Add D-pad download cancellation with explicit partial-file policy and distinct resume/delete state.
- [ ] Connect retained dormant flows/actions to explicit consumers or delete them; rerun app/domain/player tests.

### Task 14: Restore lint as an enforceable gate

**Finding:** Supplied Phase 6.

**Files:** All files reported by current app/data/player lint reports; resource XML, manifest/app-link config, Media3 integration, annotations, and build configuration where required.

- [ ] Add a generated-but-untracked error inventory from the current reports grouped by `NewApi`, `ServiceCast`, Compose state, `ContextCastToActivity`, receiver flags, `MissingPermission`, `UnsafeOptInUsageError`, Restricted API, annotations, app links, resources, and protected permissions.
- [ ] Fix each error at its source with narrow guards/contracts/public APIs; do not add a correctness baseline or broad suppression.
- [ ] Run `./gradlew :app:lintDebug`, `./gradlew :data:lintDebug`, and `./gradlew :player:lintDebug` separately, recording exit codes and error/warning counts after each batch.
- [ ] Run the combined gate `./gradlew :app:lintDebug :data:lintDebug :player:lintDebug` and require zero error-level findings before marking lint complete.

### Task 15: Add measurable performance and release coverage

**Finding:** Supplied Task 25.

**Files:** Existing benchmark module/conventions if present; otherwise create the minimal Android Macrobenchmark/Baseline Profile module and its manifest/build wiring. Do not add generated captures to git.

- [ ] Inspect current Gradle/plugin/device support and add tests for benchmark scenario registration before wiring it.
- [ ] Measure cold first frame, TV home startup, EPG scrolling/tick, D-pad navigation, overlay navigation, and rapid seek when the device supports it; record frame counts, slow/frozen frames, percentiles, startup time, device/emulator, variant, and iterations.
- [ ] Run `./gradlew :app:assembleDebug`, release build if signing permits, and connected tests if an Android device is available; retain exact external blockers.

### Task 16: Update documentation and execute final verification

**Finding:** Supplied Task 26 and final verification.

**Files:**
- Modify: `docs/performance-audit-results.md`
- Modify: `docs/planFixAudit.md` status/instructions where current evidence changes them.
- Modify: `docs/superpowers/specs/2026-09-07-streamvault-audit-remediation-design.md` only if implementation decisions materially change.
- Create/update the repository's existing Agent Notes mechanism if discovered.

- [ ] Re-read the complete mandate and this plan; create a finding-by-finding disposition matrix for Tasks 1–26, including deliberately removed and externally blocked states.
- [ ] Run the fresh suite: `./gradlew --rerun-tasks :domain:test :data:testDebugUnitTest :player:testDebugUnitTest :app:testDebugUnitTest :player:verifyLocalFfmpegArtifact`.
- [ ] Run `./gradlew :app:lintDebug :data:lintDebug :player:lintDebug`, `./gradlew :app:assembleDebug`, and `./gradlew :app:connectedDebugAndroidTest`; run release assembly only when signing configuration is available.
- [ ] Run `git diff --check`, `git status --short --branch`, and `git diff --stat`; inspect changed paths for secrets, generated outputs, `.DS_Store`, signing files, and unrelated edits.
- [ ] Enforce emulator `ROTATION_270` and perform the two-channel, 61-screenshot, two-second live-TV validation, or record the exact unavailable-source blocker sentence required by the mandate.
- [ ] Update docs only with current command/runtime evidence and leave the final response with the complete evidence matrix, exact files/tests/commands, baseline and after lint counts, benchmark results, live-TV evidence, blockers, and unrelated-change confirmation.
