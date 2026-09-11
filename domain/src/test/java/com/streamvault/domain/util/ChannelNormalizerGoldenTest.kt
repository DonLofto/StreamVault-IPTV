package com.streamvault.domain.util

import com.streamvault.domain.model.LiveChannelVariantAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Characterisation ("golden") test that locks the exact output of [ChannelNormalizer].
 *
 * Why this exists: [ChannelNormalizer.classify] feeds `logicalGroupId`, the identity used to group a
 * user's channels. A perf refactor that changes the output even slightly silently re-groups the
 * catalog, so output must be provably byte-identical across such a change.
 *
 * The fixture covers every tag category (resolution, codec, transport, source hint, language,
 * region, fps), every delimiter shape, hash-wrapped provider headers, accented names, and
 * adversarial cases where a short token appears inside a longer word.
 *
 * To regenerate after an INTENTIONAL behaviour change:
 *   1. run `:domain:test --tests '*ChannelNormalizerGoldenTest*'`
 *   2. the test writes the new output to `build/channel-normalizer-golden-actual.txt`
 *   3. review the diff, then copy it over `src/test/resources/channel-normalizer-golden.txt`
 */
class ChannelNormalizerGoldenTest {

    private data class Case(val name: String, val url: String = "")

    private fun fmt(name: String, url: String, c: ChannelClassification): String {
        val a: LiveChannelVariantAttributes = c.attributes
        return listOf(
            name,
            url,
            c.logicalGroupId,
            c.canonicalName,
            a.resolutionLabel ?: "-",
            a.declaredHeight?.toString() ?: "-",
            a.qualityTier.toString(),
            a.codecLabel ?: "-",
            a.transportLabel ?: "-",
            a.frameRate?.toString() ?: "-",
            a.isHdr.toString(),
            a.sourceHint ?: "-",
            a.regionHint ?: "-",
            a.languageHint ?: "-",
            a.rawTags.joinToString(",")
        ).joinToString("\t")
    }

    @Test
    fun `classify output is byte-identical to the golden fixture`() {
        val actual = StringBuilder(HEADER).append('\n')
        for (case in FIXTURE) {
            val classification = ChannelNormalizer.classify(case.name, PROVIDER_ID, case.url)
            actual.append(fmt(case.name, case.url, classification)).append('\n')
        }
        val actualText = actual.toString()

        val stream = javaClass.classLoader?.getResourceAsStream(GOLDEN_RESOURCE)
        if (stream == null) {
            writeActualAndFail(actualText, "golden resource '" + GOLDEN_RESOURCE + "' not found")
            return
        }
        val expected = stream.bufferedReader().use { it.readText() }

        if (expected != actualText) {
            writeActualAndFail(actualText, "output differs from the golden fixture")
            return
        }
    }

    /** Explicit assertions for the cases most likely to regress under a regex refactor. */
    @Test
    fun `short tokens only match as standalone words`() {
        // "dv" (Dolby Vision) must not fire inside a longer word.
        assertEquals(false, ChannelNormalizer.classify("Advance TV", PROVIDER_ID).attributes.isHdr)
        assertEquals(false, ChannelNormalizer.classify("DVD Movies", PROVIDER_ID).attributes.isHdr)
        assertEquals(false, ChannelNormalizer.classify("Adventure HD", PROVIDER_ID).attributes.isHdr)

        // ...but must fire as a standalone token.
        assertEquals(true, ChannelNormalizer.classify("BT Sport DV", PROVIDER_ID).attributes.isHdr)
        assertEquals(true, ChannelNormalizer.classify("BT Sport dv", PROVIDER_ID).attributes.isHdr)

        // "ts" (MPEG-TS) must not fire inside a longer word.
        assertEquals(null, ChannelNormalizer.classify("Tests HD", PROVIDER_ID).attributes.transportLabel)
        assertEquals("MPEG-TS", ChannelNormalizer.classify("News TS", PROVIDER_ID).attributes.transportLabel)

        // short language tokens must not fire inside longer words.
        assertEquals(null, ChannelNormalizer.classify("Energy TV", PROVIDER_ID).attributes.languageHint)
        assertEquals(null, ChannelNormalizer.classify("Item Shop", PROVIDER_ID).attributes.languageHint)
    }

    @Test
    fun `equivalent quality variants normalise to one logical group id`() {
        val a = ChannelNormalizer.classify("Sky Sports 1 HD", PROVIDER_ID).logicalGroupId
        val b = ChannelNormalizer.classify("Sky Sports 1 FHD", PROVIDER_ID).logicalGroupId
        val c = ChannelNormalizer.classify("Sky Sports 1 4K", PROVIDER_ID).logicalGroupId
        val d = ChannelNormalizer.classify("Sky Sports 1", PROVIDER_ID).logicalGroupId
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(a, d)
    }

