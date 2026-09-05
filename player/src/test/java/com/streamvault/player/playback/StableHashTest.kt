package com.streamvault.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StableHashTest {

    @Test
    fun stableHash_producesConsistent16HexChars() {
        val input = "https://example.com/live/stream.m3u8|Channel 1|widevine"
        val hash1 = stableHash(input)
        val hash2 = stableHash(input)
        assertEquals(hash1, hash2)
        assertEquals(16, hash1.length)
        assert(hash1.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun stableHash_isThreadSafeUnderConcurrentAccess() {
        runBlocking(Dispatchers.Default) {
            val inputs = (1..100).map { "input-$it" }
            val expected = inputs.associateWith { stableHash(it) }

            val jobs = (1..50).map {
                async {
                    for (input in inputs) {
                        val result = stableHash(input)
                        assertEquals(expected[input], result)
                    }
                }
            }
            jobs.awaitAll()
        }
    }
}
