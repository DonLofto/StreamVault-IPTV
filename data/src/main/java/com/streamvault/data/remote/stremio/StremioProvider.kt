package com.streamvault.data.remote.stremio

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamvault.domain.model.Result
import com.streamvault.domain.stremio.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class StremioProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    private companion object {
        private const val REQUEST_TIMEOUT_SECONDS = 30L
    }

    suspend fun fetchManifest(manifestUrl: String): Result<StremioManifest> = withContext(Dispatchers.IO) {
        try {
            val url = if (manifestUrl.endsWith("/manifest.json")) manifestUrl else "${manifestUrl.trimEnd('/')}/manifest.json"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()
            val body = executeRequest(request, "Failed to load Stremio manifest")
            val dto = gson.fromJson<StremioManifestDto>(body, StremioManifestDto::class.java)
                ?: throw IllegalStateException("Manifest JSON returned null")

            val manifest = StremioManifest(
                id = dto.id,
                name = dto.name,
                version = dto.version,
                description = dto.description,
                resources = dto.resources?.mapNotNull {
                    when (it) {
                        is String -> it
                        is Map<*, *> -> it["name"] as? String
                        else -> null
                    }
                } ?: emptyList(),
                types = dto.types ?: emptyList(),
                catalogs = dto.catalogs?.map { cat ->
                    StremioCatalog(
                        type = cat.type,
                        id = cat.id,
                        name = cat.name,
                        extra = cat.extra?.map { StremioCatalogExtra(it.name, it.isRequired, it.options) }
                    )
                } ?: emptyList(),
                background = dto.background,
                logo = dto.logo
            )
            Result.success(manifest)
        } catch (e: Exception) {
            Result.error("Failed to load Stremio manifest: ${e.message}", e)
        }
    }

    suspend fun fetchCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        extraParams: Map<String, String> = emptyMap()
    ): Result<List<StremioMeta>> = withContext(Dispatchers.IO) {
        try {
            val base = baseUrl.trimEnd('/').removeSuffix("/manifest.json")
            val path = if (extraParams.isEmpty()) {
                "$base/catalog/$type/$catalogId.json"
            } else {
                val extras = extraParams.entries.joinToString("&") { "${it.key}=${it.value}" }
                "$base/catalog/$type/$catalogId/$extras.json"
            }
            val request = Request.Builder()
                .url(path)
                .header("Accept", "application/json")
                .get()
                .build()
            val body = executeRequest(request, "Failed to load catalog")
            val response = gson.fromJson(body, StremioCatalogResponseDto::class.java)
            val metas = response?.metas?.map { dto ->
                StremioMeta(
                    id = dto.id,
                    type = dto.type,
                    name = dto.name,
                    poster = dto.poster,
                    background = dto.background,
                    logo = dto.logo,
                    description = dto.description,
                    releaseInfo = dto.releaseInfo,
                    imdbRating = dto.imdbRating,
                    genres = dto.genres
                )
            } ?: emptyList()
            Result.success(metas)
        } catch (e: Exception) {
            Result.error("Failed to load Stremio catalog: ${e.message}", e)
        }
    }

    suspend fun fetchStreams(
        baseUrl: String,
        type: String,
        mediaId: String
    ): Result<List<StremioStream>> = withContext(Dispatchers.IO) {
        try {
            val base = baseUrl.trimEnd('/').removeSuffix("/manifest.json")
            val url = "$base/stream/$type/$mediaId.json"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()
            val body = executeRequest(request, "Failed to load streams")
            val response = gson.fromJson(body, StremioStreamResponseDto::class.java)
            val streams = response?.streams?.map { dto ->
                StremioStream(
                    name = dto.name,
                    title = dto.title,
                    url = dto.url,
                    infoHash = dto.infoHash,
                    fileIdx = dto.fileIdx,
                    behaviorHints = dto.behaviorHints
                )
            } ?: emptyList()
            Result.success(streams)
        } catch (e: Exception) {
            Result.error("Failed to load Stremio streams: ${e.message}", e)
        }
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
