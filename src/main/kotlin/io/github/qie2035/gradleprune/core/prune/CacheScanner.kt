package io.github.qie2035.gradleprune.core.prune

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Scans the `files-2.1` directory for the module versions that physically
 * exist, i.e. the `group/name/version` directories, and reports their total
 * on-disk size.
 *
 * Only the top three directory levels are considered (group, name, version);
 * anything deeper is the per-file sha1 sharding and is never enumerated on
 * its own.
 */
object CacheScanner {

    /**
     * Returns `module -> totalBytes` for every `g/n/v` directory under
     * [filesDir]. Empty map when the directory is missing.
     */
    fun scan(filesDir: File): Map<ModuleCoordinate, Long> {
        val result = LinkedHashMap<ModuleCoordinate, Long>()
        if (!filesDir.isDirectory) return result

        for (group in safeListDirs(filesDir)) {
            for (name in safeListDirs(group)) {
                for (version in safeListDirs(name)) {
                    // A directory name the coordinate grammar rejects (e.g. a
                    // stray `:` — legal in a Linux file name) must not abort
                    // the whole scan; the rest of the cache is still scannable.
                    val coord = coordinateOf(group.name, name.name, version.name) ?: continue
                    result[coord] = dirSize(version)
                }
            }
        }
        return result
    }

    /** [ModuleCoordinate] for the three dir names, or null when malformed. */
    private fun coordinateOf(group: String, name: String, version: String): ModuleCoordinate? =
        try {
            ModuleCoordinate(group, name, version)
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun safeListDirs(dir: File): List<File> =
        dir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()

    /** Total size (bytes) of all regular files under [dir]. */
    private fun dirSize(dir: File): Long {
        var total = 0L
        val stream = try {
            Files.walk(dir.toPath())
        } catch (e: IOException) {
            return 0L
        }
        try {
            stream.filter { Files.isRegularFile(it) }.forEach {
                total += Files.size(it)
            }
        } catch (e: IOException) {
            // Count what we could; a partial walk is acceptable for reporting.
        } finally {
            stream.close()
        }
        return total
    }
}
