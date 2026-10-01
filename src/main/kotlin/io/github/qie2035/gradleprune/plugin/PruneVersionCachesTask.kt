package io.github.qie2035.gradleprune.plugin

import io.github.qie2035.gradleprune.core.CachePaths
import io.github.qie2035.gradleprune.core.formatBytes
import io.github.qie2035.gradleprune.core.prune.VersionCachePruner
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Deletes *version-named* cache directories under the Gradle user home's
 * `caches/` that no longer match the Gradle version in use (e.g. leftover
 * `8.5` dirs after moving to `9.7.1`).
 *
 * This is the opt-in second cleanup scope from the design:
 *
 *  - only directory names matching the Gradle-version shape are candidates
 *    (`\d+\.\d+(\.\d+)?(-suffix)?`), so tooling dirs (`fabric-loom`,
 *    `neoformruntime`, `jars-9`, `metadata-*`, `resources-*`, …) are never
 *    touched;
 *  - the directory named after the current Gradle version is always kept;
 *  - dry-run by default: it only reports unless
 *    `-Pprune.versions.dryRun=false` is passed.
 *
 * Registered by [GradlePrunePlugin] on the root project as
 * `gradlePruneVersionCaches`.
 */
// Not cacheable: it deletes version-named directories under the Gradle user
// home at execution time; the directory state is not a trackable input.
@DisableCachingByDefault(
    because = "deletes directories under the Gradle user home; the directory state is not a trackable input",
)
abstract class PruneVersionCachesTask : DefaultTask() {

    init {
        group = "maintenance"
        description = "Delete stale Gradle version cache directories (e.g. caches/8.5 after moving to 9.7.1). " +
            "Opt-in; version-named dirs only. Dry-run by default; delete with -Pprune.versions.dryRun=false."
        notCompatibleWithConfigurationCache("deletes directories under the Gradle user home at execution time")
    }

    @get:Input
    var dryRun: Boolean = true

    /** Gradle version whose cache dir must be kept (set by the plugin). */
    @get:Internal
    var gradleVersion: String = ""

    /** The `caches` directory to scan. Defaults to `<guy>/caches`. */
    @get:Input
    @get:Optional
    var cachesDir: String? = null

    /**
     * The Gradle user home of the running build, set by [GradlePrunePlugin].
     * See [PruneGradleCacheTask.gradleUserHome] for why this is not read from
     * the `GRADLE_USER_HOME` environment variable.
     */
    @get:Internal
    var gradleUserHome: File? = null

    @TaskAction
    fun prune() {
        val paths = CachePaths.from(gradleUserHome = gradleUserHome)
        val caches = File(cachesDir ?: File(paths.gradleUserHome, "caches").absolutePath)
        val candidates = VersionCachePruner.candidates(caches, gradleVersion)

        if (candidates.isEmpty()) {
            logger.lifecycle(
                "gradle-prune: no stale Gradle version directories found under $caches " +
                    "(keeping $gradleVersion).",
            )
            return
        }

        if (dryRun) {
            val totalBytes = candidates.sumOf { VersionCachePruner.dirSize(it) }
            logger.lifecycle(
                "gradle-prune (dry-run): would delete ${candidates.size} stale version " +
                    "directory(ies), ${formatBytes(totalBytes)}:",
            )
            candidates.forEach {
                logger.lifecycle("  ${it.name} (${formatBytes(VersionCachePruner.dirSize(it))})")
            }
            logger.lifecycle("Re-run with -Pprune.versions.dryRun=false to actually delete.")
        } else {
            var freed = 0L
            var deleted = 0
            val failed = mutableListOf<String>()
            for (dir in candidates) {
                val size = VersionCachePruner.dirSize(dir)
                // `deleteRecursively()` reports failure by returning false; it
                // never throws, so the result must be checked explicitly.
                if (VersionCachePruner.delete(dir)) {
                    freed += size
                    deleted++
                } else {
                    failed += "${dir.name} (still present after delete; check permissions)"
                }
            }
            logger.lifecycle(
                "gradle-prune: deleted $deleted stale version directory(ies), " +
                    "freed ${formatBytes(freed)}.",
            )
            failed.forEach { logger.warn("gradle-prune: failed to delete: $it") }
        }
    }
}
