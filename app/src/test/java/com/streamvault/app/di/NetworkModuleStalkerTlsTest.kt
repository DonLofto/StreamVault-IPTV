package com.streamvault.app.di

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkModuleStalkerTlsTest {
    @Test
    fun `stalker accepts a valid certificate trusted by the supplied client`() = runTest {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()

        withTlsServer(serverCertificates) { server ->
            server.dispatcher = successfulStalkerDispatcher()
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()

            val result = authenticateWithProvidedClient(client, server)

            assertThat(result).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `stalker rejects an untrusted self signed certificate`() = runTest {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()

        withTlsServer(serverCertificates) { server ->
            server.dispatcher = successfulStalkerDispatcher()

            val result = authenticateWithProvidedClient(OkHttpClient(), server)

            assertThat(result).isInstanceOf(Result.Error::class.java)
        }
    }

    @Test
    fun `stalker rejects a trusted certificate for a different hostname`() = runTest {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("wrong.example.test")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()

        withTlsServer(serverCertificates) { server ->
            server.dispatcher = successfulStalkerDispatcher()
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()

            val result = authenticateWithProvidedClient(client, server)

            assertThat(result).isInstanceOf(Result.Error::class.java)
        }
    }

    private suspend fun authenticateWithProvidedClient(
        client: OkHttpClient,
        server: MockWebServer
    ): Result<*> = NetworkModule.provideStalkerApiService(
        okHttpClient = client,
        xtreamJson = Json { ignoreUnknownKeys = true }
    ).authenticate(
        com.streamvault.data.remote.stalker.StalkerDeviceProfile(
            portalUrl = server.url("/c/").toString(),
            macAddress = "00:1A:79:12:34:56",
            authMode = StalkerAuthMode.MAC_ONLY,
            magPreset = com.streamvault.domain.model.StalkerMagPreset.GENERIC_SAFE,
            portalFingerprint = com.streamvault.domain.model.StalkerPortalFingerprint.BASIC_MAC,
            bootstrapRecipe = com.streamvault.domain.model.StalkerBootstrapRecipe.GENERIC_SAFE,
            endpointPreference = com.streamvault.domain.model.StalkerEndpointPreference.AUTO,
            cookieMode = com.streamvault.domain.model.StalkerCookieMode.NONE,
            playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.AUTO,
            username = "",
            password = "",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en",
            serialNumber = "1234567890",
            deviceId = "device1",
            deviceId2 = "device2",
            signature = "sig",
            userAgent = "Mozilla/5.0",
            playerUserAgent = "Lavf",
            xUserAgent = "Model: MAG250"
        )
    )

    private fun successfulStalkerDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.queryParameter("action")) {
            "handshake" -> MockResponse().setResponseCode(200).setBody("""{"js":{"token":"token-123"}}""")
            "get_profile", "get_main_info" -> MockResponse().setResponseCode(200)
                .setBody("""{"js":{"id":"42","name":"Strict TLS","status":"1","auth_access":true}}""")
            "get_modules" -> MockResponse().setResponseCode(200).setBody("""{"js":{}}""")
            else -> MockResponse().setResponseCode(200).setBody("""{"js":{}}""")
        }
    }

    private suspend fun withTlsServer(
        certificates: HandshakeCertificates,
        block: suspend (MockWebServer) -> Unit
    ) {
        val server = MockWebServer()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }
}
