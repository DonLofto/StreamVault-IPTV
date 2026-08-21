package com.streamvault.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.player.cache.PlaybackCacheManager
import okhttp3.OkHttpClient
import org.junit.Test
import java.io.File

@OptIn(UnstableApi::class)
class PlayerDataSourceFactoryProviderCacheTest {

    @Test
    fun createFactory_withoutCacheManager_usesDefaultFactory() {
        val dummyContext = DummyContext()
        val provider = PlayerDataSourceFactoryProvider(
            context = dummyContext,
            baseClient = OkHttpClient(),
            cacheManager = null
        )
        val (_, factory) = provider.createFactory(
            streamInfo = StreamInfo("https://example.com/live/stream.m3u8"),
            resolvedStreamType = ResolvedStreamType.HLS
        )
        assertThat(factory).isNotNull()
    }

    @Test
    fun createFactory_withCacheManager_returnsCacheAwareFactory() {
        val dummyContext = DummyContext()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "sv_test_cache_ds_${System.nanoTime()}").apply { mkdirs() }
        val cacheManager = PlaybackCacheManager(dummyContext, cacheDirOverride = tempDir, maxCacheBytes = 10 * 1024 * 1024L)
        val provider = PlayerDataSourceFactoryProvider(
            context = dummyContext,
            baseClient = OkHttpClient(),
            cacheManager = cacheManager
        )
        val (_, factory) = provider.createFactory(
            streamInfo = StreamInfo("https://example.com/live/stream.m3u8"),
            resolvedStreamType = ResolvedStreamType.HLS
        )
        assertThat(factory).isNotNull()
        cacheManager.release()
        tempDir.deleteRecursively()
    }

    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): android.content.Context = this
        override fun getCacheDir(): File = File(System.getProperty("java.io.tmpdir"))
    }
}
