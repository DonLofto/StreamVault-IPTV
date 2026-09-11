package com.streamvault.data.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * A5 - [XmltvParser.parseDate] now skips a candidate format whose necessary condition the string fails,
 * instead of discovering the mismatch by throwing and catching a DateTimeParseException.
 *
 * The risk that introduces is a guard that is too strict, so this pins every format the parser
 * supports, plus shapes that must still be rejected. 2025-01-01 12:00:00 UTC = 1735732800000.
 */
class XmltvParserDateFormatCoverageTest {

    private lateinit var parser: XmltvParser

    @Before
    fun setUp() {
        System.setProperty("org.xmlpull.v1.XmlPullParserFactory", "org.kxml2.io.KXmlParser")
        parser = XmltvParser()
    }

    private fun startTimeOf(start: String, stop: String): Long {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="$start" stop="$stop" channel="ch1">
                <title>Programme</title>
              </programme>
            </tv>
        """.trimIndent()
        val programs = parser.parse(xml.byteInputStream())
        return programs.singleOrNull()?.startTime ?: 0L
    }

    private fun assertParsesTo(
        start: String,
        stop: String,
        expected: Long,
        label: String
    ) {
        assertThat(startTimeOf(start, stop)).isEqualTo(expected)
    }

    @Test
    fun `every offset bearing format still parses to the same instant`() {
        val utcNoon = 1_735_732_800_000L
        val utcOnePm = 1_735_736_400_000L

        // The five offset formats, in their declared priority order.
        assertParsesTo("20250101120000 +0000", "20250101130000 +0000", utcNoon, "space numeric offset")
        assertParsesTo("20250101120000+0000", "20250101130000+0000", utcNoon, "numeric offset")
        assertParsesTo("20250101120000+00:00", "20250101130000+00:00", utcNoon, "colon offset")
        assertParsesTo("20250101120000Z", "20250101130000Z", utcNoon, "Z designator")
        assertParsesTo("20250101120000+00", "20250101130000+00", utcNoon, "short offset")
        assertParsesTo("2025-01-01T12:00:00+00:00", "2025-01-01T13:00:00+00:00", utcNoon, "ISO colon offset")

        // A non-zero offset must still shift the instant rather than being dropped.
        assertParsesTo("20250101120000+0300", "20250101130000+0300", utcNoon - 3 * 3_600_000L, "numeric +0300")
        assertParsesTo("20250101120000 +0300", "20250101130000 +0300", utcNoon - 3 * 3_600_000L, "space +0300")
        assertParsesTo("20250101120000+03:00", "20250101130000+03:00", utcNoon - 3 * 3_600_000L, "colon +03:00")
        assertParsesTo("20250101120000+03", "20250101130000+03", utcNoon - 3 * 3_600_000L, "short +03")
        assertParsesTo("2025-01-01T12:00:00+03:00", "2025-01-01T13:00:00+03:00", utcNoon - 3 * 3_600_000L, "ISO +03:00")

        // Guard against an unused-variable warning while keeping the second instant documented.
        assertThat(utcOnePm).isEqualTo(utcNoon + 3_600_000L)
    }

    @Test
    fun `every offset less format still parses`() {
        // These resolve in the parsing zone, so only "it parsed at all" is stable across machines.
        assertThat(startTimeOf("20250101120000", "20250101130000")).isGreaterThan(0L)
        assertThat(startTimeOf("2025-01-01T12:00:00", "2025-01-01T13:00:00")).isGreaterThan(0L)
        assertThat(startTimeOf("2025-01-01T12:00:00Z", "2025-01-01T13:00:00Z")).isGreaterThan(0L)
        assertThat(startTimeOf("2025-01-01 12:00:00", "2025-01-01 13:00:00")).isGreaterThan(0L)
        assertThat(startTimeOf("202501011200", "202501011300")).isGreaterThan(0L)
        assertThat(startTimeOf("20250101", "20250102")).isGreaterThan(0L)
    }

    @Test
    fun `shapes no format admits are still rejected`() {
        // Too short for any candidate, and the two shapes that killed the earlier attempts.
        assertThat(startTimeOf("2025010112", "2025010113")).isEqualTo(0L)
        assertThat(startTimeOf("invalid", "invalid")).isEqualTo(0L)
        assertThat(startTimeOf("2025-01-01", "2025-01-02")).isEqualTo(0L)
    }
}
