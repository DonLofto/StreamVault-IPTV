package com.streamvault.data.remote.trakt

import com.google.gson.annotations.SerializedName

data class TraktDeviceCodeResponseDto(
    @SerializedName("device_code") val deviceCode: String,
    @SerializedName("user_code") val userCode: String,
    @SerializedName("verification_url") val verificationUrl: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("interval") val interval: Int
)

data class TraktTokenResponseDto(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("scope") val scope: String,
    @SerializedName("created_at") val createdAt: Long
)

data class TraktUserDto(
    @SerializedName("username") val username: String,
    @SerializedName("name") val name: String?,
    @SerializedName("vip") val vip: Boolean?
)

data class TraktIdsDto(
    @SerializedName("trakt") val trakt: Long? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("imdb") val imdb: String? = null,
    @SerializedName("tmdb") val tmdb: Long? = null
)

data class TraktMovieDto(
    @SerializedName("title") val title: String,
    @SerializedName("year") val year: Int? = null,
    @SerializedName("ids") val ids: TraktIdsDto = TraktIdsDto()
)

data class TraktShowDto(
    @SerializedName("title") val title: String,
    @SerializedName("year") val year: Int? = null,
    @SerializedName("ids") val ids: TraktIdsDto = TraktIdsDto()
)

data class TraktEpisodeDto(
    @SerializedName("season") val season: Int,
    @SerializedName("number") val number: Int,
    @SerializedName("title") val title: String? = null,
    @SerializedName("ids") val ids: TraktIdsDto = TraktIdsDto()
)

data class TraktScrobbleRequestDto(
    @SerializedName("movie") val movie: TraktMovieDto? = null,
    @SerializedName("show") val show: TraktShowDto? = null,
    @SerializedName("episode") val episode: TraktEpisodeDto? = null,
    @SerializedName("progress") val progress: Double,
    @SerializedName("app_version") val appVersion: String = "1.0.17"
)

data class TraktScrobbleResponseDto(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("action") val action: String? = null,
    @SerializedName("progress") val progress: Double? = null
)
