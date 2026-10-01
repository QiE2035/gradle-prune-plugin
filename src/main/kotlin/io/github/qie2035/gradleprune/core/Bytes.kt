package io.github.qie2035.gradleprune.core

/**
 * Human-readable byte formatting (B/KB/MB/GB/TB/PB).
 *
 * Single source of truth: the CLI report and the Gradle task output used to
 * carry two verbatim copies of this function, which is exactly how the two
 * front-ends drift apart.
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
