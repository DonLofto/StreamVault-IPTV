package com.streamvault.data.remote.trakt

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

interface TraktApiService {

    @POST("oauth/device/code")
    @Headers("Content-Type: application/json")
    suspend fun getDeviceCode(
        @Body body: Map<String, String>
    ): Response<TraktDeviceCodeResponseDto>

    @POST("oauth/device/token")
    @Headers("Content-Type: application/json")
    suspend fun getDeviceToken(
        @Body body: Map<String, String>
    ): Response<TraktTokenResponseDto>

    @POST("oauth/token")
    @Headers("Content-Type: application/json")
    suspend fun refreshToken(
        @Body body: Map<String, String>
    ): Response<TraktTokenResponseDto>

    @GET("users/me")
    suspend fun getCurrentUser(
        @Header("Authorization") authorization: String,
        @Header("trakt-api-version") apiVersion: String = "2",
        @Header("trakt-api-key") clientId: String
    ): Response<TraktUserDto>

    @POST("scrobble/start")
    suspend fun scrobbleStart(
        @Header("Authorization") authorization: String,
        @Header("trakt-api-version") apiVersion: String = "2",
        @Header("trakt-api-key") clientId: String,
        @Body payload: TraktScrobbleRequestDto
    ): Response<TraktScrobbleResponseDto>

    @POST("scrobble/pause")
    suspend fun scrobblePause(
        @Header("Authorization") authorization: String,
        @Header("trakt-api-version") apiVersion: String = "2",
        @Header("trakt-api-key") clientId: String,
        @Body payload: TraktScrobbleRequestDto
    ): Response<TraktScrobbleResponseDto>

    @POST("scrobble/stop")
    suspend fun scrobbleStop(
        @Header("Authorization") authorization: String,
        @Header("trakt-api-version") apiVersion: String = "2",
        @Header("trakt-api-key") clientId: String,
        @Body payload: TraktScrobbleRequestDto
    ): Response<TraktScrobbleResponseDto>
}
