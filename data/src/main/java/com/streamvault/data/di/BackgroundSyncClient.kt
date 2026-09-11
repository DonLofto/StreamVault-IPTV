package com.streamvault.data.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackgroundSyncClient

/**
 * A20 - the client dedicated to Stalker portal traffic.
 *
 * Stalker used to run on the main client, so a slow portal competed with playback for the same
 * connection slots and dispatcher threads.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StalkerClient
