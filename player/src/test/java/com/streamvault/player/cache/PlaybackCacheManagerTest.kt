package com.streamvault.player.cache

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class PlaybackCacheManagerTest {

    @Test
    fun constants_haveValidDefaults() {
        assertThat(PlaybackCacheManager.CACHE_SUBDIR).isEqualTo("player_timeshift_cache")
        assertThat(PlaybackCacheManager.DEFAULT_MAX_CACHE_BYTES).isEqualTo(1024L * 1024L * 1024L)
    }

    @Test
    fun isInitialized_defaultsToFalse() {
        val dummyContext = DummyContext()
        val manager = PlaybackCacheManager(dummyContext, maxCacheBytes = 10L * 1024L * 1024L)
        assertThat(manager.isInitialized()).isFalse()
    }

    private class DummyContext : android.content.ContextWrapper(null) {
        private val tempDir = File(System.getProperty("java.io.tmpdir"), "streamvault_test_cache").apply { mkdirs() }
        override fun getCacheDir(): File = tempDir
    }
}
