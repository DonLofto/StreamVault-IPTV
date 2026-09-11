package com.streamvault.app.di

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.streamvault.app.BuildConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.streamvault.data.remote.NetworkTimeoutConfig
import com.streamvault.data.remote.http.DefaultUserAgentInterceptor
import com.streamvault.data.remote.http.buildAppRequestProfile
import com.streamvault.data.remote.http.buildAppUserAgent
import com.streamvault.data.remote.stalker.OkHttpStalkerApiService
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.parser.XmltvParser
import com.streamvault.player.AudioCompatibilityMemoryStore
import com.streamvault.player.Media3PlayerEngine
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlaybackSupportSnapshotStore
import com.streamvault.player.cache.AppCacheQuota
import com.streamvault.data.di.BackgroundSyncClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit.SECONDS
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideAppCacheQuota(
        @ApplicationContext context: Context
    ): AppCacheQuota = AppCacheQuota(context)

    /**
     * One cache for both clients.
     *
     * Previously each client built its own Cache over the SAME directory, so two DiskLruCache
     * instances maintained one journal - interleaved writers, orphaned entries, and a full
     * directory rescan from rebuildJournal() after an unclean kill. A single instance also means
     * one LRU enforcing one budget instead of two LRUs sharing it.
     */
    @Provides
    @Singleton
    fun provideHttpCache(
        @ApplicationContext context: Context,
        appCacheQuota: AppCacheQuota
    ): Cache = Cache(
        directory = File(context.cacheDir, "streamvault_http_cache"),
        maxSize = appCacheQuota.budgets.httpCacheBytes
    )

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        httpCache: Cache
    ): OkHttpClient {
        val appUserAgent = buildAppUserAgent(BuildConfig.VERSION_NAME)
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val loggingLevel = if (isDebuggable) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }

        val httpLogger = HttpLoggingInterceptor { message ->
            Log.d("OkHttp", XtreamUrlFactory.sanitizeLogMessage(message))
        }.apply {
            level = loggingLevel
        }

        return OkHttpClient.Builder()
            .cache(httpCache)
            .connectTimeout(NetworkTimeoutConfig.CONNECT_TIMEOUT_SECONDS, SECONDS)
            .readTimeout(NetworkTimeoutConfig.READ_TIMEOUT_SECONDS, SECONDS)
            .writeTimeout(NetworkTimeoutConfig.WRITE_TIMEOUT_SECONDS, SECONDS)
            .addInterceptor(DefaultUserAgentInterceptor(appUserAgent))
            .addInterceptor(httpLogger)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(10, 5, java.util.concurrent.TimeUnit.MINUTES)) // Allow more idle connections
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 10 // Increase host limit for Multi-View
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideXtreamApiService(okHttpClient: OkHttpClient, xtreamJson: Json): XtreamApiService =
        OkHttpXtreamApiService(
            client = okHttpClient,
            json = xtreamJson,
            defaultRequestProfile = buildAppRequestProfile(BuildConfig.VERSION_NAME, ownerTag = "app/xtream")
        )

    /**
     * H6: background sync traffic class. Bounded dispatcher and per-host concurrency so
     * catalog/EPG work never starves the playback client's slots on shared Wi-Fi or host
     * connections. Authentication, TLS, cache, and timeouts stay consistent with the main
     * client; only concurrency capacity differs.
     */
    @Provides
    @Singleton
    @BackgroundSyncClient
    fun provideBackgroundSyncClient(
        @ApplicationContext context: Context,
        httpCache: Cache
    ): OkHttpClient {
        val appUserAgent = buildAppUserAgent(BuildConfig.VERSION_NAME)
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val loggingLevel = if (isDebuggable) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        val httpLogger = HttpLoggingInterceptor { message ->
            Log.d("OkHttp", XtreamUrlFactory.sanitizeLogMessage(message))
        }.apply {
            level = loggingLevel
        }
        return OkHttpClient.Builder()
            .cache(httpCache)
            .connectTimeout(NetworkTimeoutConfig.CONNECT_TIMEOUT_SECONDS, SECONDS)
            .readTimeout(NetworkTimeoutConfig.READ_TIMEOUT_SECONDS, SECONDS)
            .writeTimeout(NetworkTimeoutConfig.WRITE_TIMEOUT_SECONDS, SECONDS)
            // A38 - read/write timeouts bound socket operations, not the call, so a server that
            // dribbles bytes just inside the read window could hold a sync slot indefinitely.
            // This caps the whole call. Only this client gets it: the main client serves EPG under
            // a 200 MB budget, where a blanket cap could abort a legitimate slow download.
            .callTimeout(NetworkTimeoutConfig.BACKGROUND_SYNC_CALL_TIMEOUT_SECONDS, SECONDS)
            .addInterceptor(DefaultUserAgentInterceptor(appUserAgent))
            .addInterceptor(httpLogger)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(2, 5, java.util.concurrent.TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 8
                maxRequestsPerHost = 2
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideStalkerApiService(okHttpClient: OkHttpClient, xtreamJson: Json): StalkerApiService =
        OkHttpStalkerApiService(okHttpClient, xtreamJson)

    @Provides
    @Singleton
    fun provideXtreamJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideXmltvParser(): XmltvParser = XmltvParser()

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().create()

    @Provides
    @Singleton
    @MainPlayerEngine
    fun provideMainPlayerEngine(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        appCacheQuota: AppCacheQuota,
        playbackCompatibilityRepository: com.streamvault.domain.repository.PlaybackCompatibilityRepository,
        audioCompatibilityMemoryStore: AudioCompatibilityMemoryStore,
        playbackSupportSnapshotStore: PlaybackSupportSnapshotStore
    ): PlayerEngine = Media3PlayerEngine(
        context,
        okHttpClient,
        playbackCompatibilityRepository,
        audioCompatibilityMemoryStore,
        playbackSupportSnapshotStore,
        appCacheQuota
    )

    /**
     * Factory binding for preview and multiview playback.
     * Each Provider.get() call returns a fresh engine instance.
     */
    @Provides
    @AuxiliaryPlayerEngine
    fun provideAuxiliaryPlayerEngine(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        appCacheQuota: AppCacheQuota,
        playbackCompatibilityRepository: com.streamvault.domain.repository.PlaybackCompatibilityRepository,
        audioCompatibilityMemoryStore: AudioCompatibilityMemoryStore,
        playbackSupportSnapshotStore: PlaybackSupportSnapshotStore
    ): PlayerEngine = Media3PlayerEngine(
        context,
        okHttpClient,
        playbackCompatibilityRepository,
        audioCompatibilityMemoryStore,
        playbackSupportSnapshotStore,
        appCacheQuota
    ).apply {
        enableMediaSession = false
        bypassAudioFocus = true
    }

    @Provides
    @Singleton
    fun provideTraktApiService(
        okHttpClient: OkHttpClient,
        gson: Gson
    ): com.streamvault.data.remote.trakt.TraktApiService {
        return retrofit2.Retrofit.Builder()
            .baseUrl("https://api.trakt.tv/")
            .client(okHttpClient)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create(gson))
            .build()
            .create(com.streamvault.data.remote.trakt.TraktApiService::class.java)
    }
}
