package com.streamvault.data.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderLifecycleCoordinator @Inject constructor() {

    private class ProviderState {
        var activeOperations: Int = 0
        var isDeleting: Boolean = false
        var zeroActiveSignal: CompletableDeferred<Unit>? = null
    }

    private val mutex = Mutex()
    private val providerStates = mutableMapOf<Long, ProviderState>()

    suspend fun <T> withProviderOperation(providerId: Long, block: suspend () -> T): T? {
        mutex.withLock {
            val state = providerStates.getOrPut(providerId) { ProviderState() }
            if (state.isDeleting) {
                return null
            }
            state.activeOperations++
        }

        try {
            return block()
        } finally {
            mutex.withLock {
                val state = providerStates[providerId]
                if (state != null) {
                    state.activeOperations--
                    if (state.activeOperations == 0) {
                        state.zeroActiveSignal?.complete(Unit)
                        if (!state.isDeleting) {
                            providerStates.remove(providerId)
                        }
                    }
                }
            }
        }
    }

    suspend fun <T> withProviderDeletion(providerId: Long, block: suspend () -> T): T {
        val signalToAwait: CompletableDeferred<Unit>? = mutex.withLock {
            val state = providerStates.getOrPut(providerId) { ProviderState() }
            state.isDeleting = true
            if (state.activeOperations > 0) {
                val signal = CompletableDeferred<Unit>()
                state.zeroActiveSignal = signal
                signal
            } else {
                null
            }
        }

        signalToAwait?.await()

        try {
            return block()
        } finally {
            mutex.withLock {
                providerStates.remove(providerId)
            }
        }
    }
}
