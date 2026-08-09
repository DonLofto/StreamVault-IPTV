package com.streamvault.app.ui.screens.player

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.kotlin.mock

class SeekThumbnailProviderTest {

    private val context: Context = mock()

    private fun provider() = SeekThumbnailProvider(context)

    @Test
    fun bucketPosition_roundsDownToTenSecondBucket() {
        val provider = provider()
        assertThat(provider.bucketPositionMs(0L)).isEqualTo(0L)
        assertThat(provider.bucketPositionMs(9_999L)).isEqualTo(0L)
        assertThat(provider.bucketPositionMs(10_000L)).isEqualTo(10_000L)
        assertThat(provider.bucketPositionMs(25_123L)).isEqualTo(20_000L)
        assertThat(provider.bucketPositionMs(-5L)).isEqualTo(0L)
    }

    @Test
    fun supportsFrameExtraction_rejectsPlaylistsAndAcceptsDirectMedia() {
        val provider = provider()
        assertThat(provider.supportsFrameExtraction("https://host/video.mp4")).isTrue()
        assertThat(provider.supportsFrameExtraction("https://host/live.m3u8")).isFalse()
        assertThat(provider.supportsFrameExtraction("https://host/manifest.mpd")).isFalse()
        assertThat(provider.supportsFrameExtraction("")).isFalse()
    }
}
