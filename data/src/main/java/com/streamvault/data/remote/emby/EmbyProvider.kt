package com.streamvault.data.remote.emby

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.EpisodeEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.Result
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EmbyAuthenticatedSession(
    val accessToken: String,
    val userId: String,
    val userName: String
)

data class EmbyAuthenticateRequestDto(
    @SerializedName("Username") val username: String,
    @SerializedName("Pw") val password: String
)

data class EmbyUserDto(
    @SerializedName("Id") val id: String? = null,
    @SerializedName("Name") val name: String? = null
)

data class EmbyAuthenticationResultDto(
    @SerializedName("AccessToken") val accessToken: String? = null,
    @SerializedName("User") val user: EmbyUserDto? = null
)

data class EmbyItemDto(
    @SerializedName("Id") val id: String? = null,
    @SerializedName("Name") val name: String? = null,
    @SerializedName("Overview") val overview: String? = null,
    @SerializedName("Type") val type: String? = null,
    @SerializedName("ProductionYear") val productionYear: Int? = null,
    @SerializedName("PremiereDate") val premiereDate: String? = null,
    @SerializedName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerializedName("CommunityRating") val communityRating: Float? = null,
    @SerializedName("Genres") val genres: List<String>? = null,
    @SerializedName("IndexNumber") val indexNumber: Int? = null,
    @SerializedName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerializedName("ChannelNumber") val channelNumber: String? = null,
    @SerializedName("ProviderIds") val providerIds: Map<String, String>? = null
)

data class EmbyItemsResponseDto(
    @SerializedName("Items") val items: List<EmbyItemDto>? = null,
    @SerializedName("TotalRecordCount") val totalRecordCount: Int? = null
)

