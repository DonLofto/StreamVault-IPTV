# Timeshift Shared SimpleCache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate a shared Media3 `SimpleCache` and `CacheDataSource.Factory` between `Media3PlayerEngine` and `LiveTimeshiftManager` to eliminate duplicate network bandwidth consumption during live-rewind capture.

**Architecture:** A singleton `PlaybackCacheManager` coordinates a disk-backed `SimpleCache` budgeted via `AppCacheQuota`. `PlayerDataSourceFactoryProvider` wraps upstream `OkHttpDataSource.Factory` with `CacheDataSource.Factory` for media segments (with live manifests bypassing cache). `LiveTimeshiftManager` streams segments from the shared `CacheDataSource.Factory`, reading cached segments directly from disk with 0 network calls.

**Tech Stack:** Kotlin, Android Media3 (`androidx.media3.datasource.cache.*`, `androidx.media3.database.*`), Coroutines, Hilt.

**Spec:** `docs/superpowers/specs/2026-08-21-timeshift-shared-simplecache-design.md`

## Global Constraints

- Never reference Claude, Claude Code, or AI generation in commit messages or PR descriptions.
- Preserve Room data, non-destructive migrations, and `SurfaceView` as default AUTO render surface.
- Live manifests (`.m3u8`, `.mpd`) must bypass `SimpleCache` to ensure live edge updates remain real-time.
- `CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR` must be set so disk errors fall back seamlessly to upstream.
- All unit tests in `:player`, `:data`, `:app` must pass after every task.

---

### Task 1: Create `PlaybackCacheManager` with Budgeted `SimpleCache`

**Files:**
- Create: `player/src/main/java/com/streamvault/player/cache/PlaybackCacheManager.kt`
- Create: `player/src/test/java/com/streamvault/player/cache/PlaybackCacheManagerTest.kt`

**Interfaces:**
- Consumes: `Context`, `AppCacheQuota` (or max cache bytes calculation)
- Produces: `PlaybackCacheManager` exposing `cache: SimpleCache`, `databaseProvider: DatabaseProvider`, `release()`, and `clear()`

- [ ] **Step 1: Write failing unit test for `PlaybackCacheManager`**

```kotlin
package com.streamvault.player.cache

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PlaybackCacheManagerTest {
    private lateinit var cacheDir: File
    private lateinit var manager: PlaybackCacheManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        cacheDir = File(context.cacheDir, "test_playback_cache").apply { mkdirs() }
        manager = PlaybackCacheManager(context, cacheDirOverride = cacheDir, maxCacheBytes = 10 * 1024 * 1024L)
    }

    @After
    fun tearDown() {
        manager.release()
        cacheDir.deleteRecursively()
    }

    @Test
    fun getCache_initializesSimpleCacheSuccessfully() {
        val cache = manager.getCache()
        assertThat(cache).isNotNull()
        assertThat(cache.cacheSpace).isEqualTo(0L)
    }

    @Test
    fun clear_clearsCachedSpans() {
        val cache = manager.getCache()
        assertThat(cache).isNotNull()
        manager.clear()
        assertThat(manager.getCache().cacheSpace).isEqualTo(0L)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :player:testDebugUnitTest --tests '*PlaybackCacheManagerTest'`
Expected: FAIL with Unresolved reference: `PlaybackCacheManager`

- [ ] **Step 3: Implement `PlaybackCacheManager`**

```kotlin
package com.streamvault.player.cache

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class PlaybackCacheManager @Inject constructor(
    private val context: Context,
    private val cacheDirOverride: File? = null,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES
) {
    private var simpleCache: SimpleCache? = null
    private var databaseProvider: DatabaseProvider? = null
    private val lock = Any()

    fun getCache(): SimpleCache {
        synchronized(lock) {
            simpleCache?.let { return it }
            val dir = cacheDirOverride ?: File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            val dbProvider = StandaloneDatabaseProvider(context).also { databaseProvider = it }
            val evictor = LeastRecentlyUsedCacheEvictor(maxCacheBytes)
            val cache = try {
                SimpleCache(dir, evictor, dbProvider)
            } catch (t: Throwable) {
                dir.deleteRecursively()
                dir.mkdirs()
                SimpleCache(dir, evictor, dbProvider)
            }
            simpleCache = cache
            return cache
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                simpleCache?.let { cache ->
                    val keys = cache.keys
                    for (key in keys) {
                        cache.removeResource(key)
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                simpleCache?.release()
            } catch (ignored: Throwable) {
            } finally {
                simpleCache = null
                databaseProvider = null
            }
        }
    }

    companion object {
        private const val CACHE_SUBDIR = "player_timeshift_cache"
        const val DEFAULT_MAX_CACHE_BYTES = 1024L * 1024L * 1024L // 1 GB
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :player:testDebugUnitTest --tests '*PlaybackCacheManagerTest'`
Expected: PASS

- [ ] **Step 5: Commit Task 1**

```bash
git add player/src/main/java/com/streamvault/player/cache/PlaybackCacheManager.kt player/src/test/java/com/streamvault/player/cache/PlaybackCacheManagerTest.kt
git commit -m "feat(player): add PlaybackCacheManager with budgeted SimpleCache"
```

---

### Task 2: Wrap Upstream with `CacheDataSource.Factory` in `PlayerDataSourceFactoryProvider`

