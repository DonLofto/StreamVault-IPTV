# Plan: Fix app-side buffering during playback

Goal: eliminate buffering/stall causes that originate in the app (not network/source).
Read `docs/context.md` (orientation + threading model) and `docs/buffering-investigation.md` (evidence) first.
Each fix below = ticket with: file/line, current behavior, why it stalls, concrete change, verification.

Order by priority. Do them one at a time; fix 1 and 2 first. Keep changes narrow; every policy has direct
unit tests — don't break them.

---

## FIX 1 (HIGH) — H1: Buffer-policy promotion must not destroy the player mid-stream

**File/line**
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt:1748` `promoteLiveHlsBufferIfNeeded`
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt:1035-1052` `needsRecreate` logic in `prepareInternal`
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt:1115` `recreatePlayer`
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt:1150-1159` `DefaultLoadControl.Builder`

**Current behavior**
When a live HLS channel is UHD/HDR/≥20 Mbps, the 1 s engine loop promotes the buffer target from
`stable-live` (8/30 s) to `large-live` (30/90 s). The label change flips `needsRecreate=true`, so
`prepareInternal` calls `recreatePlayer()` — full `ExoPlayer` teardown + rebuild + re-prepare of the provider URL.

**Why it stalls**
Full renderer teardown + re-open + re-buffer, independent of network. Also re-fills up to 90 s delaying
READY on high-bitrate channels.

**Change**
1. Decouple "buffer size" from "recreate". Add a code path that, when the only reason for `needsRecreate`
   is a buffer-policy change (and decoder/policy/surface are unchanged), updates the load control on the
   live player instead of re-creating it.
   - Preferred: build the `DefaultLoadControl` once in `createPlayer` from a function
     `bufferPolicyProvider: () -> PlaybackBufferPolicy` so the live `ExoPlayer` reads the current policy
     without teardown. Simplest robust option: add a `setBufferDurationsUs`-style update via
     `DefaultLoadControlBuilder.setBufferDurationsMs(...)` applied through a re-binding, or rebuild the
     load control and `player.reload()` — but avoid `recreatePlayer()`.
   - Practical minimal change: keep one `ExoPlayer`; when `needsRecreate` is true *only because*
     `nextBufferPolicy.label != currentBufferPolicyLabel` (i.e. all decoder/type/policy fields equal),
     do `exoPlayer`-level buffer update and skip `recreatePlayer()`.
2. Add a defensive guard: skip promotion entirely unless `hasRenderedFirstVideoFrame == true` OR before
   `markPlaybackStarted`. (Promotion should not fight the startup buffer.)

**Tests to update/add**
- `LiveHlsBufferPromotionDeciderTest.kt` — still expects promotion decision; keep.
- Add regression: a buffer-label change alone must NOT trigger decoder-mode rebuild (assert
  `needsRecreate == false` when only buffer differs).
- Run `PlayerMediaSourceFactoryTest`, `PlaybackBufferPoliciesTest`.

---

## FIX 2 (HIGH) — H2: Live stall recovery should not hard-reconnect on a brief frame gap

