package com.streamvault.domain.trakt

import kotlinx.coroutines.flow.StateFlow

interface TraktRepository {
    val authState: StateFlow<TraktAuthState>

    suspend fun generateDeviceCode(): Result<TraktAuthState>
    suspend fun pollForAuthorization(deviceCode: String, intervalSeconds: Int, expiresInSeconds: Int): Result<Unit>
    suspend fun cancelDeviceCodeAuth()
    suspend fun disconnect()

    suspend fun scrobble(action: TraktScrobbleAction, payload: TraktScrobblePayload): Result<Unit>
    suspend fun refreshTokensIfNeeded(): Result<Unit>
}