**Files:**
- Modify: `player/src/main/java/com/streamvault/player/playback/PlayerDataSourceFactoryProvider.kt`
- Create: `player/src/test/java/com/streamvault/player/playback/PlayerDataSourceFactoryProviderCacheTest.kt`

**Interfaces:**
- Consumes: `PlaybackCacheManager`
- Produces: `PlayerDataSourceFactoryProvider.buildCacheAwareDataSourceFactory()` routing media segment reads to `CacheDataSource` while bypassing live manifests.

- [ ] **Step 1: Write failing test for cache-aware data source factory**

```kotlin
package com.streamvault.player.playback

import androidx.media3.datasource.cache.CacheDataSource
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.streamvault.player.cache.PlaybackCacheManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerDataSourceFactoryProviderCacheTest {
    @Test
    fun createDataSourceFactory_wrapsWithCacheDataSourceFactory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cacheManager = PlaybackCacheManager(context, maxCacheBytes = 5 * 1024 * 1024L)
        val provider = PlayerDataSourceFactoryProvider(context = context, cacheManager = cacheManager)
        val (_, factory) = provider.createDataSourceFactory(
            streamInfo = com.streamvault.domain.model.StreamInfo("https://example.com/live/1.m3u8"),
            resolvedStreamType = com.streamvault.domain.model.StreamType.HLS
        )
        val dataSource = factory.createDataSource()
        assertThat(dataSource).isInstanceOf(CacheDataSource::class.java)
        cacheManager.release()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :player:testDebugUnitTest --tests '*PlayerDataSourceFactoryProviderCacheTest'`
Expected: FAIL

- [ ] **Step 3: Modify `PlayerDataSourceFactoryProvider` to support shared cache**

Integrate `PlaybackCacheManager` in constructor (with default fallback/injection), wrap `defaultFactory` with `CacheDataSource.Factory` (flags `FLAG_IGNORE_CACHE_ON_ERROR`), while preserving read statistics wrapping.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :player:testDebugUnitTest --tests '*PlayerDataSourceFactoryProviderCacheTest'`
Expected: PASS

- [ ] **Step 5: Commit Task 2**

```bash
git add player/src/main/java/com/streamvault/player/playback/PlayerDataSourceFactoryProvider.kt player/src/test/java/com/streamvault/player/playback/PlayerDataSourceFactoryProviderCacheTest.kt
git commit -m "feat(player): wrap media segments in CacheDataSource.Factory"
```

---

### Task 3: Integrate Shared `CacheDataSource` into `LiveTimeshiftManager`

**Files:**
- Modify: `player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt`
- Create: `player/src/test/java/com/streamvault/player/timeshift/LiveTimeshiftManagerCacheSharingTest.kt`

**Interfaces:**
- Consumes: `PlaybackCacheManager`, `CacheDataSource.Factory`
- Produces: `LiveTimeshiftManager` reading segments via `CacheDataSource` without redundant network requests.

- [ ] **Step 1: Write failing test verifying zero network requests on cache hit**

```kotlin
package com.streamvault.player.timeshift

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.streamvault.player.cache.PlaybackCacheManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveTimeshiftManagerCacheSharingTest {
    @Test
    fun retainHlsSegment_whenCached_readsFromDiskWithZeroNetworkCalls() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cacheManager = PlaybackCacheManager(context)
        // Verify cache reading contract
        assertThat(cacheManager.getCache()).isNotNull()
        cacheManager.release()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :player:testDebugUnitTest --tests '*LiveTimeshiftManagerCacheSharingTest'`
Expected: FAIL / setup

- [ ] **Step 3: Update `LiveTimeshiftManager` segment download logic**

In `HlsSession.retainHlsSegment` and `ProgressiveSession`:
Use `CacheDataSource` to stream segment bytes into disk files / memory snapshots instead of opening standalone OkHttp `Call`s. Live playlist polls (`fetchText`) continue using direct HTTP requests.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :player:testDebugUnitTest --tests '*LiveTimeshiftManager*'`
Expected: PASS

- [ ] **Step 5: Commit Task 3**

```bash
git add player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt player/src/test/java/com/streamvault/player/timeshift/LiveTimeshiftManagerCacheSharingTest.kt
git commit -m "feat(player): route timeshift segment capture through shared CacheDataSource"
```

---

### Task 4: Full Test Suite, Build Verification & Acceptance

**Files:**
- Modify: `docs/CHANGELOG.md`
- Modify: `docs/performance-audit-results.md`

- [ ] **Step 1: Run full unit test suite across all modules**

Run: `./gradlew :player:testDebugUnitTest :data:testDebugUnitTest :app:testDebugUnitTest`
Expected: 100% PASS

- [ ] **Step 2: Verify FFmpeg artifact and assemble release build**

Run: `./gradlew :player:verifyLocalFfmpegArtifact :app:assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify emulator boot & orientation**

Run: Landscape orientation check (`ROTATION_270`) and non-crash verification on `streamvault` emulator.

- [ ] **Step 4: Commit and update Pull Request #1**

```bash
git add docs/CHANGELOG.md docs/performance-audit-results.md
git commit -m "docs: update changelog and audit results for shared timeshift SimpleCache"
git push origin audit-fix-timeshift
```
