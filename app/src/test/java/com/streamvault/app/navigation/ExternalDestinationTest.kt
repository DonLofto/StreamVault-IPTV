package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalDestinationTest {

    @Test
    fun fromLegacyRoute_parsesSupportedRoutes() {
        assertThat(ExternalDestination.fromLegacyRoute("home"))
            .isEqualTo(ExternalDestination.Home)
        assertThat(ExternalDestination.fromLegacyRoute("provider_setup?providerId=-1&importUri="))
            .isEqualTo(ExternalDestination.ProviderSetup())
        assertThat(
            ExternalDestination.fromLegacyRoute(
                "series_detail/42?returnRoute=home"
            )
        ).isEqualTo(
            ExternalDestination.SeriesDetail(seriesId = 42L, returnRoute = "home")
        )
    }

    @Test
    fun fromLegacyRoute_rejectsUnsupportedRoutes() {
        assertThat(ExternalDestination.fromLegacyRoute("settings"))
            .isNull()
        assertThat(ExternalDestination.fromLegacyRoute("series_detail/not-a-number"))
            .isNull()
    }

    @Test
    fun fromLegacyRoute_decodesProviderImportUriOnMinimumSdkCompatibleOverload() {
        assertThat(
            ExternalDestination.fromLegacyRoute(
                "provider_setup?providerId=7&importUri=https%3A%2F%2Fexample.test%2Fplaylist%3Fx%3D1%26y%3D2"
            )
        ).isEqualTo(
            ExternalDestination.ProviderSetup(
                providerId = 7L,
                importUri = "https://example.test/playlist?x=1&y=2"
            )
        )
    }

    @Test
    fun fromLegacyRoute_decodesMovieAndSeriesReturnRoutes() {
        assertThat(
            ExternalDestination.fromLegacyRoute("movie_detail/42?returnRoute=search%2Fcats")
        ).isEqualTo(ExternalDestination.MovieDetail(42L, "search/cats"))
        assertThat(
            ExternalDestination.fromLegacyRoute("series_detail/43?returnRoute=home%252Ftv")
        ).isEqualTo(ExternalDestination.SeriesDetail(43L, "home%2Ftv"))
    }

    @Test
    fun fromLegacyRoute_ignoresMalformedAndBlankQueryValuesSafely() {
        assertThat(
            ExternalDestination.fromLegacyRoute("movie_detail/42?returnRoute=%ZZ")
        ).isEqualTo(ExternalDestination.MovieDetail(42L))
        assertThat(
            ExternalDestination.fromLegacyRoute("provider_setup?providerId=&importUri=")
        ).isEqualTo(ExternalDestination.ProviderSetup())
    }

    @Test
    fun fromLegacyRoute_duplicateKeysUseTheLastWellFormedValue() {
        assertThat(
            ExternalDestination.fromLegacyRoute(
                "provider_setup?providerId=1&providerId=2&importUri=https%3A%2F%2Fexample.test"
            )
        ).isEqualTo(
            ExternalDestination.ProviderSetup(
                providerId = 2L,
                importUri = "https://example.test"
            )
        )
    }
}
