package io.github.qie2035.gradleprune.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared, presentation-only formatting.
 *
 * Single source of truth: the CLI report and the Gradle task output used to
 * carry verbatim copies of this logic, which is exactly how the two front-ends
 * drift apart.
 */

/**
 * Human-readable byte formatting (B/KB/MB/GB/TB/PB).
 *
 * The unit ladder is walked to the end rather than "assigned on break", so
 * absurdly large inputs (>= 1 PB) report `PB` instead of falling back to a
 * nonsensical `… B`.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    var unit = "B"
    for (u in listOf("KB", "MB", "GB", "TB", "PB")) {
        value /= 1024.0
        unit = u
        if (value < 1024.0) break
    }
    val s = if (value >= 100) value.toLong().toString() else "%.1f".format(value)
    return "$s $unit"
}

private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Formats a registry `lastSeen` epoch-millis for display.
 *
 * Purely informational — **no pruning decision reads a timestamp**. It is shown
 * so the operator can sanity-check a stale entry ("was this really built long
 * ago?") before the entry is dropped.
 */
fun formatTimestamp(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (epochMillis <= 0L) "unknown"
    else TIMESTAMP.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
