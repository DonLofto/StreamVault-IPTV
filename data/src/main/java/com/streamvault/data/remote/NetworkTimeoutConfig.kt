package com.streamvault.data.remote

object NetworkTimeoutConfig {
    const val CONNECT_TIMEOUT_SECONDS = 30L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 30L
    // EPG files can be large and served from slow hosts — allow more time per read.
    const val EPG_READ_TIMEOUT_SECONDS = 120L
    const val EPG_MAX_SIZE_BYTES = 200L * 1_048_576 // 200 MB
    // H5: decompressed-byte ceiling for gzip XMLTV (a 200 MB compressed body may expand
    // far beyond itself) and a programme-count ceiling for oversized feeds.
    const val EPG_MAX_DECOMPRESSED_BYTES = 400L * 1_048_576 // 400 MB expanded
    const val EPG_MAX_PROGRAMMES = 2_000_000
    const val XTREAM_SEGMENTED_READ_TIMEOUT_SECONDS = 45L
    const val XTREAM_SEGMENTED_WRITE_TIMEOUT_SECONDS = 45L
    const val XTREAM_SEGMENTED_CALL_TIMEOUT_SECONDS = 50L
    const val XTREAM_HEAVY_READ_TIMEOUT_SECONDS = 300L
    const val XTREAM_HEAVY_WRITE_TIMEOUT_SECONDS = 60L
    const val XTREAM_HEAVY_CALL_TIMEOUT_SECONDS = 330L

    /**
     * A38 - total-call ceiling for the background-sync client.
     *
     * Read and write timeouts bound individual socket operations, not the call: a server that
     * dribbles one byte just inside the read window keeps a sync alive indefinitely, holding one
     * of the client's eight slots. This caps the whole call instead.
     *
     * Only the background-sync client gets it. The main client serves EPG under a 200 MB budget
     * (EPG_READ_TIMEOUT_SECONDS is already 120 s), so a blanket total-call cap there could abort a
     * legitimate slow download. 330 s matches XTREAM_HEAVY_CALL_TIMEOUT_SECONDS, which is the
     * largest single transfer the sync path is expected to make.
     */
    const val BACKGROUND_SYNC_CALL_TIMEOUT_SECONDS = 330L
}
