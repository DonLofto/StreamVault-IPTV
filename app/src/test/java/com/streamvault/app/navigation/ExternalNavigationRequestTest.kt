package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalNavigationRequestTest {

    @Test
    fun fromLegacyRoute_providerSetup_withEncodedImportUri() {
        val encodedUri = "https%3A%2F%2Fexample.com%2Fplaylist.m3u8%3Ftoken%3Dsecret"
        val route = "provider_setup?providerId=42&importUri=$encodedUri"

        val destination = ExternalDestination.fromLegacyRoute(route)
        assertThat(destination).isInstanceOf(ExternalDestination.ProviderSetup::class.java)

        val setup = destination as ExternalDestination.ProviderSetup
        assertThat(setup.providerId).isEqualTo(42L)
        assertThat(setup.importUri).isEqualTo("https://example.com/playlist.m3u8?token=secret")
    }

    @Test
    fun fromLegacyRoute_movieDetail_withEncodedReturnRoute() {
        val encodedReturn = "%2Fhome%2Fcontinue"
        val route = "movie_detail/123?returnRoute=$encodedReturn"

        val destination = ExternalDestination.fromLegacyRoute(route)
        assertThat(destination).isInstanceOf(ExternalDestination.MovieDetail::class.java)

        val movie = destination as ExternalDestination.MovieDetail
        assertThat(movie.movieId).isEqualTo(123L)
        assertThat(movie.returnRoute).isEqualTo("/home/continue")
    }

    @Test
    fun fromLegacyRoute_seriesDetail_withEncodedReturnRoute() {
        val encodedReturn = "%2Fseries%2Fall"
        val route = "series_detail/456?returnRoute=$encodedReturn"

        val destination = ExternalDestination.fromLegacyRoute(route)
        assertThat(destination).isInstanceOf(ExternalDestination.SeriesDetail::class.java)

        val series = destination as ExternalDestination.SeriesDetail
        assertThat(series.seriesId).isEqualTo(456L)
        assertThat(series.returnRoute).isEqualTo("/series/all")
    }

    @Test
    fun fromLegacyRoute_malformedPercentEncoding_doesNotCrash() {
        val route = "provider_setup?providerId=1&importUri=https%ZZ%invalid"
        val destination = ExternalDestination.fromLegacyRoute(route)

        assertThat(destination).isInstanceOf(ExternalDestination.ProviderSetup::class.java)
        val setup = destination as ExternalDestination.ProviderSetup
        assertThat(setup.providerId).isEqualTo(1L)
        // Malformed URI decoding safely results in null
        assertThat(setup.importUri).isNull()
    }

    @Test
    fun fromLegacyRoute_blankValues_handledSafely() {
        val route = "provider_setup?providerId=&importUri="
        val destination = ExternalDestination.fromLegacyRoute(route)

        assertThat(destination).isInstanceOf(ExternalDestination.ProviderSetup::class.java)
        val setup = destination as ExternalDestination.ProviderSetup
        assertThat(setup.providerId).isNull()
        assertThat(setup.importUri).isNull()
    }

    @Test
    fun fromLegacyRoute_duplicateKeys_lastValueWins() {
        val route = "provider_setup?providerId=10&providerId=20"
        val destination = ExternalDestination.fromLegacyRoute(route)

        val setup = destination as ExternalDestination.ProviderSetup
        assertThat(setup.providerId).isEqualTo(20L)
    }

    @Test
    fun fromLegacyRoute_invalidMovieId_returnsNull() {
        assertThat(ExternalDestination.fromLegacyRoute("movie_detail/not-a-number")).isNull()
    }

    @Test
    fun fromLegacyRoute_blankRoute_returnsNull() {
        assertThat(ExternalDestination.fromLegacyRoute("   ")).isNull()
    }

    @Test
    fun fromLegacyRoute_standardDestinations() {
        assertThat(ExternalDestination.fromLegacyRoute("home")).isEqualTo(ExternalDestination.Home)
        assertThat(ExternalDestination.fromLegacyRoute("plugins")).isEqualTo(ExternalDestination.Plugins)
    }
}
