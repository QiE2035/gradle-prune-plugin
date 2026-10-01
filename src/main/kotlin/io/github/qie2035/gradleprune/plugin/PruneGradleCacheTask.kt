package io.github.qie2035.gradleprune.plugin

import io.github.qie2035.gradleprune.core.CachePaths
import io.github.qie2035.gradleprune.core.prune.CacheScanner
import io.github.qie2035.gradleprune.core.prune.LockGuard
import io.github.qie2035.gradleprune.core.prune.PruneExecutor
import io.github.qie2035.gradleprune.core.prune.PrunePlanner
import io.github.qie2035.gradleprune.core.registry.RegistryStore
import org.gradle.api.DefaultTask
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Prunes the shared Gradle dependency cache (`modules-2/files-2.1`) down to
 * the union of all registered builds — the Gradle equivalent of
 * `pnpm store prune`.
 *
 * Registered by [GradlePrunePlugin] on the root project as
 * `gradlePruneModules`. The task itself is cache-scoped (it never affects the
 * calling build) and is safe to run any time; it is **never** marked
 * `@Output`-based so it always runs.
 *
 * Defaults mirror the CLI: dry-run, refuse on empty registry, warn on a live
 * cache lock.
 */
abstract class PruneGradleCacheTask : DefaultTask() {

    init {
        group = "maintenance"
        description = "Delete module versions from the shared Gradle cache that no registered build uses (like `pnpm store prune`). " +
            "Dry-run by default; delete with -Pprune.modules.dryRun=false " +
            "(flags: -Pprune.modules.force/all/verbose=true)."
        notCompatibleWithConfigurationCache("reads/writes the on-disk module cache at execution time")
    }

    @get:Input
    var dryRun: Boolean = true

    @get:Input
    var force: Boolean = false

    @get:Input
    var all: Boolean = false

    @get:Input
    var verbose: Boolean = false

    /** Deletion root (`files-2.1`). */
    @get:Input
    @get:Optional
    var modulesDir: String? = null

    /** Registry directory. */
    @get:Input
    @get:Optional
    var registryDir: String? = null

    @TaskAction
    fun prune() {
        val paths = CachePaths.from(
            filesDir = modulesDir?.let(::File),
            registryDir = registryDir?.let(::File),
        )
        runCore(
            paths = paths,
            dryRun = dryRun,
            force = force,
            all = all,
            verbose = verbose,
            logger = logger,
        )
    }
}

/**
 * Shared implementation used by the task and (conceptually) the CLI so both
 * behave identically. Kept as a top-level function to keep the task class
 * thin and testable.
 */
internal fun runCore(
    paths: CachePaths,
    dryRun: Boolean,
    force: Boolean,
    all: Boolean,
    verbose: Boolean,
    logger: Logger,
) {
    val store = RegistryStore(paths.registryDir)
    val keep = store.union()

    if (keep.isEmpty() && !all) {
        logger.lifecycle(
            "gradle-prune: no builds are registered under ${paths.registryDir}. " +
                "Nothing was deleted. Apply the plugin to a project and build it, " +
                "or run with -Pprune.modules.all=true to delete the entire module cache.",
        )
        return
    }

    val lock = LockGuard(paths.modulesLock)
    if (!force && lock.check() == LockGuard.LockStatus.ACTIVE) {
        logger.lifecycle(
            "gradle-prune: the module cache lock looks freshly written — a Gradle " +
                "build may be running. Proceeding anyway (deletion is cache-only and " +
                "safe); use --force to silence this warning.",
        )
    }

    if (!paths.filesDir.isDirectory) {
        logger.lifecycle(
            "gradle-prune: no module cache found at ${paths.filesDir} — nothing to do.",
        )
        return
    }

    val present = CacheScanner.scan(paths.filesDir)
    val plan = PrunePlanner.plan(present, keep, allowEmptyKeepSet = all)
    if (verbose) {
        logger.lifecycle(
            "gradle-prune: ${plan.present.size} cached module version(s), " +
                "${plan.keep.size} kept, ${plan.toDelete.size} to delete.",
        )
    }

    val executor = PruneExecutor(paths.filesDir)
    val result = executor.execute(plan, dryRun)

    // Rendered via a tiny text report (no Mordant in task output; Gradle
    // styles its own logs).
    if (dryRun) {
        logger.lifecycle(
            "gradle-prune (dry-run): would free " +
                formatBytesHuman(plan.freedBytes) +
                " across ${plan.toDelete.size} module version(s). " +
                    "Re-run with -Pprune.modules.dryRun=false to delete for real.",
        )
    } else {
        logger.lifecycle(
            "gradle-prune: freed " +
                formatBytesHuman(result.deletedBytes) +
                " across ${result.deletedModules.size} module version(s).",
        )
    }
    if (result.failed.isNotEmpty()) {
        logger.warn("gradle-prune: ${result.failed.size} deletion(s) failed:")
        result.failed.take(10).forEach { logger.warn("  $it") }
    }
}

/** Byte formatting identical to the CLI's, kept here so the task has no Mordant dependency. */
internal fun formatBytesHuman(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    var unit = "B"
    for (u in listOf("KB", "MB", "GB", "TB")) {
        value /= 1024.0
        if (value < 1024.0) {
            unit = u
            break
        }
    }
    val s = if (value >= 100) value.toLong().toString() else "%.1f".format(value)
    return "$s $unit"
}
