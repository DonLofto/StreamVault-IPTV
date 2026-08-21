package com.streamvault.app.diagnostics

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeDiagnosticsManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun appendSnapshot_rotatesWhenByteLimitReached() {
        val dir = temporaryFolder.newFolder("diag")
        val target = File(dir, DiagnosticLogRotation.PREFIX)
        val rotation = DiagnosticLogRotation(
            maxBytes = 1_024L,
            maxFiles = 2
        )
        val chunk = "x".repeat(512) // half the limit; two appends exceed it

        repeat(6) { rotation.append(target, chunk) }

        val files = rotation.diagnosticFiles(dir)
        assertThat(files.size).isAtMost(2)
        assertThat(rotation.totalBytes(dir)).isLessThan(3 * 1_024L)
        // The active file is always small (started fresh after rotation).
        assertThat(target.length()).isLessThan(1_024L)
    }

    @Test
    fun appendSnapshot_underLimitKeepsSingleFile() {
        val dir = temporaryFolder.newFolder("diag")
        val target = File(dir, DiagnosticLogRotation.PREFIX)
        val rotation = DiagnosticLogRotation(maxBytes = 10_000L, maxFiles = 2)

        repeat(3) { rotation.append(target, "snapshot-line") }

        assertThat(rotation.diagnosticFiles(dir)).hasSize(1)
        assertThat(target.length()).isGreaterThan(0L)
    }

    @Test
    fun rotatedFilesAreWithinMaxFiles() {
        val dir = temporaryFolder.newFolder("diag")
        val target = File(dir, DiagnosticLogRotation.PREFIX)
        val rotation = DiagnosticLogRotation(maxBytes = 64L, maxFiles = 2)

        repeat(20) { rotation.append(target, "y".repeat(32)) }

        assertThat(rotation.diagnosticFiles(dir).size).isAtMost(2)
    }
}
