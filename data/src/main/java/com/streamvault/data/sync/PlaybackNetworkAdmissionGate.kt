package com.streamvault.data.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * H6: process-scoped playback admission gate. While the player is active, background
 * catalog/EPG work defers so playback segments keep priority on shared Wi-Fi, host
 * slots, and disk. The player lifecycle signals [onPlaybackStarted]/[onPlaybackStopped];
 * background workers call [awaitBackgroundAdmission] before large downloads.
 */
@Singleton
class PlaybackNetworkAdmissionGate @Inject constructor() {

    enum class TrafficClass {
        PLAYBACK,
        BACKGROUND_CATALOG,
        BACKGROUND_EPG
    }

    enum class Admission {
        ALLOW,
        DEFER
    }

    private val _activePlaybackCount = MutableStateFlow(0)
    val activePlaybackCount: StateFlow<Int> = _activePlaybackCount.asStateFlow()

    val isPlaybackActive: Boolean
        get() = _activePlaybackCount.value > 0

    fun onPlaybackStarted(providerId: Long = 0L) {
        _activePlaybackCount.update { it + 1 }
        if (providerId > 0L) {
            com.streamvault.data.remote.stalker.StalkerTrafficCoordinator.notePlaybackStarted(providerId)
        }
    }

    fun onPlaybackStopped(providerId: Long = 0L) {
        _activePlaybackCount.update { it.coerceAtLeast(1) - 1 }
        if (providerId > 0L) {
            com.streamvault.data.remote.stalker.StalkerTrafficCoordinator.notePlaybackStopped(providerId)
        }
    }

    fun admissionFor(trafficClass: TrafficClass): Admission = when (trafficClass) {
        TrafficClass.PLAYBACK -> Admission.ALLOW
        TrafficClass.BACKGROUND_CATALOG,
        TrafficClass.BACKGROUND_EPG -> if (_activePlaybackCount.value > 0) Admission.DEFER else Admission.ALLOW
    }

    /**
     * Suspends until playback is no longer active. Bounded per-call wait; callers that
     * exceed the window should treat the work as deferred/retryable rather than blocking
     * the worker indefinitely.
     */
    suspend fun awaitBackgroundAdmission(
        trafficClass: TrafficClass = TrafficClass.BACKGROUND_CATALOG,
        maxWaitMs: Long = 30_000L,
        pollMs: Long = 250L
    ): Admission {
        if (admissionFor(trafficClass) == Admission.ALLOW) return Admission.ALLOW
        val maxPolls = (maxWaitMs / pollMs).coerceAtLeast(1L)
        repeat(maxPolls.toInt()) {
            currentCoroutineContext().ensureActive()
            delay(pollMs)
            if (admissionFor(trafficClass) == Admission.ALLOW) return Admission.ALLOW
        }
        return Admission.DEFER
    }

    fun resetForTests() {
        _activePlaybackCount.value = 0
    }
}