    @Test
    fun `blank input falls back deterministically`() {
        val c = ChannelNormalizer.classify("   ", PROVIDER_ID)
        assertNotNull(c.logicalGroupId)
        // blank input is replaced by the literal "Channel" before canonicalisation
        assertEquals(PROVIDER_ID.toString() + "_channel", c.logicalGroupId)
    }

    /**
     * A6/A9 regression guard.
     *
     * classify() is memoised because ChannelRepositoryImpl reclassifies the whole catalog on every
     * observeChannels emission. assertSame proves the second call returned the CACHED instance rather
     * than recomputing - a value-equality assertion would pass even if the cache were removed.
     */
    @Test
    fun `classify memoises identical inputs and separates different ones`() {
        val first = ChannelNormalizer.classify("Sky Sports 1 HD", 7L, "http://example.com/a.ts")
        val second = ChannelNormalizer.classify("Sky Sports 1 HD", 7L, "http://example.com/a.ts")
        org.junit.Assert.assertSame(first, second)

        // Each key component must participate, or a preference change would return a stale result.
        val otherProvider = ChannelNormalizer.classify("Sky Sports 1 HD", 8L, "http://example.com/a.ts")
        org.junit.Assert.assertNotSame(first, otherProvider)
        val otherUrl = ChannelNormalizer.classify("Sky Sports 1 HD", 7L, "http://example.com/b.m3u8")
        org.junit.Assert.assertNotSame(first, otherUrl)
        val otherName = ChannelNormalizer.classify("Sky Sports 2 HD", 7L, "http://example.com/a.ts")
        org.junit.Assert.assertNotSame(first, otherName)
    }

    private fun writeActualAndFail(actualText: String, reason: String) {
        val out = File("build/channel-normalizer-golden-actual.txt")
        out.parentFile?.mkdirs()
        out.writeText(actualText)
        throw AssertionError(
            reason + ".\nWrote actual output to " + out.absolutePath +
                "\nReview it, then copy over domain/src/test/resources/" + GOLDEN_RESOURCE
        )
    }

