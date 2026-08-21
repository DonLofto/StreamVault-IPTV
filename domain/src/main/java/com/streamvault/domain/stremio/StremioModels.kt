package com.streamvault.domain.stremio

data class StremioCatalogExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String>? = null
)

data class StremioCatalog(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<StremioCatalogExtra>? = null
)

data class StremioManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val resources: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<StremioCatalog> = emptyList(),
    val background: String? = null,
    val logo: String? = null
)

data class StremioMeta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String>? = null
)

data class StremioStream(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val quality: String? = null,
    val behaviorHints: Map<String, Any>? = null
)
