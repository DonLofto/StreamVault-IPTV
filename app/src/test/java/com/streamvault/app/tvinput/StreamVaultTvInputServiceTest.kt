package com.streamvault.app.tvinput

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamVaultTvInputServiceTest {

    @Test
    fun newerTuneWinsWhenOlderResolutionFinishesLast() {
        val sequencer = TuneSequencer()
        val tuneA = sequencer.next()
        val tuneB = sequencer.next()

        // B's resolution finishes first and is still current.
        assertThat(sequencer.isCurrent(tuneB)).isTrue()
        // A finishes last; it is stale and must not apply player state.
        assertThat(sequencer.isCurrent(tuneA)).isFalse()
    }

    @Test
    fun singleTuneRemainsCurrent() {
        val sequencer = TuneSequencer()
        val generation = sequencer.next()
        assertThat(sequencer.isCurrent(generation)).isTrue()
        assertThat(sequencer.latestGeneration).isEqualTo(generation)
    }

    @Test
    fun generationAdvancesMonotonically() {
        val sequencer = TuneSequencer()
        val first = sequencer.next()
        val second = sequencer.next()
        assertThat(second).isGreaterThan(first)
        assertThat(sequencer.latestGeneration).isEqualTo(second)
    }
}
