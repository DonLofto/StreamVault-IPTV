package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.streamvault.player.LiveAudioPcmBuffer
import com.streamvault.player.LiveAudioTap
import java.nio.ByteBuffer

@UnstableApi
internal class LiveAudioTapAudioSink(
    private val delegate: AudioSink,
    private val tapProvider: () -> LiveAudioTap?
) : AudioSink by delegate {
    private var sampleRate: Int = Format.NO_VALUE
    private var channelCount: Int = Format.NO_VALUE
    private var encoding: Int = C.ENCODING_INVALID

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        sampleRate = inputFormat.sampleRate
        channelCount = inputFormat.channelCount
        encoding = inputFormat.pcmEncoding
        delegate.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        val startPosition = buffer.position()
        // Read the tap before handleBuffer advances the buffer, and only build the read-only view
        // when a tap will actually consume it. asReadOnlyBuffer() allocated a wrapper on every
        // audio buffer (~50/s) even with no tap attached, which is the common case.
        val tap = tapProvider()
        val copySource = if (tap != null) buffer.asReadOnlyBuffer() else null
        val handled = delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (tap != null && copySource != null) {
            copyConsumedForTap(tap, copySource, startPosition, buffer.position(), presentationTimeUs)
        }
        return handled
    }

    private fun copyConsumedForTap(
        tap: LiveAudioTap,
        buffer: ByteBuffer,
        startPosition: Int,
        endPosition: Int,
        presentationTimeUs: Long
    ) {
        if (encoding != C.ENCODING_PCM_16BIT || sampleRate <= 0 || channelCount <= 0 || endPosition <= startPosition) {
            return
        }
        val adjustedPresentationTimeUs = presentationTimeUs + consumedOffsetUs(startPosition)
        val duplicate = buffer.apply {
            position(startPosition)
            limit(endPosition)
        }
        val data = ByteArray(duplicate.remaining())
        duplicate.get(data)
        tap.onPcmAudio(
            LiveAudioPcmBuffer(
                data = data,
                presentationTimeUs = adjustedPresentationTimeUs,
                sampleRate = sampleRate,
                channelCount = channelCount,
                encoding = encoding
            )
        )
    }

    private fun consumedOffsetUs(bytePosition: Int): Long {
        val frameSize = channelCount * 2
        if (frameSize <= 0 || sampleRate <= 0) return 0L
        val frames = bytePosition / frameSize
        return frames * 1_000_000L / sampleRate
    }
}
