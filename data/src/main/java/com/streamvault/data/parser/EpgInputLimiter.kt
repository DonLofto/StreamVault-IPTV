package com.streamvault.data.parser

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * H5: typed, user-safe failure for EPG inputs that exceed configured limits.
 */
class EpgInputLimitException(message: String) : IOException(message)

/**
 * H5: bounds the bytes read from an already-decompressed XMLTV stream. Apply after
 * [XmltvParser.maybeDecompressGzip] so a small compressed body that expands beyond the
 * limit is rejected on the decompressed size rather than the compressed size.
 */
class MaxBytesInputStream(
    source: InputStream,
    private val maxBytes: Long
) : FilterInputStream(source) {
    private var bytesRead: Long = 0L

    override fun read(): Int {
        ensureWithinLimit(1)
        return super.read().also { if (it >= 0) bytesRead++ }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        ensureWithinLimit(len)
        return super.read(b, off, len).also { if (it > 0) bytesRead += it }
    }

    override fun skip(n: Long): Long {
        ensureWithinLimit(n.toInt())
        return super.skip(n).also { skipped -> if (skipped > 0) bytesRead += skipped }
    }

    private fun ensureWithinLimit(aboutToRead: Int) {
        if (bytesRead + aboutToRead > maxBytes) {
            throw EpgInputLimitException("EPG content exceeds decompressed size limit")
        }
    }
}
