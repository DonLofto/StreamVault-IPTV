# Buffering Investigation — App-side Playback Handling

Investigation of buffering during playback in StreamVault (Android TV IPTV, Kotlin/Jetpack Compose/Media3).
Scope: `player/` (playback abstraction + Media3 implementation) and `data/` (provider/repository layer).
Assumption: internet connection and source stream are NOT the cause; focus is app-side handling.

Findings ordered by how likely each is to cause buffering unrelated to network conditions.
Refs are `file:line`.

---

## HIGH — App self-inflicted stalls on live playback

### H1. Buffer-policy promotion re-creates the whole player mid-stream
`Media3PlayerEngine.kt:1748` (`promoteLiveHlsBufferIfNeeded`) runs every 1 s from the engine loop (`:383`).
For live HLS/UHD (4K/HDR/≥20 Mbps, `PlaybackBufferPolicies.kt:250-273` + `LiveHlsBufferPromotionDecider.kt`),
once the video format is observed it jumps the live buffer from `stable-live` (8 s / 30 s) to
`large-live` (30 s / 90 s, `PlaybackBufferPolicies.kt:239`). The policy label change makes `prepareInternal`
compute `needsRecreate=true` (`:1035-1052`) → `recreatePlayer()` (`:1115`) tears down and rebuilds the
`ExoPlayer`, then re-prepares the source.

- **Current behavior:** a few seconds after a UHD live channel starts, the app destroys and recreates the player to raise the buffer target.
- **Why it looks like buffering:** full renderer teardown + re-open of the provider URL + re-buffer, independent of network. On a high-bitrate channel the promotion can also re-fill 90 s of buffer, delaying READY further.
- **Fix:** apply the promoted threshold via `DefaultLoadControl.Builder` on the existing player (or `setBufferDurationUs`) without `recreatePlayer`, or gate promotion to before first frame only (`hasRenderedFirstVideoFrame`). Only re-create when the *decoder* actually changes, not on a buffer-size tweak.

### H2. Video-stall recovery reprepares (re-opens provider connection) on first live stall
`Media3PlayerEngine.kt:1783` (`handleVideoStall`) + `PlaybackStallRecoveryPolicy.kt:14` (`shouldReconnectLiveStall`) —
on recovery attempt 1 in live `READY`/`BUFFERING`, a full re-prepare (reconnect) fires.
`VideoStallDetector.kt:46` can report a stall after just 5 s of no new video frame. On a live stream where
audio keeps flowing but the video renderer hiccups briefly (HDMI negotiation, a dropped-frame burst), this
misclassifies as "stalled" and does a full reconnect → visible spinner even when the network is fine.

- **Fix:** require longer frame-silence or confirm `PLAYER bufferedDuration` drop before a destructive reconnect; consider letting the player's own default live speed-control / rebuffer ride through the first event instead of tearing down.

---

## MEDIUM — Redundant network work mid-playback mistaken for buffering

### M1. Timeshift manager downloads the SAME live stream a second full time
`LiveTimeshiftManager.kt` runs its own OkHttp capture in parallel with ExoPlayer whenever live-rewind is
enabled (default off, `timeshiftConfig.enabled=false`, `PlayerViewModel.kt:297/439`):
- `HlsSession.capture()` (`:588`) polls the playlist and **re-downloads every segment** (`:667-685`, `streamSegmentToDisk`/`fetchBytes`).
- `ProgressiveSession.capture()` (`:446`) opens a second byte stream of the identical URL and writes everything to disk/memory.

This doubles throughput to the provider. On a provider with connection caps or a contended link, ExoPlayer's
own segment requests get starved → buffering actually caused by the app's parallel capture, not the connection.
There is no coordination with `StalkerTrafficCoordinator` (which only defers catalog fetch, `StalkerTrafficCoordinator.kt:26`).

- **Fixes:** cap HLS timeshift capture to the segments ExoPlayer already fetched (use `onMediaItemTransition` buffered window or `SimpleCache` instead of a second network read); or throttle capture; at minimum record `notePlaybackStarted`/`notePlaybackStopped` and skip/reduce capture during active playback. Memory backend also holds up to ~5 min of raw PCM/segment `ByteArray`s in RAM (`LiveTimeshiftModels.kt:12-20`).

