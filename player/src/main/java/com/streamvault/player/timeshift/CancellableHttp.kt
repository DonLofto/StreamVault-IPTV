package com.streamvault.player.timeshift

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * A38 - cancellable OkHttp adapter for the player module.
 *
 * This is a deliberate local copy of com.streamvault.data.remote.http.awaitResponse rather than a
 * shared one: the player module depends only on :domain, and :domain is a pure JVM module with no
 * OkHttp on its classpath. Putting an HTTP-client adapter there to share sixteen lines would make
 * the domain layer network-aware. The player module already depends on OkHttp directly, so the copy
 * costs nothing structurally.
 *
 * Cancelling the coroutine invokes [Call.cancel] immediately, so a blocked blocking-read does not
 * keep a thread parked on socket connect, headers or a timeout.
 */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel()
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}
