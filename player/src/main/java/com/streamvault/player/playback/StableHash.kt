package com.streamvault.player.playback

import java.security.MessageDigest

private val sha256ThreadLocal: ThreadLocal<MessageDigest> = ThreadLocal.withInitial {
    MessageDigest.getInstance("SHA-256")
}

/** SHA-256 fingerprint truncated to 16 hex chars — stable across JVM restarts. Thread-safe via ThreadLocal. */
internal fun stableHash(input: String): String {
    val md = sha256ThreadLocal.get() ?: MessageDigest.getInstance("SHA-256")
    md.reset()
    val digest = md.digest(input.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
}
