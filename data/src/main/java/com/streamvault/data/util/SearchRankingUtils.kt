package com.streamvault.data.util

import java.util.Locale

private val WHITESPACE_REGEX = Regex("\\s+")
private val NON_WORD_REGEX = Regex("[^\\p{L}\\p{N}_]")

/** Sort key computed once per element instead of once per comparison. */
private class RankKey<T>(
    val item: T,
    val relevanceBucket: Int,
    val firstMatchPosition: Int,
    val rawNameLength: Int,
    val normalizedName: String
)

internal fun <T> List<T>.rankSearchResults(rawQuery: String, nameSelector: (T) -> String): List<T> {
    val normalizedQuery = rawQuery.trim().lowercase(Locale.ROOT)
    if (normalizedQuery.isBlank()) return this

    val queryTokens = normalizedQuery
        .split(WHITESPACE_REGEX)
        .map { it.replace(NON_WORD_REGEX, "") }
        .filter { it.length >= 2 }

    if (queryTokens.isEmpty()) return this

    // Decorate-sort-undecorate. The previous comparator chain re-derived trim/lowercase for each
    // selector on every comparison (O(n log n) derivations) instead of once per element.
    return map { item ->
        val rawName = nameSelector(item)
        val normalizedName = rawName.trim().lowercase(Locale.ROOT)
        RankKey(
            item = item,
            relevanceBucket = relevanceBucket(normalizedName, normalizedQuery, queryTokens),
            firstMatchPosition = firstMatchPosition(normalizedName, queryTokens),
            rawNameLength = rawName.length,
            normalizedName = normalizedName
        )
    }.sortedWith(
        compareBy<RankKey<T>> { it.relevanceBucket }
            .thenBy { it.firstMatchPosition }
            .thenBy { it.rawNameLength }
            .thenBy { it.normalizedName }
    ).map { it.item }
}

private fun relevanceBucket(normalizedName: String, normalizedQuery: String, queryTokens: List<String>): Int {
    return when {
        normalizedName == normalizedQuery -> 0
        normalizedName.startsWith(normalizedQuery) -> 1
        queryTokens.all { token -> normalizedName.startsWith(token) || normalizedName.contains(" $token") } -> 2
        queryTokens.any { token -> normalizedName.contains(token) } -> 3
        else -> 4
    }
}

private fun firstMatchPosition(normalizedName: String, queryTokens: List<String>): Int {
    return queryTokens.minOfOrNull { token ->
        normalizedName.indexOf(token).takeIf { it >= 0 } ?: Int.MAX_VALUE
    } ?: Int.MAX_VALUE
}