**File/line**
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt:1783` `handleVideoStall`
- `player/src/main/java/com/streamvault/player/playback/PlaybackStallRecoveryPolicy.kt:14` `shouldReconnectLiveStall`
- `player/src/main/java/com/streamvault/player/playback/VideoStallDetector.kt:46` `shouldReportStall`

**Current behavior**
First live stall (attempt 1) in `READY`/`BUFFERING` triggers a full re-prepare/reconnect
(`shouldReconnectLiveStall` returns true on attempt 1). `VideoStallDetector` reports a stall after 5 s with
no new video frame, even when `bufferedDuration` is healthy.

**Why it stalls**
A brief video-renderer hiccup (audio continues) is misclassified as a stall and converts into a full
destructive reconnect + re-buffer, which the user experiences as a spinner.

**Change**
1. Tighten `shouldReportStall` (or its caller) so a live `READY` stall is not reported while the buffer is
   healthy — e.g. only report if `bufferedDurationMs < respectThreadhold` (a few seconds) in addition to
   frame-silence. Preserve the true "stuck" case (0 progress + empty buffer).
2. Change `shouldReconnectLiveStall`: do NOT return true on the very first stall. Require the frame-silence
   window to be exceeded and buffering to actually be present (state `BUFFERING` with low buffered) before a
   reconnect; add an attempt>=2 gate so one blip doesn't tear down.
3. Let ExoPlayer's `DefaultLoadControl`/live speed control absorb the initial event instead of the engine
   forcing `prepareInternal`.

**Tests to update/add**
- `PlaybackStallRecoveryPolicyTest.kt` — update expectations: attempt 1 should not reconnect under healthy buffer.
- `VideoStallDetectorTest.kt` — add case: READY + frame silence but bufferedDurationMs high ⇒ no stall.
- Keep real "stuck with empty buffer for N s" recovering.

---

## FIX 3 (MEDIUM) — M1: Timeshift must not download a 2nd full copy of the live stream

**File/line**
- `player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt:588` (`HlsSession.capture`), `:446` (`ProgressiveSession.capture`), `:119` (`startSession`)

**Current behavior**
When live-rewind is enabled, a parallel OkHttp capture re-downloads every HLS segment / full progressive
byte-stream, doubling throughput to the provider during playback and starving ExoPlayer's own requests.

**Why it stalls**
Bandwidth/connection contention to the provider is caused by the app itself; capturable as buffering even on
a healthy link, especially under provider caps.

**Change (pick the least invasive that closes the stall)**
1. Short-term (must): coordinate with playback — in `startSession`, and inside the capture loops, respect
   `StalkerTrafficCoordinator` active-playback state (or an app-level "mediasession active" flag) and
   throttle/skip capture while the primary player is mid-buffer. At minimum, do not start a second full
   read for a stream that ExoPlayer is actively buffering.
2. Better: for HLS, reuse segments ExoPlayer already downloaded via `onMediaItemTransition` buffered-window
   or a `SimpleCache`/`CacheDataSource` shared with the player, instead of a second network read. This is a
   larger change — if out of scope for this pass, ship (1) and file a follow-up.
3. Memory backend: keep hard cap (already `LiveTimeshiftModels.kt:12-20`); consider surfacing memory warning.

**Tests**
- `LiveTimeshiftBackendSelectionTest.kt` — keep passing.
- Add test: capture loop defers when active playback/buffering is indicated.

---

## FIX 4 (MEDIUM) — M2: Token renewal should not re-prepare the whole player

**File/line**
- `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerLifecycleActions.kt:41` `startTokenRenewalMonitoring`
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt:412` `renewStreamUrl`
- `data/src/main/java/com/streamvault/data/remote/xtream/XtreamStreamUrlResolver.kt:103` `resolveWithMetadata`

**Current behavior**
Every token-expiry (poll every 10 s, renew when within 60 s) re-resolves the full stream (Stalker does
`create_link` handshake) then `renewStreamUrl` → `setMediaSource` + `prepare()`. Hard reconnect on a timer.

**Why it stalls**
Periodic re-buffer at a fixed cadence regardless of network health.

**Change**
1. Prefer `preferStableUrl=true` (credential-based stable URL, `XtreamStreamUrlResolver.kt:82, :171`) for
   long sessions so no expiry-triggered re-resolve/re-prepare is needed mid-playback.
2. If renewal is required, re-issue only the token onto the existing data source (update default request
   properties via `OkHttpDataSource.Factory` / interceptor) and continue playback — do not call `prepare()`/
   `setMediaSource` for the same media.
3. Keep `renewStreamUrl` for genuine URL/param changes only.

**Tests**
- `XtreamStreamUrlResolver` tests (under `data/`) — ensure stable-url path exercised.
- Manual: verify a long Stalker/Xtream session no longer re-buffers at token expiry.

---

## FIX 5 (MEDIUM) — M3: Reduce live full re-prepare retry cascade