### M2. Stalker/Xtream token renewal re-resolves and re-prepares the entire player
`PlayerLifecycleActions.kt:41` (`startTokenRenewalMonitoring`) polls every 10 s; when the resolved URL's expiry
is within 60 s it re-resolves the full stream (`resolvePlaybackStreamInfo`, which for Stalker performs
`create_link` handshakes — `StalkerProvider.kt:475`) and calls `renewStreamUrl` (`Media3PlayerEngine.kt:412`)
which does `setMediaSource` + `player.prepare()`. That is a hard reconnect, mid-playback, on a timer.

- **Why it looks like buffering:** at a fixed cadence the stream re-buffers regardless of network health.
- **Fix:** use the `preferStableUrl` credential-based URL for long sessions, or re-issue only the token to the same data source rather than re-creating the media source and calling `prepare()`.

### M3. Aggressive live retry/re-prepare loop after playback start
`PlayerRetryPolicy.kt`: live transient retries up to **10** (`:14, :170`), fast-retry delay 500 ms (`:13`),
live HLS malformed after start up to **12** (`:15, :180`). Each retry path calls `prepareInternal` → full
re-prepare/reconnect (`Media3PlayerEngine.kt:2430-2456`). Combined with H2 a single self-inflicted hiccup can
cascade into multiple full reconnects, each looking like a separate buffer event.

- **Fix:** prefer `LoadErrorHandlingPolicy` segment-level retries (handled internally by ExoPlayer without teardown) and reserve the engine-level re-prepare for genuinely terminal conditions; reduce the live attempt counts.

---

## LOW — Render-pipeline / main-thread stalls

### L1. Synchronous disk write on the main thread every second
`Media3PlayerEngine.kt:383` → `shouldRefreshPlaybackSupportSnapshot()` returns `true` on non-low-memory devices (`:2211`), and `PlaybackSupportSnapshotStore.write()` (`PlaybackSupportSnapshotStore.kt:17`) is a **blocking `writeText` on `Dispatchers.Main.immediate`** inside the 1 s engine loop. That is a synchronous filesystem write on the UI/player thread every tick — a jank/stall source during playback.

- **Fix:** move the snapshot write to `Dispatchers.IO`.

### L2. FFmpeg audio fallback duplication and full-pipeline re-prepare
`FfmpegExtensionSupport.kt:20` (`shouldAttemptFfmpegAudioFallback`) **unconditionally returns `false`**, while the engine uses its own private copy (`Media3PlayerEngine.kt:2252`). This duplication is confusing but benign. The real gate (`tryFfmpegAudioFallback`, `:2093`) triggers a full `prepareInternal` on the `onTracksChanged` path when audio groups exist but no track is decodable (`:1526-1548`). It only fires on genuinely unsupported audio codecs, so it cannot cause buffering on healthy streams — but when it fires it drops the render pipeline (metadata pause).
- **Fix:** delete the dead `FfmpegExtensionSupport.shouldAttemptFfmpegAudioFallback` and route the fallback through `MergingMediaSource`/`MediaCodecSelector` so only audio, not the whole pipeline, is switched. Consider offloading the per-buffer PCM copy in `LiveAudioTapAudioSink.kt:39` (allocates a `ByteArray` on the audio renderer thread per buffer when a tap is active).

### L3. Audio PCM tap copy on the audio renderer thread
Only when live-translation is active. `LiveAudioTapAudioSink.kt:39-65` allocates and copies the full PCM buffer on the renderer thread; with a slow consumer this could back-pressure, but the consumer `trySend`s to a DROP_OLDEST channel (`LiveTranslationSession.kt:66, :227`), so it is non-blocking in practice. Low risk.

---

## Threading (Checkpoint 7) — overall healthy
- Engine scope is `Dispatchers.Main.immediate` (correct for ExoPlayer callbacks); Room/EPG/sync all run on `Dispatchers.IO`/`WorkManager` (`ProviderSyncWorker`, `BackgroundEpgSyncWorker`), not the player thread — **no** blocking Room/EPG work on the playback loop, except the L1 main-thread file write.
- Global timeshift capture runs on `Dispatchers.IO` (not on the renderer thread) — the problem is the *dual network load* (M1), not thread blocking.

---

## Bottom line
Strongest app-side, non-network buffering causes:
1. **H1** — buffer-policy promotion tearing down/recreating the player mid-stream
2. **H2** — stall recovery doing a full reconnect on a brief frame gap
3. **M1** — timeshift downloading a second full copy of the live stream
4. **M2/M3** — token renewal and live retry doing full re-prepares on timers/errors
5. **L1** — a blocking main-thread file write every second (jank)

No evidence forcing a network/connection-only explanation — these are real app-driven stalls.
