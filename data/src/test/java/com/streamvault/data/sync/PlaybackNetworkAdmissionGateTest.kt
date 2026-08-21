package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackNetworkAdmissionGateTest {

    @Test
    fun activePlayback_defersBackgroundCatalogWork() = runTest {
        val gate = PlaybackNetworkAdmissionGate()
        gate.onPlaybackStarted()
        assertThat(gate.admissionFor(PlaybackNetworkAdmissionGate.TrafficClass.BACKGROUND_CATALOG))
            .isEqualTo(PlaybackNetworkAdmissionGate.Admission.DEFER)
        assertThat(gate.admissionFor(PlaybackNetworkAdmissionGate.TrafficClass.PLAYBACK))
            .isEqualTo(PlaybackNetworkAdmissionGate.Admission.ALLOW)
    }

    @Test
    fun activePlayback_defersBackgroundEpgWork() = runTest {
        val gate = PlaybackNetworkAdmissionGate()
        gate.onPlaybackStarted()
        assertThat(gate.admissionFor(PlaybackNetworkAdmissionGate.TrafficClass.BACKGROUND_EPG))
            .isEqualTo(PlaybackNetworkAdmissionGate.Admission.DEFER)
    }

    @Test
    fun idlePlayback_allowsBackgroundWork() = runTest {
        val gate = PlaybackNetworkAdmissionGate()
        assertThat(gate.admissionFor(PlaybackNetworkAdmissionGate.TrafficClass.BACKGROUND_CATALOG))
            .isEqualTo(PlaybackNetworkAdmissionGate.Admission.ALLOW)
        assertThat(gate.admissionFor(PlaybackNetworkAdmissionGate.TrafficClass.BACKGROUND_EPG))
            .isEqualTo(PlaybackNetworkAdmissionGate.Admission.ALLOW)
    }

    @Test
    fun playbackStopped_allowsBackgroundWorkAgain() = runTest {
        val gate = PlaybackNetworkAdmissionGate()
        gate.onPlaybackStarted()
        gate.onPlaybackStarted()
        gate.onPlaybackStopped()
        assertThat(gate.admissionFor(PlaybackNetworkAdmissionGate.TrafficClass.BACKGROUND_CATALOG))
            .isEqualTo(PlaybackNetworkAdmissionGate.Admission.DEFER)
        gate.onPlaybackStopped()
        assertThat(gate.admissionFor(PlaybackNetworkAdmissionGate.TrafficClass.BACKGROUND_CATALOG))
            .isEqualTo(PlaybackNetworkAdmissionGate.Admission.ALLOW)
    }

    @Test
    fun awaitBackgroundAdmission_returnsAllowWhenIdleImmediately() = runTest {
        val gate = PlaybackNetworkAdmissionGate()
        assertThat(
            gate.awaitBackgroundAdmission(PlaybackNetworkAdmissionGate.TrafficClass.BACKGROUND_CATALOG)
        ).isEqualTo(PlaybackNetworkAdmissionGate.Admission.ALLOW)
    }

    @Test
    fun awaitBackgroundAdmission_defersDuringPlaybackUntilStopped() = runTest {
        val gate = PlaybackNetworkAdmissionGate()
        gate.onPlaybackStarted()
        var result: PlaybackNetworkAdmissionGate.Admission? = null
        val job = backgroundScope.launch {
            result = gate.awaitBackgroundAdmission(
                PlaybackNetworkAdmissionGate.TrafficClass.BACKGROUND_CATALOG,
                maxWaitMs = 10_000L
            )
        }
        runCurrent()
        assertThat(result).isNull()
        gate.onPlaybackStopped()
        advanceTimeBy(1_000L)
        assertThat(result).isEqualTo(PlaybackNetworkAdmissionGate.Admission.ALLOW)
        job.cancel()
    }

    @Test
    fun awaitBackgroundAdmission_returnsDeferAfterMaxWait() = runTest {
        val gate = PlaybackNetworkAdmissionGate()
        gate.onPlaybackStarted()
        var result: PlaybackNetworkAdmissionGate.Admission? = null
        val job = backgroundScope.launch {
            result = gate.awaitBackgroundAdmission(
                PlaybackNetworkAdmissionGate.TrafficClass.BACKGROUND_CATALOG,
                maxWaitMs = 1_000L
            )
        }
        advanceTimeBy(2_000L)
        runCurrent()
        assertThat(result).isEqualTo(PlaybackNetworkAdmissionGate.Admission.DEFER)
        job.cancel()
    }
}
