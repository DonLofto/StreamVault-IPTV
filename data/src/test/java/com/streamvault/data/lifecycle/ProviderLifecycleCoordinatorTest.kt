package com.streamvault.data.lifecycle

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderLifecycleCoordinatorTest {

    @Test
    fun `deletion waits for active work and rejects work admitted after tombstone`() = runTest {
        val coordinator = ProviderLifecycleCoordinator()
        val activeEntered = CompletableDeferred<Unit>()
        val releaseActive = CompletableDeferred<Unit>()
        val deletionEntered = CompletableDeferred<Unit>()

        val active = async {
            coordinator.withProviderOperation(7L) {
                activeEntered.complete(Unit)
                releaseActive.await()
                "active-complete"
            }
        }
        activeEntered.await()

        val deletion = async {
            coordinator.withProviderDeletion(7L) {
                deletionEntered.complete(Unit)
                "deleted"
            }
        }
        runCurrent()

        val rejected = coordinator.withProviderOperation(7L) { "must-not-run" }
        assertThat(rejected).isNull()
        assertThat(deletionEntered.isCompleted).isFalse()

        releaseActive.complete(Unit)

        assertThat(active.await()).isEqualTo("active-complete")
        assertThat(deletion.await()).isEqualTo("deleted")
        assertThat(deletionEntered.isCompleted).isTrue()
    }

    @Test
    fun `cancelling active work releases a waiting deletion without leaking admission state`() = runTest {
        val coordinator = ProviderLifecycleCoordinator()
        val activeEntered = CompletableDeferred<Unit>()
        val deletionEntered = CompletableDeferred<Unit>()

        val active = launch {
            coordinator.withProviderOperation(7L) {
                activeEntered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        activeEntered.await()

        val deletion = async {
            coordinator.withProviderDeletion(7L) {
                deletionEntered.complete(Unit)
                Unit
            }
        }
        runCurrent()
        assertThat(deletionEntered.isCompleted).isFalse()

        active.cancelAndJoin()

        assertThat(deletion.await()).isEqualTo(Unit)
        assertThat(deletionEntered.isCompleted).isTrue()
        assertThat(coordinator.withProviderOperation(7L) { "reused" }).isEqualTo("reused")
    }
}
