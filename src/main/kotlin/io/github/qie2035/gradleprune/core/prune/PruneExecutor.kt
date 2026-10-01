package io.github.qie2035.gradleprune.core.prune

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Executes a [PrunePlan] against the real `files-2.1` tree: deletes the
 * `g/n/v` directories that the plan marks for deletion, then bottom-up
 * prunes parent directories (`name` and `group`) that have become empty.
 *
 * Safe-by-construction:
 *  - only ever deletes directories under [filesDir];
 *  - a failed deletion of a version dir is recorded and the run continues
 *    with the rest (a later run retries it);
 *  - `--dry-run` performs no writes at all.
 */
class PruneExecutor(private val filesDir: File) {

    /**
     * Deletes [PrunePlan.toDelete]. Returns a summary of what happened.
     *
     * @param dryRun when true, nothing is deleted; the summary simply echoes
     *   the plan's intended deletions.
     */
    fun execute(plan: PrunePlan, dryRun: Boolean = false): PruneResult {
        val deleted = mutableListOf<ModuleCoordinate>()
        val freedBytes = mutableListOf<Pair<ModuleCoordinate, Long>>()
        val failed = mutableListOf<String>()

        if (!dryRun && plan.toDelete.isNotEmpty()) {
            for ((coord, size) in plan.toDelete) {
                val dir = File(File(File(filesDir, coord.group), coord.name), coord.version)
                if (!dir.exists()) {
                    // Already gone (e.g. by a concurrent run) — not a failure.
                    continue
                }
                try {
                    deleteRecursively(dir)
                    deleted += coord
                    freedBytes += coord to size
                } catch (e: IOException) {
                    failed += "${coord} (${e.message})"
                }
            }
            pruneEmptyParents()
        }

        val actualFreed = if (dryRun) 0L else freedBytes.sumOf { it.second }
        return PruneResult(
            dryRun = dryRun,
            deletedModules = deleted,
            deletedBytes = actualFreed,
            failed = failed,
            keptModules = plan.keep.keys,
        )
    }

    /**
     * After deleting version dirs, remove `name` and `group` dirs that have
     * no entries left. Bottom-up so a group dir is only removed once all of
     * its name dirs are gone.
     */
    private fun pruneEmptyParents() {
        if (!filesDir.isDirectory) return
        val groupDirs = filesDir.listFiles { f -> f.isDirectory } ?: return
        for (group in groupDirs) {
            if (isEmptyDir(group)) {
                group.delete()
                continue
            }
            val nameDirs = group.listFiles { f -> f.isDirectory } ?: continue
            for (name in nameDirs) {
                if (isEmptyDir(name)) {
                    name.delete()
                }
            }
            if (isEmptyDir(group)) {
                group.delete()
            }
        }
    }

    private fun isEmptyDir(dir: File): Boolean =
        dir.isDirectory &&
            !Files.isSymbolicLink(dir.toPath()) &&
            dir.listFiles().isNullOrEmpty()

    /**
     * Bottom-up recursive delete. Throws [IOException] on the first failure.
     *
     * Symlinks are deleted as links and never descended into: `File.isDirectory`
     * follows them, so a symlinked version directory would otherwise have its
     * *target's* contents deleted. `CacheScanner.dirSize` walks with
     * `Files.walk` (no `FOLLOW_LINKS`), so it already treats symlinks as leaves
     * — this keeps deletion and size accounting consistent.
     */
    private fun deleteRecursively(dir: File) {
        val path = dir.toPath()
        // NOFOLLOW_LINKS so a *dangling* symlink is still cleaned up.
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (dir.isDirectory && !Files.isSymbolicLink(path)) {
            dir.listFiles()?.forEach { deleteRecursively(it) }
        }
        if (!dir.delete()) {
            throw IOException("could not delete ${dir.path}")
        }
    }
}

/** The outcome of one (possibly dry) prune execution. */
data class PruneResult(
    val dryRun: Boolean,
    val deletedModules: List<ModuleCoordinate>,
    val deletedBytes: Long,
    /** Human-readable failures (empty on success). */
    val failed: List<String>,
    val keptModules: Set<ModuleCoordinate>,
) {
    val success: Boolean get() = failed.isEmpty()
}
