package com.streamvault.data.remote.stremio

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.domain.model.Result
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class StremioProviderTest {

    @Test
    fun `fetchManifest correctly parses Stremio addon manifest`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                assertThat(request.url.encodedPath).isEqualTo("/manifest.json")

                val manifestJson = """
                    {
                      "id": "org.streamvault.cinemeta",
                      "version": "3.0.12",
                      "name": "Cinemeta",
                      "description": "Official Cinemeta catalog for StreamVault",
                      "resources": ["catalog", "meta"],
                      "types": ["movie", "series"],
                      "catalogs": [
                        {
                          "type": "movie",
                          "id": "top",
                          "name": "Popular Movies"
                        }
                      ]
                    }
                """.trimIndent()

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(manifestJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val provider = StremioProvider(okHttpClient = client, gson = Gson())
        val result = provider.fetchManifest("https://v3-cinemeta.strem.io/manifest.json")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val manifest = (result as Result.Success).data
        assertThat(manifest.id).isEqualTo("org.streamvault.cinemeta")
        assertThat(manifest.name).isEqualTo("Cinemeta")
        assertThat(manifest.resources).containsExactly("catalog", "meta")
        assertThat(manifest.types).containsExactly("movie", "series")
        assertThat(manifest.catalogs).hasSize(1)
        assertThat(manifest.catalogs.first().name).isEqualTo("Popular Movies")
    }

    @Test
    fun `fetchStreams parses stream candidates for media`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                assertThat(request.url.encodedPath).isEqualTo("/stream/movie/tt0137523.json")

                val streamResponseJson = """
                    {
                      "streams": [
                        {
                          "name": "RealDebrid 4K",
                          "title": "Fight Club (1999) 2160p HDR Remux [18.4 GB]",
                          "url": "https://debrid.example.com/download/stream.mp4"
                        },
                        {
                          "name": "Torrentio 1080p",
                          "title": "Fight Club (1999) 1080p BluRay [2.1 GB]",
                          "infoHash": "a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e"
                        }
                      ]
                    }
                """.trimIndent()

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(streamResponseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val provider = StremioProvider(okHttpClient = client, gson = Gson())
        val result = provider.fetchStreams("https://torrentio.strem.fun", "movie", "tt0137523")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val streams = (result as Result.Success).data
        assertThat(streams).hasSize(2)
        assertThat(streams[0].name).isEqualTo("RealDebrid 4K")
        assertThat(streams[0].url).isEqualTo("https://debrid.example.com/download/stream.mp4")
        assertThat(streams[1].name).isEqualTo("Torrentio 1080p")
        assertThat(streams[1].infoHash).isEqualTo("a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e")
    }
}
