# Context — StreamVault Playback / Buffering Work

Project overview and orientation for an agent starting a new session on the buffering fix work.
Companion plan: `docs/planFix.md`. Findings that motivate this work: `docs/buffering-investigation.md`.

## Repo / project
- Android TV IPTV app. Kotlin, Jetpack Compose, Media3 (ExoPlayer), Hilt, Room, OkHttp, Coroutines.
- Multi-module Gradle: `app/`, `player/`, `data/`, `domain/`.
- Root config: `AGENTS.md` (has graphify note + emulator orientation + live-TV validation guidance).

## Scope of this task
Fix app-side causes of buffering that are independent of network/source. Focus modules:
- `player/` — playback abstraction + Media3 implementation.
- `data/` — provider/repository layer.
- `app/` — only where it drives player re-preparation mid-playback.

## Key files (player/)
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt` — central engine (~2482 lines). Owns `ExoPlayer`, `prepareInternal`, `recreatePlayer`, stall recovery, FFmpeg audio fallback, buffer-policy promotion, retry. Runs on `Dispatchers.Main.immediate`.
- `player/src/main/java/com/streamvault/player/playback/PlaybackBufferPolicies.kt` — buffer policy table (live/VOD/timeshift, low-memory, auto UHD promotion).
- `player/src/main/java/com/streamvault/player/playback/LiveHlsBufferPromotionDecider.kt` — decides to raise live HLS buffer for UHD/HDR/high-bitrate.
- `player/src/main/java/com/streamvault/player/playback/PlaybackStallRecoveryPolicy.kt` — `shouldReconnectLiveStall`.
- `player/src/main/java/com/streamvault/player/playback/VideoStallDetector.kt` — frame-silence stall detection.
- `player/src/main/java/com/streamvault/player/playback/PlayerRetryPolicy.kt` — live retry counts/delays.
- `player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt` (~1185 lines) — live-rewind capture (HLS/DASH/progressive). Runs on `Dispatchers.IO`.
- `player/src/main/java/com/streamvault/player/playback/FfmpegExtensionSupport.kt` — dead `shouldAttemptFfmpegAudioFallback` (always false).
- `player/src/main/java/com/streamvault/player/PlaybackSupportSnapshotStore.kt` — per-second main-thread file write.
- `player/src/main/java/com/streamvault/player/playback/AudioVideoOffsetAudioSink.kt`, `LiveAudioTapAudioSink.kt` — audio sink wrappers.

## Key files (app/ driving re-prepare)
- `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerLifecycleActions.kt` — `startTokenRenewalMonitoring` (~`renewStreamUrl` every token expiry).
- `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerRecoveryActions.kt` — auth-error re-resolve, zap watchdog.
- `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerViewModel.kt` — `preparePlayer`, `handlePlaybackError`, `maybeStartLiveTimeshift`.

## Data layer (provider url resolution)
- `data/src/main/java/com/streamvault/data/remote/xtream/XtreamStreamUrlResolver.kt` — resolves Xtream/Stalker/Jellyfin/M3U playback URLs; Stalker path does `create_link` handshakes.
- `data/src/main/java/com/streamvault/data/remote/stalker/StalkerProvider.kt` — auth cache + `resolvePlaybackInfo` (`.createLink`, rebootstrap).
- `data/src/main/java/com/streamvault/data/remote/stalker/StalkerTrafficCoordinator.kt` — defers catalog fetch during active playback.

## Threading model (important)
- Engine state + ExoPlayer must be touched only on `Dispatchers.Main.immediate`.
- `Media3PlayerEngine` uses one `CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())` (`Media3PlayerEngine.kt:170`).
- `createPlayer` uses `DefaultLivePlaybackSpeedControl` with fallback speeds pinned to 1.0f (no catch-up speedup).
- Timeshift manager uses its own `CoroutineScope(Dispatchers.IO)`.
- Tests replace `scope`; do not change dispatcher without checking tests.

## How the player is built
`createPlayer()` (`Media3PlayerEngine.kt:1139`) builds `DefaultLoadControl` from the current `PlaybackBufferPolicy`
and a custom `DefaultRenderersFactory` (`buildRenderersFactory`, `:1200`) with managed codec selectors +
extension renderer modes.
`prepareInternal()` (`:922`) recomputes policy/decoder preferences each call and calls `recreatePlayer()` if
`needsRecreate` (`:1035`).

## Verified candidate bugs (see buffering-investigation.md for details)
- **H1** `promoteLiveHlsBufferIfNeeded` → `needsRecreate` → `recreatePlayer()` mid-stream on UHD live HLS.
- **H2** `handleVideoStall` + `shouldReconnectLiveStall` full re-prepare on first live frame-gap stall.
- **M1** Timeshift downloads a 2nd full copy of the live stream (bandwidth starvation).
- **M2** Token renewal re-resolves + `renewStreamUrl` (full re-prepare) on timer.
- **M3** Live retry up to 10–12 full re-prepares (`PlayerRetryPolicy`).
- **L1** `PlaybackSupportSnapshotStore.write` blocking file write on Main every 1 s.

## Verify-your-work notes
- Read `AGENTS.md` for emulator orientation + the live-TV playback validation routine (2 s screenshot cadence,
  45–61 frames, unique-frame hashes, media session `PLAYING` + `error=null`, no fatal error / no unintended
  MPEG-TS fallback / HLS prepare+first-frame in logcat).
- Player unit tests live alongside sources, e.g.:
  - `player/src/test/java/com/streamvault/player/playback/LiveHlsBufferPromotionDeciderTest.kt`
  - `player/src/test/java/com/streamvault/player/playback/PlaybackStallRecoveryPolicyTest.kt`
  - `player/src/test/java/com/streamvault/player/playback/PlaybackBufferPoliciesTest.kt`
- Confirm a symbol/test target before editing; many policies have direct tests.
