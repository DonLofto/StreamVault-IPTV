package com.streamvault.domain.util

import com.streamvault.domain.model.LiveChannelVariantAttributes
import java.text.Normalizer
import java.util.Locale

data class ChannelClassification(
    val logicalGroupId: String,
    val canonicalName: String,
    val attributes: LiveChannelVariantAttributes
)

object ChannelNormalizer {
    private val bracketRegex = Regex("""\[(.*?)]|\((.*?)\)|\|(.*?)\|""")
    private val leadingRegionRegex = Regex("""^\s*([a-z]{2,3})\s*[:\-]\s*""", RegexOption.IGNORE_CASE)
    private val separatorRegex = Regex("""[\s_\-./]+""")
    private val collapseWhitespaceRegex = Regex("""\s+""")
    private val nonAlphaNumericRegex = Regex("""[^a-z0-9 ]""")
    private val frameRateRegex = Regex("""(?<!\d)(24|25|30|50|60)\s*fps(?!\d)""", RegexOption.IGNORE_CASE)
    private val heightRegex = Regex("""(?<!\d)(4320|2160|1440|1080|720|576|540|480|360|240)\s*p?(?!\d)""", RegexOption.IGNORE_CASE)
    private val plusSignRegex = Regex("""\+""")
    private val colonPipeRegex = Regex("""[:|]""")
    private val combiningMarksRegex = Regex("\\p{InCombiningDiacriticalMarks}+")

    private val resolutionTags = linkedMapOf(
        "8k" to 4320,
        "4320p" to 4320,
        "uhd" to 2160,
        "ultra hd" to 2160,
        "ultrahd" to 2160,
        "4k" to 2160,
        "2160p" to 2160,
        "2k" to 1440,
        "qhd" to 1440,
        "1440p" to 1440,
        "full hd" to 1080,
        "fullhd" to 1080,
        "fhd" to 1080,
        "1080p" to 1080,
        "1080i" to 1080,
        "hd" to 720,
        "720p" to 720,
        "576p" to 576,
        "540p" to 540,
        "hq" to 576,
        "sd" to 576
    )
    private val codecTags = linkedMapOf(
        "dolby vision" to "Dolby Vision",
        "dv" to "Dolby Vision",
        "hdr10" to "HDR10",
        "hdr" to "HDR",
        "hevc" to "HEVC",
        "h265" to "HEVC",
        "x265" to "HEVC",
        "av1" to "AV1",
        "h264" to "H.264",
        "x264" to "H.264"
    )
    private val transportTags = linkedMapOf(
        "mpeg ts" to "MPEG-TS",
        "mpeg-ts" to "MPEG-TS",
        "ts" to "MPEG-TS",
        "hls" to "HLS",
        "m3u8" to "HLS"
    )
    private val sourceHintTags = linkedMapOf(
        "backup" to "Backup",
        "alt" to "Alternate",
        "alternate" to "Alternate",
        "raw" to "Raw",
        "lite" to "Lite",
        "mobile" to "Mobile",
        "test" to "Test",
        "low" to "Low",
        "vip" to "VIP",
        "premium" to "Premium",
        "pro" to "Pro"
    )
    private val languageTags = linkedMapOf(
        "en" to "EN",
        "eng" to "EN",
        "english" to "EN",
        "fr" to "FR",
        "fre" to "FR",
        "french" to "FR",
        "de" to "DE",
        "ger" to "DE",
        "german" to "DE",
        "it" to "IT",
        "ita" to "IT",
        "italian" to "IT",
        "es" to "ES",
        "esp" to "ES",
        "spanish" to "ES",
        "pt" to "PT",
        "por" to "PT",
        "portuguese" to "PT",
        "ar" to "AR",
        "ara" to "AR",
        "arabic" to "AR"
    )

    /**
     * Phrases stripped when building the canonical name. Sorted longest-first so a longer phrase wins
     * over its own substring ("mpeg ts" before "ts"), preserving the previous replacement order.
     */
    private val removableCanonicalPhraseTokens: List<String> = (
        resolutionTags.keys +
            codecTags.keys +
            transportTags.keys +
            sourceHintTags.keys +
            listOf("fps")
        ).sortedByDescending { it.length }

    /**
     * Single-pass equivalent of the previous "replace every phrase in turn" loop, which compiled and
     * applied ~49 patterns to the whole name for every channel. Built once at class init.
     */
    private val removablePhrasesRegex = Regex(
        removableCanonicalPhraseTokens.joinToString("|") { phrase ->
            "(?<![a-z0-9])${Regex.escape(phrase)}(?![a-z0-9])"
        },
        RegexOption.IGNORE_CASE
    )

    fun getLogicalGroupId(channelName: String, providerId: Long): String =
        classify(channelName, providerId).logicalGroupId

