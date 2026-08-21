# Timeshift Shared SimpleCache Design Specification

## Overview

This specification defines the integration of a unified Media3 `SimpleCache` and `CacheDataSource.Factory` between ExoPlayer's live playback engine (`Media3PlayerEngine`) and the live-rewind timeshift capture manager (`LiveTimeshiftManager`).

The goal is to eliminate duplicate network bandwidth consumption during live playback with timeshift enabled, ensuring media segments downloaded by ExoPlayer are read directly from local disk cache with zero duplicate network requests.

---

## 1. Architectural Components

### 1.1 `PlaybackCacheManager` (`com.streamvault.player.cache`)
- **Singleton Lifecycle**: Scoped across the player module, manages a single instance of `androidx.media3.datasource.cache.SimpleCache`.
- **Storage Location**: Stored under `context.cacheDir/player_timeshift_cache/`.
- **Eviction & Budgeting**: Backed by `LeastRecentlyUsedCacheEvictor` initialized with the timeshift storage budget derived from `AppCacheQuota` (512 MB to 2 GB dynamically bounded by device storage).
- **Metadata Indexing**: Uses `androidx.media3.database.StandaloneDatabaseProvider` for persistent, crash-resilient span index caching across app and channel lifecycles.
- **Resilience**: Safely handles lock errors or corrupted databases by purging corrupt cache files and recreating the cache without throwing unhandled exceptions.

### 1.2 `PlayerDataSourceFactoryProvider` (`com.streamvault.player.playback`)
- Wraps upstream `OkHttpDataSource.Factory` in `CacheDataSource.Factory`.
- Configured with `CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR` to ensure transparent fallback to upstream network if any cache read/write error occurs.
- Media segment requests for HLS (`.ts`, `.m4s`), DASH, and Progressive streams route through `CacheDataSource`.
- Live playlists and manifests (`.m3u8`, `.mpd`) bypass `SimpleCache` to ensure real-time live-edge freshness.

### 1.3 `LiveTimeshiftManager` (`com.streamvault.player.timeshift`)
- Injects or receives the shared `CacheDataSource.Factory` / `PlaybackCacheManager`.
- Replaces raw OkHttp segment streaming in `retainHlsSegment` / `ProgressiveSession` with `CacheDataSource` streaming.
- When ExoPlayer is playing a live stream, requested segments are already present in `SimpleCache` on disk; `LiveTimeshiftManager` reads them with **0 network requests**.
- When timeshift discovers an uncached segment, `CacheDataSource` downloads it once and commits it to `SimpleCache`, allowing ExoPlayer to subsequently play it from cache.

---

## 2. Data Flow & Manifest Handling

1. **Manifest/Playlist Flow (Live Edge)**:
   - Request: `https://provider.example/live/stream.m3u8`
   - Flow: Directly to Upstream OkHttp (Bypasses `SimpleCache`).
   - Outcome: Live sequence numbers and tokens remain 100% fresh.

2. **Media Segment Flow**:
   - Request: `https://provider.example/live/segment_105.ts`
   - Flow: `CacheDataSource` checks `SimpleCache`.
   - On Hit: Returns bytes from local disk (0 network transfer).
   - On Miss: Streams from Upstream OkHttp $\rightarrow$ Writes to `SimpleCache` $\rightarrow$ Returns bytes to consumer.

---

## 3. Storage Budget & Concurrency Safety

- **Budget Allocation**: Synchronized with `AppCacheQuota`. When disk space approaches low threshold ($\le 200\text{ MB}$ free), cache quota shrinks and LRU evicts older segment spans.
- **Locking**: Media3 `SimpleCache` per-key lock semantics protect concurrent reads/writes from `Media3PlayerEngine` and `LiveTimeshiftManager`.

---

## 4. Testing & Verification Plan

1. **Unit Tests**:
   - `PlaybackCacheManagerTest`: Verify `SimpleCache` initialization, LRU eviction at quota limit, and recovery on corrupt database.
   - `PlayerDataSourceFactoryProviderTest`: Verify media segments use `CacheDataSource` and live manifests bypass cache.
   - `LiveTimeshiftManagerCacheSharingTest`: Verify zero-network segment retrieval on cache hit and fallback on miss.
2. **Build Verification**:
   - `:player:testDebugUnitTest`, `:data:testDebugUnitTest`, `:app:testDebugUnitTest`.
   - `:player:verifyLocalFfmpegArtifact` and `:app:assembleRelease`.
3. **Emulator Verification**:
   - Verify landscape orientation (`ROTATION_270`) and runtime startup on emulator.
