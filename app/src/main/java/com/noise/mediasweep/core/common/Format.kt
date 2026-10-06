package com.noise.mediasweep.core.common

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Human readable byte formatting.
 *
 * Binary (1024) steps with the labels users recognise, trailing zeros trimmed:
 * 1_887_436_800 -> "1.76 GB", 512 -> "512 B".
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val rounded = if (unit == 0) abs(value).toLong().toString() else trimZeros(value)
    return "$rounded ${units[unit]}"
}

private fun trimZeros(value: Double): String {
    val formatted = String.format(Locale.US, "%.2f", value)
    return formatted.trimEnd('0').trimEnd('.')
}

/** Thousands-separated counter, e.g. 12240 -> "12,240". */
fun formatCount(count: Long): String = String.format(Locale.US, "%,d", count)

/** Progress label, e.g. "4,832 / 12,240". */
fun formatProgress(processed: Long, total: Long): String =
    "${formatCount(processed)} / ${formatCount(total)}"

/** Stable day-level age comparison used by the old-media criterion. */
fun ageInDays(unixSeconds: Long, nowSeconds: Long): Long =
    (nowSeconds - unixSeconds) / (24 * 60 * 60)

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

/**
 * Human-readable media date, e.g. "May 12, 2026" (specification §29).
 *
 * [DateTimeFormatter] is immutable and thread-safe, and the zone is resolved per call:
 * list rows format dates without taking a shared lock (a scan may be running on another
 * thread) and a runtime time-zone change is still reflected immediately.
 */
fun formatDate(unixSeconds: Long): String = DATE_FORMAT.format(
    LocalDateTime.ofInstant(Instant.ofEpochSecond(unixSeconds), ZoneId.systemDefault()),
)
