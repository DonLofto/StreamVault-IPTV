package com.streamvault.data.remote.stremio

import com.google.gson.annotations.SerializedName

data class StremioCatalogExtraDto(
    @SerializedName("name") val name: String,
    @SerializedName("isRequired") val isRequired: Boolean = false,
    @SerializedName("options") val options: List<String>? = null
)

data class StremioCatalogDto(
    @SerializedName("type") val type: String,
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("extra") val extra: List<StremioCatalogExtraDto>? = null
)

data class StremioManifestDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("version") val version: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("resources") val resources: List<Any>? = null,
    @SerializedName("types") val types: List<String>? = null,
    @SerializedName("catalogs") val catalogs: List<StremioCatalogDto>? = null,
    @SerializedName("background") val background: String? = null,
    @SerializedName("logo") val logo: String? = null
)

data class StremioMetaDto(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String,
    @SerializedName("name") val name: String,
    @SerializedName("poster") val poster: String? = null,
    @SerializedName("background") val background: String? = null,
    @SerializedName("logo") val logo: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("releaseInfo") val releaseInfo: String? = null,
    @SerializedName("imdbRating") val imdbRating: String? = null,
    @SerializedName("genres") val genres: List<String>? = null
)

data class StremioCatalogResponseDto(
    @SerializedName("metas") val metas: List<StremioMetaDto>? = null
)

data class StremioMetaResponseDto(
    @SerializedName("meta") val meta: StremioMetaDto? = null
)

data class StremioStreamDto(
    @SerializedName("name") val name: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("infoHash") val infoHash: String? = null,
    @SerializedName("fileIdx") val fileIdx: Int? = null,
    @SerializedName("behaviorHints") val behaviorHints: Map<String, Any>? = null
)

data class StremioStreamResponseDto(
    @SerializedName("streams") val streams: List<StremioStreamDto>? = null
)
