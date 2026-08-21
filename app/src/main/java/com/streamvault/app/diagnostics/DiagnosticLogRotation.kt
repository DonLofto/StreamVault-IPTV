package com.streamvault.app.diagnostics

import java.io.File

/**
 * L2: bounded retention for debug diagnostic logs. A single growing file is rotated once
 * it exceeds [maxBytes]; the current file becomes `.1` and a fresh file starts, keeping
 * at most [maxFiles] files on disk so long debug soaks never accumulate unbounded cache.
 */
internal class DiagnosticLogRotation(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES
) {
    fun append(target: File, content: String) {
        target.parentFile?.mkdirs()
        target.appendText(content + System.lineSeparator())
        if (target.length() > maxBytes) {
            rotate(target)
        }
    }

    fun diagnosticFiles(directory: File): List<File> =
        directory.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) }
            ?.sortedBy { it.name }
            ?.toList()
            .orEmpty()

    fun totalBytes(directory: File): Long =
        diagnosticFiles(directory).sumOf { it.length() }

    private fun rotate(target: File) {
        // Drop the oldest rotated file first, then shift the chain.
        val directory = target.parentFile ?: return
        diagnosticFiles(directory)
            .filter { it.name != target.name }
            .sortedByDescending { it.name }
            .forEachIndexed { index, file ->
                if (index >= maxFiles - 1) {
                    file.delete()
                } else {
                    file.renameTo(File(directory, "${PREFIX}.${index + 2}"))
                }
            }
        target.renameTo(File(directory, "${PREFIX}.1"))
        target.parentFile?.mkdirs()
    }

    companion object {
        const val PREFIX = "runtime-memory.log"
        const val DEFAULT_MAX_BYTES = 2L * 1024L * 1024L // 2 MB
        const val DEFAULT_MAX_FILES = 2
    }
}
