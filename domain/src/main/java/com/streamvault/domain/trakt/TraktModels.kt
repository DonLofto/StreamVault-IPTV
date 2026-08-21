package com.streamvault.domain.trakt

data class TraktAuthState(
    val isAuthenticated: Boolean = false,
    val username: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val expiresInSeconds: Int = 0,
    val intervalSeconds: Int = 5,
    val isPendingAuthorization: Boolean = false
)

data class TraktTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val createdAt: Long = System.currentTimeMillis() / 1000
)

data class TraktIds(
    val trakt: Long? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: Long? = null
)

data class TraktMovie(
    val title: String,
    val year: Int? = null,
    val ids: TraktIds = TraktIds()
)

data class TraktShow(
    val title: String,
    val year: Int? = null,
    val ids: TraktIds = TraktIds()
)

data class TraktEpisode(
    val season: Int,
    val number: Int,
    val title: String? = null,
    val ids: TraktIds = TraktIds()
)

enum class TraktScrobbleAction {
    START,
    PAUSE,
    STOP
}

data class TraktScrobblePayload(
    val movie: TraktMovie? = null,
    val show: TraktShow? = null,
    val episode: TraktEpisode? = null,
    val progress: Double,
    val appVersion: String = "1.0.17"
)
