package io.github.qie2035.gradleprune.core

import java.io.File

/**
 * A single module version as stored in the Gradle dependency cache:
 * `group:name:version`, mirroring the cache layout
 * `modules-2/files-2.1/<group>/<name>/<version>/`.
 *
 * The registry stores coordinates as the [toString] form (`g:n:v`); the prune
 * engine maps them back to the [toDir] directory relative to `files-2.1`.
 *
 * Note: a Gradle `group` may itself contain dots (e.g. `com.google`), and a
 * `name` may contain dots too — only the version slot is what varies per file,
 * so splitting [toString] is safe as long as we split on the *first* and
 * *last* colon.
 */
data class ModuleCoordinate(
    val group: String,
    val name: String,
    val version: String,
) {
    /** Canonical registry form: `group:name:version`. */
    override fun toString(): String = "$group:$name:$version"

    /** Directory under `files-2.1` that holds this module version. */
    fun toDir(baseDir: File): File = File(File(File(baseDir, group), name), version)

    fun toDir(baseDir: String): String = "$baseDir/$group/$name/$version"

    init {
        require(group.isNotBlank()) { "group must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(":" !in version) { "version must not contain a colon: $version" }
    }

    companion object {
        /**
         * Parses a `group:name:version` string. Group and name may contain
         * dots; the version must not contain a colon. Splits on the first
         * colon (group) and the last colon (version).
         */
        fun parse(raw: String): ModuleCoordinate {
            val first = raw.indexOf(':')
            require(first > 0) { "invalid coordinate (missing group): $raw" }
            val last = raw.lastIndexOf(':')
            require(last > first) { "invalid coordinate (missing name/version): $raw" }
            return ModuleCoordinate(
                group = raw.substring(0, first),
                name = raw.substring(first + 1, last),
                version = raw.substring(last + 1),
            )
        }
    }
}
