package io.github.qie2035.gradleprune.core.capture

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import java.io.File

/**
 * The safety-net capture described in the plan: instead of trusting the
 * resolution graph, record which files under `files-2.1` were *written* during
 * the build (mtime newer than a build-start timestamp) and map each back to
 * its `group:name:version`.
 *
 * This is an optional, off-by-default extra (`prune.captureDownloads=true`).
 * The primary capture is the resolution graph ([GraphWalker]); this one covers
 * files Gradle downloads but never reports as resolved (e.g. transitive
 * metadata fetches, build-script artifacts that don't show up in
 * `ResolutionResult`).
 */
object MtimeDeltaCapture {

    /**
     * Returns the module coordinates of every `g/n/v` directory under
     * [filesDir] that was touched at or after [sinceEpochMs].
     */
    fun capture(filesDir: File, sinceEpochMs: Long): Set<ModuleCoordinate> {
        if (!filesDir.isDirectory) return emptySet()
        val result = LinkedHashSet<ModuleCoordinate>()

        for (group in safeListDirs(filesDir)) {
            for (name in safeListDirs(group)) {
                for (version in safeListDirs(name)) {
                    if (lastModifiedOf(version) >= sinceEpochMs) {
                        result += ModuleCoordinate(group.name, name.name, version.name)
                    }
                }
            }
        }
        return result
    }

    /**
     * The newest mtime of any file (recursively) or directory under [dir].
     * Returns 0 for a missing directory.
     */
    fun lastModifiedOf(dir: File): Long {
        var newest = dir.lastModified()
        if (dir.isDirectory) {
            dir.walkTopDown().forEach { newest = newest.coerceAtLeast(it.lastModified()) }
        }
        return newest
    }

    private fun safeListDirs(dir: File): List<File> =
        dir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
}
