package com.streamvault.data.remote.emby

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class EmbyProviderTest {

    @Test
    fun `authenticate sends valid payload and parses access token`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                assertThat(request.url.encodedPath).isEqualTo("/Users/AuthenticateByName")
                assertThat(request.header("X-Emby-Authorization")).contains("Client=\"StreamVault\"")

                val responseBody = """
                    {
                      "AccessToken": "test-emby-token-12345",
                      "User": {
                        "Id": "user-uuid-99",
                        "Name": "StreamVaultUser"
                      }
                    }
                """.trimIndent()

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val provider = EmbyProvider(okHttpClient = client, gson = Gson())
        val result = provider.authenticate("https://emby.example.com", "admin", "secret")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val session = (result as Result.Success).data
        assertThat(session.accessToken).isEqualTo("test-emby-token-12345")
        assertThat(session.userId).isEqualTo("user-uuid-99")
        assertThat(session.userName).isEqualTo("StreamVaultUser")
    }

    @Test
    fun `fetchMovies maps Emby item dto to MovieEntity correctly`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                assertThat(request.url.encodedPath).isEqualTo("/Items")
                assertThat(request.header("X-Emby-Token")).isEqualTo("secret-token")

                val responseBody = """
                    {
                      "Items": [
                        {
                          "Id": "emby-movie-100",
                          "Name": "Big Buck Bunny",
                          "Overview": "A large rabbit takes revenge.",
                          "ProductionYear": 2008,
                          "PremiereDate": "2008-04-10T00:00:00.0000000Z",
                          "RunTimeTicks": 5960000000,
                          "CommunityRating": 7.8,
                          "Genres": ["Animation", "Comedy"]
                        }
                      ],
                      "TotalRecordCount": 1
                    }
                """.trimIndent()

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val embyProvider = EmbyProvider(okHttpClient = client, gson = Gson())
        val provider = Provider(
            id = 10,
            name = "My Emby",
            type = ProviderType.EMBY,
            serverUrl = "https://emby.example.com",
            password = "secret-token"
        )

        val result = embyProvider.fetchMovies(provider)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val movies = (result as Result.Success).data
        assertThat(movies).hasSize(1)
        val movie = movies.first()
        assertThat(movie.name).isEqualTo("Big Buck Bunny")
        assertThat(movie.plot).isEqualTo("A large rabbit takes revenge.")
        assertThat(movie.genre).isEqualTo("Animation, Comedy")
        assertThat(movie.rating).isEqualTo(7.8f)
        assertThat(movie.releaseDate).isEqualTo("2008-04-10")
        assertThat(movie.streamUrl).contains("/Videos/emby-movie-100/stream?static=true&api_key=secret-token")
    }
}