@Singleton
class EmbyProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    private companion object {
        private const val MOVIE_CATEGORY_ID = 1L
        private const val SERIES_CATEGORY_ID = 2L
        private const val LIVE_CATEGORY_ID = 3L
        private const val REQUEST_TIMEOUT_SECONDS = 60L
    }

    private val itemsResponseType = object : TypeToken<EmbyItemsResponseDto>() {}.type
    private val authResultType = object : TypeToken<EmbyAuthenticationResultDto>() {}.type

    suspend fun authenticate(serverUrl: String, username: String, password: String): Result<EmbyAuthenticatedSession> {
        return try {
            val session = withContext(Dispatchers.IO) {
                authenticateSession(serverUrl, username, password)
            }
            Result.success(session)
        } catch (e: Exception) {
            Result.error("Emby authentication failed: ${e.message}", e)
        }
    }

    suspend fun fetchLiveChannels(provider: Provider): Result<List<ChannelEntity>> = try {
        val items = withContext(Dispatchers.IO) {
            fetchItems(provider, "/LiveTv/Channels", emptyMap())
        }
        val channels = items.mapIndexed { index, item ->
            val remoteId = item.id.orEmpty()
            ChannelEntity(
                streamId = stableRemoteId(remoteId),
                name = item.name ?: "Channel $index",
                logoUrl = buildEmbyImageUrl(provider.serverUrl, item.id, "Primary", provider.password),
                streamUrl = buildEmbyStreamUrl(provider.serverUrl, remoteId, provider.password),
                groupTitle = "Live TV",
                categoryId = LIVE_CATEGORY_ID,
                categoryName = "Live TV",
                number = item.channelNumber?.toIntOrNull() ?: (index + 1),
                providerId = provider.id,
                catchUpSupported = false,
                catchUpDays = 0,
                isAdult = false,
                isUserProtected = false
            )
        }
        Result.success(channels)
    } catch (e: Exception) {
        Result.error("Failed to load Emby live channels: ${e.message}", e)
    }

    suspend fun fetchMovies(provider: Provider): Result<List<MovieEntity>> = try {
        val items = withContext(Dispatchers.IO) {
            fetchItems(provider, "/Items", mapOf(
                "IncludeItemTypes" to "Movie",
                "Recursive" to "true",
                "Fields" to "Overview,ProductionYear,PremiereDate,RunTimeTicks,Genres,CommunityRating,ProviderIds"
            ))
        }
        val movies = items.mapIndexed { index, item ->
            val remoteId = item.id.orEmpty()
            val durationMinutes = item.runTimeTicks?.let { (it / 10_000_000L / 60L).toInt() } ?: 0
            val durationSeconds = item.runTimeTicks?.let { (it / 10_000_000L).toInt() } ?: 0
            MovieEntity(
                streamId = stableRemoteId(remoteId),
                name = item.name ?: "Movie $index",
                posterUrl = buildEmbyImageUrl(provider.serverUrl, item.id, "Primary", provider.password),
                backdropUrl = buildEmbyImageUrl(provider.serverUrl, item.id, "Backdrop", provider.password),
                categoryId = MOVIE_CATEGORY_ID,
                categoryName = item.genres?.firstOrNull() ?: "Movies",
                streamUrl = buildEmbyStreamUrl(provider.serverUrl, remoteId, provider.password),
                containerExtension = "mp4",
                plot = item.overview,
                genre = item.genres?.joinToString(", "),
                releaseDate = item.premiereDate?.take(10),
                duration = if (durationMinutes > 0) "$durationMinutes min" else null,
                durationSeconds = durationSeconds,
                rating = item.communityRating ?: 0f,
                year = item.productionYear?.toString(),
                tmdbId = item.providerIds?.get("Tmdb")?.toLongOrNull(),
                providerId = provider.id,
                isAdult = false
            )
        }
        Result.success(movies)
    } catch (e: Exception) {
        Result.error("Failed to load Emby movies: ${e.message}", e)
    }

    suspend fun fetchSeries(provider: Provider): Result<List<SeriesEntity>> = try {
        val items = withContext(Dispatchers.IO) {
            fetchItems(provider, "/Items", mapOf(
                "IncludeItemTypes" to "Series",
                "Recursive" to "true",
                "Fields" to "Overview,ProductionYear,PremiereDate,Genres,CommunityRating,ProviderIds"
            ))
        }
        val seriesList = items.mapIndexed { index, item ->
            val remoteId = item.id.orEmpty()
            SeriesEntity(
                seriesId = stableRemoteId(remoteId),
                providerSeriesId = remoteId,
                name = item.name ?: "Series $index",
                posterUrl = buildEmbyImageUrl(provider.serverUrl, item.id, "Primary", provider.password),
                backdropUrl = buildEmbyImageUrl(provider.serverUrl, item.id, "Backdrop", provider.password),
                categoryId = SERIES_CATEGORY_ID,
                categoryName = item.genres?.firstOrNull() ?: "Series",
                plot = item.overview,
                genre = item.genres?.joinToString(", "),
                releaseDate = item.premiereDate?.take(10),
                rating = item.communityRating ?: 0f,
                tmdbId = item.providerIds?.get("Tmdb")?.toLongOrNull(),
                providerId = provider.id,
                lastModified = System.currentTimeMillis(),
                isAdult = false
            )
        }
        Result.success(seriesList)
    } catch (e: Exception) {
        Result.error("Failed to load Emby series: ${e.message}", e)
    }

    suspend fun fetchEpisodes(provider: Provider, seriesRemoteId: String, seriesLocalId: Long): Result<List<EpisodeEntity>> = try {
        val items = withContext(Dispatchers.IO) {
            fetchItems(provider, "/Shows/$seriesRemoteId/Episodes", mapOf(
                "Fields" to "Overview,RunTimeTicks,CommunityRating,IndexNumber,ParentIndexNumber,PremiereDate"
            ))
        }
        val episodes = items.mapIndexed { index, item ->
            val remoteId = item.id.orEmpty()
            val durationMinutes = item.runTimeTicks?.let { (it / 10_000_000L / 60L).toInt() } ?: 0
            val durationSeconds = item.runTimeTicks?.let { (it / 10_000_000L).toInt() } ?: 0
            EpisodeEntity(
                episodeId = stableRemoteId(remoteId),
                title = item.name ?: "Episode ${index + 1}",
                episodeNumber = item.indexNumber ?: (index + 1),
                seasonNumber = item.parentIndexNumber ?: 1,
                streamUrl = buildEmbyStreamUrl(provider.serverUrl, remoteId, provider.password),
                containerExtension = "mp4",
                coverUrl = buildEmbyImageUrl(provider.serverUrl, item.id, "Primary", provider.password),
                plot = item.overview,
                duration = if (durationMinutes > 0) "$durationMinutes min" else null,
                durationSeconds = durationSeconds,
                rating = item.communityRating ?: 0f,
                releaseDate = item.premiereDate?.take(10),
                seriesId = seriesLocalId,
                providerId = provider.id,
                isAdult = false
            )
        }
        Result.success(episodes)
    } catch (e: Exception) {
        Result.error("Failed to load Emby episodes: ${e.message}", e)
    }

    private fun authenticateSession(serverUrl: String, username: String, password: String): EmbyAuthenticatedSession {
        val url = "${serverUrl.trimEnd('/')}/Users/AuthenticateByName"
        val payload = gson.toJson(EmbyAuthenticateRequestDto(username = username, password = password))
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("X-Emby-Authorization", buildEmbyAuthHeader())
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val body = executeRequest(request, "Emby login failed")
        val parsed = gson.fromJson<EmbyAuthenticationResultDto>(body, authResultType)
        val token = parsed.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Emby login did not return an access token")
        val userId = parsed.user?.id?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Emby login did not return a user id")
        return EmbyAuthenticatedSession(accessToken = token, userId = userId, userName = parsed.user?.name ?: username)
    }

    private fun fetchItems(provider: Provider, path: String, query: Map<String, String>): List<EmbyItemDto> {
        val url = buildUrl(provider.serverUrl, path, query)
        val request = Request.Builder()
            .url(url)
            .header("X-Emby-Authorization", buildEmbyAuthHeader(provider.password))
            .header("X-Emby-Token", provider.password)
            .header("Accept", "application/json")
            .get()
            .build()
        val body = executeRequest(request, "Emby request failed")
        if (body.isBlank()) return emptyList()
        val parsed = gson.fromJson<EmbyItemsResponseDto>(body, itemsResponseType)
        return parsed.items.orEmpty().filter { !it.id.isNullOrBlank() }
    }

    private fun buildEmbyAuthHeader(token: String? = null): String {
        val base = "MediaBrowser Client=\"StreamVault\", Device=\"AndroidTV\", DeviceId=\"StreamVault-TV\", Version=\"1.0.17\""
        return if (!token.isNullOrBlank()) {
            "$base, Token=\"$token\""
        } else {
            base
        }
    }

    private fun buildUrl(serverUrl: String, path: String, queryParams: Map<String, String>): String {
        val base = "${serverUrl.trimEnd('/')}/${path.trimStart('/')}"
        if (queryParams.isEmpty()) return base
        val queryString = queryParams.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return if (base.contains("?")) "$base&$queryString" else "$base?$queryString"
    }

    private fun buildEmbyImageUrl(serverUrl: String, itemId: String?, imageType: String, token: String): String? {
        if (itemId.isNullOrBlank()) return null
        return "${serverUrl.trimEnd('/')}/Items/$itemId/Images/$imageType?api_key=$token"
    }

    fun buildEmbyStreamUrl(serverUrl: String, itemId: String, token: String): String {
        return "${serverUrl.trimEnd('/')}/Videos/$itemId/stream?static=true&api_key=$token"
    }

    private fun stableRemoteId(value: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (digest[i].toLong() and 0xff)
        return result and Long.MAX_VALUE
    }

    private fun executeRequest(request: Request, errorContext: String): String {
        val client = okHttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("$errorContext: HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }
}
