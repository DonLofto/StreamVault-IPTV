package com.streamvault.player.timeshift

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimeshiftDiskManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("cache")
        context = mock()
        whenever(context.cacheDir).thenReturn(cacheDir)
    }

    @Test
    fun newlyWrittenFile_immediatelyReflectedInUsage() {
        val manager = TimeshiftDiskManager(context, maxBudgetBytes = 10_000L)
        val timeshiftDir = manager.timeshiftDir.apply { mkdirs() }
        val sessionDir = File(timeshiftDir, "session-1").apply { mkdirs() }

        assertThat(manager.currentUsageBytes()).isEqualTo(0L)

        val chunk1 = File(sessionDir, "chunk-1.ts")
        chunk1.writeBytes(ByteArray(1000))
        manager.recordFileMutation()

        assertThat(manager.currentUsageBytes()).isEqualTo(1000L)

        val chunk2 = File(sessionDir, "chunk-2.ts")
        chunk2.writeBytes(ByteArray(2500))
        manager.recordFileMutation()

        assertThat(manager.currentUsageBytes()).isEqualTo(3500L)
    }

    @Test
    fun deletedFile_immediatelyReducesUsage() {
        val manager = TimeshiftDiskManager(context, maxBudgetBytes = 10_000L)
        val timeshiftDir = manager.timeshiftDir.apply { mkdirs() }
        val sessionDir = File(timeshiftDir, "session-1").apply { mkdirs() }

        val chunk = File(sessionDir, "chunk-1.ts")
        chunk.writeBytes(ByteArray(2000))
        manager.recordFileMutation()
        assertThat(manager.currentUsageBytes()).isEqualTo(2000L)

        chunk.delete()
        manager.recordFileMutation()
        assertThat(manager.currentUsageBytes()).isEqualTo(0L)
    }

    @Test
    fun hardLinkedSnapshot_isNotChargedTwice() {
        val manager = TimeshiftDiskManager(context, maxBudgetBytes = 50_000L)
        val timeshiftDir = manager.timeshiftDir.apply { mkdirs() }
        val sessionDir = File(timeshiftDir, "session-1").apply { mkdirs() }
        val snapshotDir = File(sessionDir, "snapshot-1").apply { mkdirs() }

        val originalSegment = File(sessionDir, "segment-1.ts")
        originalSegment.writeBytes(ByteArray(4000))
        manager.recordFileMutation()

        assertThat(manager.currentUsageBytes()).isEqualTo(4000L)

        val linkedSegment = File(snapshotDir, "segment-1.ts")
        try {
            Files.createLink(linkedSegment.toPath(), originalSegment.toPath())
            manager.recordFileMutation()
            // Physical inode is the same, so usage should still be 4000 bytes, NOT 8000
            assertThat(manager.currentUsageBytes()).isEqualTo(4000L)
        } catch (_: UnsupportedOperationException) {
            // Hard links not supported on filesystem under test, skip assertion
        }
    }

    @Test
    fun concurrentReservations_cannotOversubscribeGlobalBudget() {
        val manager = TimeshiftDiskManager(context, maxBudgetBytes = 10_000L)

        assertThat(manager.reserve(6_000L)).isTrue()
        assertThat(manager.currentReservedBytes()).isEqualTo(6_000L)

        // Cannot reserve 5000 more when 6000 reserved out of 10000
        assertThat(manager.reserve(5_000L)).isFalse()

        // Can reserve 4000
        assertThat(manager.reserve(4_000L)).isTrue()
        assertThat(manager.currentReservedBytes()).isEqualTo(10_000L)

        // When full, cannot reserve even 1 byte
        assertThat(manager.reserve(1L)).isFalse()

        // Releasing 6000 allows new reservation of 5000
        manager.release(6_000L)
        assertThat(manager.reserve(5_000L)).isTrue()
    }

    @Test
    fun evictLruUntilWithinBudget_evictsOldestFirst() {
        val manager = TimeshiftDiskManager(context, maxBudgetBytes = 10_000L)
        val timeshiftDir = manager.timeshiftDir.apply { mkdirs() }

        val oldDir = File(timeshiftDir, "stale-old").apply { mkdirs() }
        File(oldDir, "chunk.ts").writeBytes(ByteArray(5_000))
        oldDir.setLastModified(1000L)

        val newDir = File(timeshiftDir, "stale-new").apply { mkdirs() }
        File(newDir, "chunk.ts").writeBytes(ByteArray(5_000))
        newDir.setLastModified(5000L)

        manager.recordFileMutation()
        assertThat(manager.currentUsageBytes()).isEqualTo(10_000L)

        // Target is 80% of 10_000 = 8_000. Evicting oldDir removes 5_000, bringing usage to 5_000 <= 8_000
        manager.evictLruUntilWithinBudget(activeSessionDir = null)

        assertThat(oldDir.exists()).isFalse()
        assertThat(newDir.exists()).isTrue()
        assertThat(manager.currentUsageBytes()).isEqualTo(5_000L)
    }
}