    /**
     * Returns true when the channel name is wrapped in hash markers,
     * e.g. "#### GENERAL HD/4K ####". These are category-header entries
     * from some providers (like Strong8k) that are not actual playable channels.
     */
    fun isHashWrappedHeader(channelName: String): Boolean {
        val trimmed = channelName.trim()
        return trimmed.startsWith("##") && trimmed.endsWith("##")
    }

    /**
     * Classification is a pure function of (channelName, providerId, streamUrl), but it is called
     * once per channel on EVERY emission of ChannelRepositoryImpl.observeChannels - and that flow is
     * a combine of six sources including user preferences, so toggling parental level, grouping mode
     * or hidden ids reclassified the entire catalog. The on-device thread-dump profile caught this
     * pipeline in every sample where app code was actively executing.
     *
     * Access-ordered LRU, bounded the same way AdultContentClassifier's cache is, so a large
     * multi-provider catalog cannot grow it without limit. Results are immutable data classes.
     */
    private const val CLASSIFICATION_CACHE_SIZE = 4096

    private data class ClassificationKey(
        val channelName: String,
        val providerId: Long,
        val streamUrl: String
    )

    private val classificationCache: MutableMap<ClassificationKey, ChannelClassification> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<ClassificationKey, ChannelClassification>(512, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<ClassificationKey, ChannelClassification>?
                ): Boolean = size > CLASSIFICATION_CACHE_SIZE
            }
        )

    fun classify(
        channelName: String,
        providerId: Long,
        streamUrl: String = ""
    ): ChannelClassification {
        val key = ClassificationKey(channelName, providerId, streamUrl)
        synchronized(classificationCache) {
            classificationCache[key]?.let { return it }
        }
        val computed = classifyUncached(channelName, providerId, streamUrl)
        synchronized(classificationCache) {
            classificationCache[key] = computed
        }
        return computed
    }

    private fun classifyUncached(
        channelName: String,
        providerId: Long,
        streamUrl: String
    ): ChannelClassification {
        val originalName = channelName.trim().ifBlank { "Channel" }
        val lowerName = originalName.lowercase(Locale.ROOT)
        val lowerUrl = streamUrl.lowercase(Locale.ROOT)

        val extractedTags = buildList {
            bracketRegex.findAll(originalName).forEach { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.let(::add)
            }
        }
        val regionHint = resolveRegionHint(originalName, extractedTags)
        val languageHint = resolveLanguageHint(lowerName, extractedTags)
        val declaredHeight = resolveDeclaredHeight(lowerName, lowerUrl)
        val frameRate = frameRateRegex.find(lowerName)?.groupValues?.get(1)?.toIntOrNull()
        val codecLabel = resolveCodecLabel(lowerName, lowerUrl)
        val transportLabel = resolveTransportLabel(lowerName, lowerUrl)
        val sourceHint = resolveSourceHint(lowerName)
        val rawTags = buildRawTags(
            declaredHeight = declaredHeight,
            frameRate = frameRate,
            codecLabel = codecLabel,
            transportLabel = transportLabel,
            sourceHint = sourceHint,
            regionHint = regionHint,
            languageHint = languageHint,
            lowerName = lowerName
        )

        val canonicalName = buildCanonicalName(originalName)
        val logicalKey = canonicalName
            .stripAccents()
            .lowercase(Locale.ROOT)
            .replace(nonAlphaNumericRegex, " ")
            .replace(collapseWhitespaceRegex, " ")
            .trim()
            .replace(" ", "")
            .ifEmpty {
                originalName.stripAccents()
                    .lowercase(Locale.ROOT)
                    .replace(nonAlphaNumericRegex, "")
                    .ifBlank { "channel${providerId}" }
            }

        return ChannelClassification(
            logicalGroupId = "${providerId}_$logicalKey",
            canonicalName = canonicalName,
            attributes = LiveChannelVariantAttributes(
                resolutionLabel = declaredHeight?.let(::heightToResolutionLabel),
                declaredHeight = declaredHeight,
                qualityTier = declaredHeight?.let(::heightToQualityTier) ?: 0,
                codecLabel = codecLabel,
                transportLabel = transportLabel,
                frameRate = frameRate,
                isHdr = lowerName.contains("hdr") || lowerName.contains("dolby vision") ||
                    containsStandalone(lowerName, "dv"),
                sourceHint = sourceHint,
                regionHint = regionHint,
                languageHint = languageHint,
                rawTags = rawTags
            )
        )
    }

    private fun buildCanonicalName(originalName: String): String {
        var cleaned = originalName
            .replace(bracketRegex, " ")
            .replace(leadingRegionRegex, " ")

        cleaned = removablePhrasesRegex.replace(cleaned, " ")
        cleaned = heightRegex.replace(cleaned, " ")
        cleaned = frameRateRegex.replace(cleaned, " ")

        cleaned = cleaned
            .replace(plusSignRegex, " + ")
            .replace(colonPipeRegex, " ")
            .replace(separatorRegex, " ")
            .replace(collapseWhitespaceRegex, " ")
            .trim()

        return cleaned.ifBlank { originalName.trim() }
    }

    private fun resolveDeclaredHeight(lowerName: String, lowerUrl: String): Int? {
        val directHeight = heightRegex.find(lowerName)?.groupValues?.get(1)?.toIntOrNull()
        if (directHeight != null) {
            return directHeight
        }
        resolutionTags.forEach { (tag, height) ->
            if (containsStandalone(lowerName, tag)) {
                return height
            }
        }
        return heightRegex.find(lowerUrl)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun resolveCodecLabel(lowerName: String, lowerUrl: String): String? =
        codecTags.entries.firstOrNull { (token, _) ->
            containsStandalone(lowerName, token) || containsStandalone(lowerUrl, token)
        }?.value

    private fun resolveTransportLabel(lowerName: String, lowerUrl: String): String? = when {
        lowerUrl.endsWith(".m3u8") || containsStandalone(lowerName, "m3u8") || containsStandalone(lowerName, "hls") -> "HLS"
        lowerUrl.endsWith(".ts") || containsStandalone(lowerName, "ts") || containsStandalone(lowerName, "mpeg ts") || containsStandalone(lowerName, "mpeg-ts") -> "MPEG-TS"
        else -> transportTags.entries.firstOrNull { (token, _) ->
            containsStandalone(lowerName, token) || containsStandalone(lowerUrl, token)
        }?.value
    }

    private fun resolveSourceHint(lowerName: String): String? =
        sourceHintTags.entries.firstOrNull { (token, _) -> containsStandalone(lowerName, token) }?.value

    private fun resolveRegionHint(originalName: String, extractedTags: List<String>): String? {
        val prefixMatch = leadingRegionRegex.find(originalName)
        val prefixCode = prefixMatch?.groupValues?.getOrNull(1)
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.length in 2..3 }
        if (prefixCode != null) {
            return prefixCode
        }
        return extractedTags.firstNotNullOfOrNull { tag ->
            val normalized = tag.trim().uppercase(Locale.ROOT)
            normalized.takeIf { it.length in 2..3 && it.all(Char::isLetter) }
        }
    }

    private fun resolveLanguageHint(lowerName: String, extractedTags: List<String>): String? {
        val extractedMatch = extractedTags.firstNotNullOfOrNull { tag ->
            languageTags[tag.trim().lowercase(Locale.ROOT)]
        }
        if (extractedMatch != null) {
            return extractedMatch
        }
        return languageTags.entries.firstOrNull { (token, _) -> containsStandalone(lowerName, token) }?.value
    }

    private fun buildRawTags(
        declaredHeight: Int?,
        frameRate: Int?,
        codecLabel: String?,
        transportLabel: String?,
        sourceHint: String?,
        regionHint: String?,
        languageHint: String?,
        lowerName: String
    ): List<String> = buildList {
        declaredHeight?.let { add(heightToResolutionLabel(it)) }
        frameRate?.let { add("${it}fps") }
        codecLabel?.let { add(it) }
        transportLabel?.let { add(it) }
        sourceHint?.let { add(it) }
        regionHint?.let { add(it) }
        languageHint?.let { if (it != regionHint) add(it) }
        if (lowerName.contains("hdr")) add("HDR")
        if (lowerName.contains("dolby vision") || containsStandalone(lowerName, "dv")) {
            add("Dolby Vision")
        }
    }.distinct()

    /**
     * True when [token] occurs in [text] as a standalone word rather than embedded in a longer one.
     *
     * Equivalent to the previous `(?<![a-z0-9])token(?![a-z0-9])` pattern, but without compiling a
     * Regex on every call — that per-call `Pattern.compile` was the single hottest frame in the
     * on-device profile. Callers always pass lowercased text, so only [a-z0-9] can block a boundary.
     */
    private fun containsStandalone(text: String, token: String): Boolean {
        if (token.isEmpty()) return false
        var index = text.indexOf(token)
        while (index >= 0) {
            val beforeOk = index == 0 || !isAsciiLowerAlphanumeric(text[index - 1])
            val end = index + token.length
            val afterOk = end >= text.length || !isAsciiLowerAlphanumeric(text[end])
            if (beforeOk && afterOk) return true
            index = text.indexOf(token, index + 1)
        }
        return false
    }

    /** Mirrors the `[a-z0-9]` character class the previous lookarounds used. */
    private fun isAsciiLowerAlphanumeric(c: Char): Boolean = (c in 'a'..'z') || (c in '0'..'9')

    private fun heightToResolutionLabel(height: Int): String = when {
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height > 0 -> "${height}p"
        else -> "Unknown"
    }

    private fun heightToQualityTier(height: Int): Int = when {
        height >= 4320 -> 7
        height >= 2160 -> 6
        height >= 1440 -> 5
        height >= 1080 -> 4
        height >= 720 -> 3
        height >= 576 -> 2
        else -> 1
    }

    private fun String.stripAccents(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(combiningMarksRegex, "")
}
