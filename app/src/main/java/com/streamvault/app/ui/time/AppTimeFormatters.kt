package com.streamvault.app.ui.time

import androidx.compose.runtime.compositionLocalOf
import com.streamvault.domain.model.AppTimeFormat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

val LocalAppTimeFormat = compositionLocalOf { AppTimeFormat.SYSTEM }

/**
 * These factories are called from composition, so they must not rebuild a formatter each time.
 *
 * Constructing a `DateFormat` runs `SimpleDateFormat` initialisation, which loads ICU decimal-format
 * symbols and builds a `NumberFormat`. That appeared as a hot stack frame in the on-device profile
 * (reached from `rememberSettingsScreenLabels` during `SettingsScreen` composition).
 *
 * `DateFormat` is mutable and **not** thread-safe, so callers receive a `clone()` of a cached
 * prototype rather than the shared instance; cloning skips the expensive ICU initialisation.
 * `DateTimeFormatter` is immutable and thread-safe, so it is shared directly.
 */
private val timeFormatPrototypes = ConcurrentHashMap<Pair<AppTimeFormat, Locale>, DateFormat>()
private val dateTimeFormatPrototypes = ConcurrentHashMap<Pair<AppTimeFormat, Locale>, DateFormat>()
private val timeFormatters = ConcurrentHashMap<Pair<AppTimeFormat, Locale>, DateTimeFormatter>()

fun AppTimeFormat.createTimeFormat(locale: Locale = Locale.getDefault()): DateFormat =
    timeFormatPrototypes.getOrPut(this to locale) { buildTimeFormat(locale) }.clone() as DateFormat

fun AppTimeFormat.createDateTimeFormat(locale: Locale = Locale.getDefault()): DateFormat =
    dateTimeFormatPrototypes.getOrPut(this to locale) { buildDateTimeFormat(locale) }.clone() as DateFormat

fun AppTimeFormat.createTimeFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    timeFormatters.getOrPut(this to locale) { buildTimeFormatter(locale) }

private fun AppTimeFormat.buildTimeFormat(locale: Locale): DateFormat = when (this) {
    AppTimeFormat.SYSTEM -> DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    AppTimeFormat.TWELVE_HOUR -> SimpleDateFormat("h:mm a", locale)
    AppTimeFormat.TWENTY_FOUR_HOUR -> SimpleDateFormat("HH:mm", locale)
}

private fun AppTimeFormat.buildDateTimeFormat(locale: Locale): DateFormat = when (this) {
    AppTimeFormat.SYSTEM -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
    AppTimeFormat.TWELVE_HOUR -> SimpleDateFormat("MMM d, h:mm a", locale)
    AppTimeFormat.TWENTY_FOUR_HOUR -> SimpleDateFormat("MMM d, HH:mm", locale)
}

private fun AppTimeFormat.buildTimeFormatter(locale: Locale): DateTimeFormatter = when (this) {
    AppTimeFormat.SYSTEM -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    AppTimeFormat.TWELVE_HOUR -> DateTimeFormatter.ofPattern("h:mm a", locale)
    AppTimeFormat.TWENTY_FOUR_HOUR -> DateTimeFormatter.ofPattern("HH:mm", locale)
}
