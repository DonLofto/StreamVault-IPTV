package com.streamvault.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Marks the dispatcher used for CPU-bound work taken off the UI thread.
 *
 * Injected rather than referenced as [Dispatchers.Default] directly, so tests can drive that
 * work with a test dispatcher; a hard-coded [Dispatchers.Default] runs on a real background
 * thread that a coroutine test scheduler cannot advance, which makes the paths untestable.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {
    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
