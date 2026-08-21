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

    fun isInitialized(): Boolean {
        synchronized(lock) {
            return simpleCache != null
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
        const val CACHE_SUBDIR = "player_timeshift_cache"
        const val DEFAULT_MAX_CACHE_BYTES = 1024L * 1024L * 1024L // 1 GB
    }
}
