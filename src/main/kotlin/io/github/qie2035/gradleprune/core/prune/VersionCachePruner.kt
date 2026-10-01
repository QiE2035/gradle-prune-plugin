package io.github.qie2035.gradleprune.core.prune

import java.io.File

/**
 * Cleans up *version-named* Gradle cache directories that no longer match the
 * Gradle version currently in use.
 *
 * Under `<guy>/caches/` Gradle keeps per-version state dirs such as `9.7.1`,
 * `8.5`, etc. (task history, transformed artifacts, …). Once you move to a new
 * Gradle version the old ones are dead weight.
 *
 * This is intentionally **conservative** and **opt-in** (a separate task/flag,
 * not part of the default `prune`):
 *  - it only considers directory names that match a Gradle-version shape
 *    (`\d+\.\d+(\.\d+)?(-<suffix>)?`), so tooling dirs like `fabric-loom`,
 *    `neoformruntime`, `jars-9`, `metadata-*`, `resources-*` are never touched;
 *  - it always keeps the directory named after the [currentVersion].
 */
object VersionCachePruner {

    // Matches Gradle version shapes: 8.5, 9.7.1, 9.7-rc-1, 10.0-milestone-2.
    // The optional suffix may contain hyphens (pre-release tags) but the name
    // must *start* with the numeric version, so tooling dirs (jars-9,
    // metadata-2.107, modules-2, …) never match.
    private val VERSION_DIR = Regex("""\d+\.\d+(\.\d+)?(-[\w.-]+)?""")

    /**
     * @param cachesDir the `<guy>/caches` directory.
     * @param currentVersion the Gradle version to keep (e.g. `9.7.1`).
     * @return the version-named directories eligible for deletion.
     */
    fun candidates(cachesDir: File, currentVersion: String): List<File> {
        if (!cachesDir.isDirectory) return emptyList()
        return cachesDir.listFiles { f ->
            f.isDirectory && f.name != currentVersion && VERSION_DIR.matches(f.name)
        }?.sortedBy { it.name } ?: emptyList()
    }

    /** Total bytes (files) under [dir], best-effort. */
    fun dirSize(dir: File): Long {
        var total = 0L
        if (!dir.isDirectory) return 0L
        try {
            dir.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
        } catch (e: Exception) {
            // best effort
        }
        return total
    }

    /**
     * Deletes [dir] recursively, reporting whether it is really gone.
     *
     * `File.deleteRecursively()` signals failure by **returning `false`** — it
     * does not throw — so a `try { … } catch` around it silently counts failed
     * deletions as successes. Callers must branch on this result instead.
     *
     * A directory that is already absent counts as success (idempotent).
     */
    fun delete(dir: File): Boolean {
        if (!dir.exists()) return true
        dir.deleteRecursively()
        return !dir.exists()
    }
}