**File/line**
- `player/src/main/java/com/streamvault/player/playback/PlayerRetryPolicy.kt:14-15, :166-184`
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt:2430-2456`

**Current behavior**
Live transient retries up to 10 (fast-retry 500 ms), live HLS malformed-after-start up to 12; each engine
retry is a full `prepareInternal`/reconnect. Single hiccup can cascade to multiple full reconnects.

**Why it stalls**
Each retry is a full re-prepare + re-open, each looking like a separate buffer event.

**Change**
1. Prefer ExoPlayer `LoadErrorHandlingPolicy` segment-level retries (handled internally, no teardown) over
   the engine-level `prepareInternal` re-prepare for transient/live errors.
2. Reconsider `LIVE_TRANSIENT_RETRY_ATTEMPTS=10` and `LIVE_HLS_MALFORMED_RETRY_ATTEMPTS_AFTER_START=12` —
   the engine-level full re-prepare should only trigger on genuinely terminal conditions; lower counts or
   gate so `prepareInternal` is not used for segment-level blips.
3. Ensure `shouldPreservePlaybackStateForRetry` keeps state so a retry doesn't reset position needlessly.

**Tests**
- `PlayerRetryPolicyTest.kt`, `LiveRetryStatePolicyTest.kt` — keep consistent with new counts.

---

## FIX 6 (LOW) — L1: Move per-second snapshot write off the main thread

**File/line**
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt:383`
- `player/src/main/java/com/streamvault/player/PlaybackSupportSnapshotStore.kt:17`

**Current behavior**
`PlaybackSupportSnapshotStore.write` does a blocking `writeText` on `Dispatchers.Main.immediate` every 1 s
from the engine loop (non-low-memory devices).

**Why it janks**
Synchronous filesystem write on the UI/player thread every tick.

**Change**
- In the engine, wrap the write in `scope.launch(Dispatchers.IO) { playbackSupportSnapshotStore.write(...) }`
  (or make `write` suspend and dispatch to IO internally).
- Keep the read-side behavior identical (readers read the same file path).

**Tests**
- No policy change; smoke test engine still writes snapshot and starts.

---

## FIX 7 (LOW, cleanup) — L2: FFmpeg audio fallback duplication

**File/line**
- `player/src/main/java/com/streamvault/player/playback/FfmpegExtensionSupport.kt:20` dead `shouldAttemptFfmpegAudioFallback`
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt:2252` real gate, `:2093` `tryFfmpegAudioFallback`

**Current behavior**
Two functions with the same name; the `FfmpegExtensionSupport` one always returns `false`. The engine uses
its own. Confusing but currently benign. The fallback itself does a full `prepareInternal` (drop render
pipeline) but only on genuinely unsupported audio codecs.

**Change**
1. Delete the dead `shouldAttemptFfmpegAudioFallback` in `FfmpegExtensionSupport.kt` (and its test file
   `FfmpegAudioFallbackPolicyTest.kt` if it only targets it — verify first).
2. Note: full-pipeline re-prepare on audio fallback is acceptable for unsupported codecs; do not refactor
   unless a stall is reproduced on such a stream.

**Tests**
- `FfmpegExtensionSupportTest.kt`, `FfmpegAudioFallbackPolicyTest.kt` — adjust/remove dead references.

---

## Acceptance / verification checklist
For each fix, before marking done:
1. Unit tests relevant to that policy pass (targeted module: `./gradlew :player:testDebugUnitTest` /
   `:data:testDebugUnitTest`).
2. No new compiler warnings that matter; added regression test where behavior changed.
3. If live-behavior changed, follow the `AGENTS.md` live-TV validation:
   - emulator orientation locked (`ROTATION_270`),
   - 2 s screenshot cadence ≥ 60 frames reading data,
   - unique-frame hash count spans the whole window (video progressing),
   - `dumpsys media_session` still `PLAYING` + `error=null`,
   - logcat: no fatal-error, no stuck-player, no unintended MPEG-TS fallback, HLS prepare/read/first-frame present.
4. Validate >1 live channel when the fix is live-TV-general (record channel names, frame count, unique hashes,
   media-session result, log findings in the final report).

## Suggested implementation order
1. FIX 1 (H1) — remove mid-stream player teardown on buffer promotion.
2. FIX 2 (H2) — stop hard-reconnect on healthy-buffer frame gap.
3. FIX 5 (M3) — gate live engine-level retries (depends on FIX 2 behavior being safe).
4. FIX 4 (M2) — prefer stable URL / non-destructive token renewal.
5. FIX 3 (M1) — timeshift coordinate/throttle capture.
6. FIX 6 (L1) — main-thread snapshot write off.
7. FIX 7 (L2) — cleanup dead code.

## Not in scope / deferred
- Full HLS timeshift reuse via shared `SimpleCache` (file a follow-up after FIX 3 short-term lands).
- Any network/bandwidth tuning — the premise is the connection and source are healthy.
