package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.cache.AppCacheQuota
import com.streamvault.player.cache.PlaybackCacheManager
import com.streamvault.player.playback.LiveTimeshiftPlaybackGate
import okhttp3.OkHttpClient
import org.junit.Test
import java.io.File

class LiveTimeshiftManagerCacheSharingTest {

    @Test
    fun defaultLiveTimeshiftManager_acceptsPlaybackCacheManager() {
        val dummyContext = DummyContext()
        val cacheManager = PlaybackCacheManager(dummyContext, maxCacheBytes = 10 * 1024 * 1024L)
        val manager = DefaultLiveTimeshiftManager(
            context = dummyContext,
            okHttpClient = OkHttpClient(),
            playbackGate = LiveTimeshiftPlaybackGate(),
            appCacheQuota = AppCacheQuota(dummyContext),
            playbackCacheManager = cacheManager
        )
        assertThat(manager).isNotNull()
        cacheManager.release()
    }

    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): android.content.Context = this
        override fun getCacheDir(): File = File(System.getProperty("java.io.tmpdir"))
    }
}