    private companion object {
        const val PROVIDER_ID = 42L
        const val GOLDEN_RESOURCE = "channel-normalizer-golden.txt"
        const val HEADER = "# name\turl\tlogicalGroupId\tcanonicalName\tresolutionLabel\t" +
            "declaredHeight\tqualityTier\tcodecLabel\ttransportLabel\tframeRate\tisHdr\t" +
            "sourceHint\tregionHint\tlanguageHint\trawTags"

        val FIXTURE: List<Case> = buildList {
            // --- plain quality/resolution tags ---
            listOf(
                "BBC One HD", "BBC One FHD", "BBC One UHD", "BBC One 4K", "BBC One 2K",
                "BBC One 1080p", "BBC One 720p", "BBC One 576p", "BBC One 540p", "BBC One 480p",
                "BBC One 360p", "BBC One 240p", "BBC One SD", "BBC One HQ",
                "ITV 1 HD", "Channel 4 HD", "Channel 5 +1", "5USA HD",
                "Sky Sports Main Event HD", "Sky Sports F1 UHD", "Sky Sports Premier League FHD",
                "TNT Sports 1 HD", "Eurosport 1 HD", "Discovery Channel HD",
                "National Geographic HD", "Animal Planet HD", "History HD"
            ).forEach { add(Case(it)) }

            // --- country / region prefixes in every delimiter shape ---
            listOf(
                "UK: BBC One HD", "US: CNN", "FR: TF1 HD", "DE: ARD HD", "IT: Rai 1 HD",
                "ES: La 1 HD", "NL: NPO 1 HD", "PT: RTP 1 HD", "AR: MBC 1 HD", "TR: TRT 1 HD",
                "PL: TVP 1 HD", "RU: Perviy HD",
                "UK - Sky News", "US - Fox News HD", "UK - BBC Two",
                "FR| Canal+ HD", "DE| ZDF HD", "IT| Canale 5 HD",
                "BBC: One: HD", "UK:FR: Mixed HD"
            ).forEach { add(Case(it)) }

            // --- codec tags ---
            listOf(
                "Sky Sports F1 HEVC", "Sky Sports F1 H265", "Sky Sports F1 x265",
                "Sky Sports F1 H264", "Sky Sports F1 x264", "Sky Sports F1 H.264",
                "Sky Sports F1 AV1", "BT Sport HDR", "BT Sport HDR10",
                "BT Sport Dolby Vision", "BT Sport DV", "BT Sport dv",
                "Movie Channel HEVC HDR", "4K HDR Sports", "UHD HDR10 Test",
                "DV", "hdr", "h265", "x265", "av1", "Dolby", "Vision"
            ).forEach { add(Case(it)) }

            // --- transport tags ---
            listOf(
                "Sports HD HLS", "Sports HD m3u8", "Sports HD M3U8", "Sports MPEG-TS",
                "Sports mpeg ts", "Sports mpeg-ts", "News Channel TS", "News .ts",
                "TS", "HLS", "mpeg ts", "mpeg-ts"
            ).forEach { add(Case(it)) }

            // --- source hints ---
            listOf(
                "Sky Sports Backup", "Sky Sports Alt", "Sky Sports Alternate", "Sky Sports Raw",
                "Sky Sports Lite", "Sky Sports Mobile", "Sky Sports Test", "Sky Sports Low",
                "Sky Sports VIP", "Sky Sports Premium", "Sky Sports Pro",
                "Premier Sports 1 Backup HD", "ALT", "Raw", "VIP"
            ).forEach { add(Case(it)) }

            // --- language tags ---
            listOf(
                "BBC One EN", "BBC One English", "TF1 FR", "TF1 French",
                "ARD DE", "ARD German", "Rai 1 IT", "Rai 1 Italian",
                "La 1 ES", "La 1 Spanish", "RTP 1 PT", "RTP 1 Portuguese",
                "MBC 1 AR", "MBC 1 Arabic", "EN", "IT", "Pro"
            ).forEach { add(Case(it)) }

            // --- bracket / paren / pipe wrappers ---
            listOf(
                "[HD] BBC One", "(US) CNN", "|UK| Sky News", "BBC One [FHD]",
                "CNN (HD)", "Sky News |UK|", "BBC One (1080p)", "[4K] Sports",
                "Sky (Sports) 1 [HD] (UK)"
            ).forEach { add(Case(it)) }

            // --- frame rates ---
            listOf(
                "Sports 50fps", "Sports 60fps", "Sports 24fps", "Sports 25fps",
                "Sports 30fps", "Sports 50 FPS", "Sports fps 50",
                "Sports 120fps", "Sports 15fps"
            ).forEach { add(Case(it)) }

            // --- channel numbers must survive ---
            listOf(
                "101 BBC One HD", "Sky 401 Sports HD", "Sky Sports 1",
                "Channel 5 HD", "Sport 24 HD", "News 24"
            ).forEach { add(Case(it)) }

            // --- hash-wrapped provider headers ---
            listOf(
                "#### GENERAL HD/4K ####", "## SPORTS ##", "#### UK ENTERTAINMENT ####",
                "## MOVIES 24/7 ##"
            ).forEach { add(Case(it)) }

            // --- accents ---
            listOf(
                "Café TV HD", "München TV HD", "Télé France HD", "España TV HD",
                "Rai Città HD", "Zürich Sport HD", "Île de France TV"
            ).forEach { add(Case(it)) }

            // --- separator shapes ---
            listOf(
                "Sky-Sports-1-HD", "Sky_Sports_1_HD", "Sky.Sports.1.HD",
                "Sky/Sports/1/HD", "Sky Sports 1 +1", "Sky Sports 1: Extra",
                "  Padded  Name  HD  ", "Tabs\tAnd\tSpaces HD"
            ).forEach { add(Case(it)) }

            // --- adversarial: short tokens inside longer words ---
            listOf(
                "Advance TV", "Adventure HD", "DVD Movies", "Energy TV", "Item Shop",
                "Tests HD", "Latest News", "Remote Control", "Backupster TV",
                "Litehouse TV", "Profile TV", "Provider One", "Alternatives HD"
            ).forEach { add(Case(it)) }

            // --- long, tag-dense realistic names ---
            listOf(
                "UK: SKY SPORTS PREMIER LEAGUE FHD 1080p HEVC HDR 50fps Backup EN",
                "US - HBO MAX MOVIES 4K UHD DOLBY VISION HDR10 x265 60fps",
                "FR: CANAL+ SPORT 4K HDR HEVC 50fps VIP",
                "DE: SKY SPORT BUNDESLIGA UHD HDR10 HEVC 50fps Backup",
                "AR: MBC ACTION HD 1080p x264 25fps Premium Arabic",
                "UK: BT SPORT 1 HD 1080p H.264 50fps Alt English"
            ).forEach { add(Case(it)) }

            // --- single-token / minimal inputs ---
            listOf("HD", "1080p", "UHD", "4K", "SD", "mp4", "Test", "Backup", "Premium", "Low", "Mobile")
                .forEach { add(Case(it)) }
            add(Case(""))
            add(Case("   "))
            add(Case("A"))
            add(Case("12"))

            // --- URL-derived signals (name held constant) ---
            listOf(
                "http://example.com/live/user/pass/12345.ts",
                "http://example.com/live/user/pass/12345.m3u8",
                "http://example.com/live/user/pass/12345.mpd",
                "http://example.com/1080p/stream.ts",
                "http://example.com/720p/stream.m3u8",
                "http://example.com/hevc/stream.m3u8",
                "http://example.com/h265/stream.m3u8",
                "http://example.com/mpeg-ts/stream",
                "http://example.com/hls/stream",
                "http://example.com/av1/stream.m3u8",
                "http://example.com/uhd/hdr/stream.m3u8"
            ).forEach { add(Case("Sports Channel", it)) }
        }
    }
}
