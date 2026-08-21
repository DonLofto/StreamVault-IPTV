package com.streamvault.app.tvinput

/**
 * B6: monotonic tune sequencing for the TIF session. The latest tune wins; a tune whose
 * resolution finishes after a newer tune is discarded and must not apply player state.
 */
internal class TuneSequencer {
    private var generation = 0L

    /** Returns the generation for a new tune and cancels any pending older tune. */
    fun next(): Long = ++generation

    val latestGeneration: Long
        get() = generation

    fun isCurrent(generation: Long): Boolean = generation == this.generation
}
