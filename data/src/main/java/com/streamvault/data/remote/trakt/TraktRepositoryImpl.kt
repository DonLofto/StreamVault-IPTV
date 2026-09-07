package com.streamvault.data.remote.trakt

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamvault.domain.trakt.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private val Context.traktDataStore by preferencesDataStore(name = "trakt_preferences")

@Singleton
class TraktRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktApiService: TraktApiService
) : TraktRepository {

    private companion object {
        private const val TAG = "TraktRepository"
        // Public open-source Trakt API credentials for StreamVault TV
        const val DEFAULT_CLIENT_ID = "e8c8df634f1ecb90f42337d1cfc9f4d1c472be3f5adce9ea7173b22cfdcbb416"
        const val DEFAULT_CLIENT_SECRET = "8d3e911eb31a89c9c30f40cfdcf2d9c0250df75adad568fa0999553f191f61f5"

        val KEY_ACCESS_TOKEN = stringPreferencesKey("trakt_access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("trakt_refresh_token")
        val KEY_EXPIRES_AT = longPreferencesKey("trakt_expires_at")
        val KEY_USERNAME = stringPreferencesKey("trakt_username")
    }

    private val _authState = MutableStateFlow(TraktAuthState())
    override val authState: StateFlow<TraktAuthState> = _authState.asStateFlow()

    private val pollingMutex = Mutex()
    private val pollingGeneration = AtomicLong(0L)
    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        scope.launch {
            loadSavedAuthState()
        }
    }

    private suspend fun loadSavedAuthState() {
        val prefs = context.traktDataStore.data.first()
        val accessToken = prefs[KEY_ACCESS_TOKEN]
        val username = prefs[KEY_USERNAME]
        if (!accessToken.isNullOrBlank()) {
            _authState.value = TraktAuthState(
                isAuthenticated = true,
                username = username ?: "Trakt User"
            )
        }
    }

    override suspend fun generateDeviceCode(): Result<TraktAuthState> = withContext(Dispatchers.IO) {
        pollingMutex.withLock {
            cancelDeviceCodeAuthLocked()
            try {
                val requestBody = mapOf("client_id" to DEFAULT_CLIENT_ID)
                val response = traktApiService.getDeviceCode(requestBody)
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val newState = TraktAuthState(
                        isAuthenticated = false,
                        userCode = body.userCode,
                        verificationUrl = body.verificationUrl,
                        expiresInSeconds = body.expiresIn,
                        intervalSeconds = body.interval.coerceAtLeast(5),
                        isPendingAuthorization = true
                    )
                    _authState.value = newState
                    Result.success(newState)
                } else {
                    Result.failure(Exception("Failed to request Trakt device code: ${response.code()} ${response.message()}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating device code", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun pollForAuthorization(
        deviceCode: String,
        intervalSeconds: Int,
        expiresInSeconds: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val currentGen: Long
        pollingMutex.withLock {
            cancelDeviceCodeAuthLocked()
            currentGen = pollingGeneration.incrementAndGet()
            pollingJob = coroutineContext.job
        }
        val startTime = System.currentTimeMillis()
        val maxDurationMs = expiresInSeconds * 1000L
        val intervalMs = intervalSeconds * 1000L

        try {
            while (System.currentTimeMillis() - startTime < maxDurationMs) {
                delay(intervalMs)
                currentCoroutineContext().ensureActive()
                if (pollingGeneration.get() != currentGen) {
                    return@withContext Result.failure(CancellationException("Trakt authentication superseded"))
                }
                try {
                    val requestBody = mapOf(
                        "code" to deviceCode,
                        "client_id" to DEFAULT_CLIENT_ID,
                        "client_secret" to DEFAULT_CLIENT_SECRET
                    )
                    val response = traktApiService.getDeviceToken(requestBody)
                    currentCoroutineContext().ensureActive()
                    if (pollingGeneration.get() != currentGen) {
                        return@withContext Result.failure(CancellationException("Trakt authentication superseded"))
                    }
                    when (response.code()) {
                        200 -> {
                            val token = response.body()
                            if (token != null) {
                                saveTokens(token)
                                fetchAndSaveUserProfile(token.accessToken)
                                pollingMutex.withLock {
                                    if (pollingGeneration.get() == currentGen) {
                                        pollingJob = null
                                    }
                                }
                                return@withContext Result.success(Unit)
                            }
                        }
                        400 -> {
                            // Pending authorization, continue polling
                            Log.d(TAG, "Trakt device token pending...")
                        }
                        404, 409, 410 -> {
                            // Expired or denied
                            pollingMutex.withLock {
                                if (pollingGeneration.get() == currentGen) {
                                    cancelDeviceCodeAuthLocked()
                                }
                            }
                            return@withContext Result.failure(Exception("Trakt authentication expired or denied"))
                        }
                        429 -> {
                            // Rate limit, slow down
                            delay(5000L)
                        }
                        else -> {
                            Log.w(TAG, "Unexpected response during polling: ${response.code()}")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error", e)
                }
            }
            pollingMutex.withLock {
                if (pollingGeneration.get() == currentGen) {
                    cancelDeviceCodeAuthLocked()
                }
            }
            Result.failure(Exception("Trakt authorization timed out"))
        } catch (e: CancellationException) {
            pollingMutex.withLock {
                if (pollingGeneration.get() == currentGen) {
                    cancelDeviceCodeAuthLocked()
                }
            }
            throw e
        }
    }

    private suspend fun saveTokens(tokenDto: TraktTokenResponseDto) {
        val expiresAt = System.currentTimeMillis() + (tokenDto.expiresIn * 1000L)
        context.traktDataStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN] = tokenDto.accessToken
            prefs[KEY_REFRESH_TOKEN] = tokenDto.refreshToken
            prefs[KEY_EXPIRES_AT] = expiresAt
        }
    }

    private suspend fun fetchAndSaveUserProfile(accessToken: String) {
        try {
            val response = traktApiService.getCurrentUser(
                authorization = "Bearer $accessToken",
                clientId = DEFAULT_CLIENT_ID
            )
            if (response.isSuccessful && response.body() != null) {
                val username = response.body()!!.username
                context.traktDataStore.edit { prefs ->
                    prefs[KEY_USERNAME] = username
                }
                _authState.value = TraktAuthState(
                    isAuthenticated = true,
                    username = username
                )
            } else {
                _authState.value = TraktAuthState(
                    isAuthenticated = true,
                    username = "Trakt User"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch user profile", e)
            _authState.value = TraktAuthState(
                isAuthenticated = true,
                username = "Trakt User"
            )
        }
    }

    override suspend fun cancelDeviceCodeAuth() {
        pollingMutex.withLock {
            cancelDeviceCodeAuthLocked()
        }
    }

    private suspend fun cancelDeviceCodeAuthLocked() {
        pollingGeneration.incrementAndGet()
        pollingJob?.cancelAndJoin()
        pollingJob = null
        if (!_authState.value.isAuthenticated) {
            _authState.value = TraktAuthState()
        }
    }

    override suspend fun disconnect() {
        cancelDeviceCodeAuth()
        context.traktDataStore.edit { prefs ->
            prefs.remove(KEY_ACCESS_TOKEN)
            prefs.remove(KEY_REFRESH_TOKEN)
            prefs.remove(KEY_EXPIRES_AT)
            prefs.remove(KEY_USERNAME)
        }
        _authState.value = TraktAuthState()
    }

    override suspend fun refreshTokensIfNeeded(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val prefs = context.traktDataStore.data.first()
            val refreshToken = prefs[KEY_REFRESH_TOKEN] ?: return@withContext Result.failure(Exception("No refresh token"))
            val expiresAt = prefs[KEY_EXPIRES_AT] ?: 0L

            // Refresh if expiring within 24 hours
            if (System.currentTimeMillis() + 86400000L < expiresAt) {
                return@withContext Result.success(Unit)
            }

            val requestBody = mapOf(
                "refresh_token" to refreshToken,
                "client_id" to DEFAULT_CLIENT_ID,
                "client_secret" to DEFAULT_CLIENT_SECRET,
                "redirect_uri" to "urn:ietf:wg:oauth:2.0:oob",
                "grant_type" to "refresh_token"
            )
            val response = traktApiService.refreshToken(requestBody)
            if (response.isSuccessful && response.body() != null) {
                saveTokens(response.body()!!)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to refresh Trakt tokens"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed refreshing token", e)
            Result.failure(e)
        }
    }

    override suspend fun scrobble(action: TraktScrobbleAction, payload: TraktScrobblePayload): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val prefs = context.traktDataStore.data.first()
            val accessToken = prefs[KEY_ACCESS_TOKEN] ?: return@withContext Result.failure(Exception("Not logged into Trakt"))

            val requestDto = TraktScrobbleRequestDto(
                movie = payload.movie?.let {
                    TraktMovieDto(
                        title = it.title,
                        year = it.year,
                        ids = TraktIdsDto(
                            trakt = it.ids.trakt,
                            slug = it.ids.slug,
                            imdb = it.ids.imdb,
                            tmdb = it.ids.tmdb
                        )
                    )
                },
                show = payload.show?.let {
                    TraktShowDto(
                        title = it.title,
                        year = it.year,
                        ids = TraktIdsDto(
                            trakt = it.ids.trakt,
                            slug = it.ids.slug,
                            imdb = it.ids.imdb,
                            tmdb = it.ids.tmdb
                        )
                    )
                },
                episode = payload.episode?.let {
                    TraktEpisodeDto(
                        season = it.season,
                        number = it.number,
                        title = it.title,
                        ids = TraktIdsDto(
                            trakt = it.ids.trakt,
                            slug = it.ids.slug,
                            imdb = it.ids.imdb,
                            tmdb = it.ids.tmdb
                        )
                    )
                },
                progress = payload.progress.coerceIn(0.0, 100.0),
                appVersion = payload.appVersion
            )

            val authHeader = "Bearer $accessToken"
            val response = when (action) {
                TraktScrobbleAction.START -> traktApiService.scrobbleStart(
                    authorization = authHeader,
                    clientId = DEFAULT_CLIENT_ID,
                    payload = requestDto
                )
                TraktScrobbleAction.PAUSE -> traktApiService.scrobblePause(
                    authorization = authHeader,
                    clientId = DEFAULT_CLIENT_ID,
                    payload = requestDto
                )
                TraktScrobbleAction.STOP -> traktApiService.scrobbleStop(
                    authorization = authHeader,
                    clientId = DEFAULT_CLIENT_ID,
                    payload = requestDto
                )
            }

            if (response.isSuccessful) {
                Log.d(TAG, "Trakt scrobble $action succeeded for ${payload.movie?.title ?: payload.show?.title}")
                Result.success(Unit)
            } else {
                Log.w(TAG, "Trakt scrobble $action returned ${response.code()}: ${response.message()}")
                Result.failure(Exception("Trakt scrobble failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing Trakt scrobble", e)
            Result.failure(e)
        }
    }
}
