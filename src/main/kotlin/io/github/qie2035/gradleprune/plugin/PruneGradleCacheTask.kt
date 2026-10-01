package io.github.qie2035.gradleprune.plugin

import io.github.qie2035.gradleprune.core.CachePaths
import io.github.qie2035.gradleprune.core.formatBytes
import io.github.qie2035.gradleprune.core.prune.CacheScanner
import io.github.qie2035.gradleprune.core.prune.LockGuard
import io.github.qie2035.gradleprune.core.prune.PruneExecutor
import io.github.qie2035.gradleprune.core.prune.PrunePlanner
import io.github.qie2035.gradleprune.core.registry.RegistryStore
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
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
// Not cacheable: it deletes from the shared on-disk module cache based on
// registry files and cache state that are not trackable task inputs — the
// task must always run against the current state of the cache.
@DisableCachingByDefault(
    because = "prunes the shared on-disk module cache; the cache state is not a trackable input",
)
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

    /** `-Pprune.modules.all=true`: ignore the registry and wipe the cache. */
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

    /**
     * The Gradle user home of the *running* build, set by [GradlePrunePlugin].
     *
     * Deliberately not derived from the `GRADLE_USER_HOME` environment
     * variable: inside a build the source of truth is
     * `Gradle.getGradleUserHomeDir()`, which also honours `-g` /
     * `--gradle-user-home`. Resolving from the environment here would point
     * the delete at a different cache than the one the build uses.
     */
    @get:Internal
    var gradleUserHome: File? = null

    @TaskAction
    fun prune() {
        val paths = CachePaths.from(
            gradleUserHome = gradleUserHome,
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

    // A registry file we cannot parse silently shrinks the keep-set, so
    // modules that a real build still needs start looking unused. Report it,
    // and refuse to act on it unless explicitly forced.
    val unreadable = store.unreadable()
    if (unreadable.isNotEmpty()) {
        logger.warn(
            "gradle-prune: ${unreadable.size} registry file(s) could not be parsed and were " +
                "ignored: ${unreadable.joinToString(", ") { it.name }}. Modules only they " +
                "referenced look unused and would be deleted.",
        )
        if (!dryRun && !force) {
            throw GradleException(
                "gradle-prune: refusing to delete while unreadable registry files are present " +
                    "(${unreadable.joinToString(", ") { it.name }}). Fix or remove them, or run " +
                    "with -Pprune.modules.force=true to override.",
            )
        }
    }

    if (keep.isEmpty() && !all) {
        logger.lifecycle(
            "gradle-prune: no builds are registered under ${paths.registryDir}. " +
                "Nothing was deleted. Apply the plugin to a project and build it, " +
                "or run with -Pprune.modules.all=true to delete the entire module cache.",
        )
        return
    }

    if (!paths.filesDir.isDirectory) {
        logger.lifecycle(
            "gradle-prune: no module cache found at ${paths.filesDir} — nothing to do.",
        )
        return
    }

    val lock = LockGuard(paths.modulesLock)
    if (!force && lock.check() == LockGuard.LockStatus.ACTIVE) {
        logger.lifecycle(
            "gradle-prune: the module cache lock looks freshly written — a Gradle " +
                "build may be running. Proceeding anyway (deletion is cache-only and " +
                "safe); use -Pprune.modules.force=true to silence this warning.",
        )
    }

    val present = CacheScanner.scan(paths.filesDir)
    val plan = PrunePlanner.plan(present, keep, deleteAll = all)
    logger.lifecycle(
        "gradle-prune: ${plan.present.size} cached module version(s), " +
            "${plan.keep.size} kept, ${plan.toDelete.size} to delete" +
            if (all) " (--all: the registry is ignored)." else ".",
    )

    val executor = PruneExecutor(paths.filesDir)
    val result = executor.execute(plan, dryRun)

    // Rendered via a tiny text report (no Mordant in task output; Gradle
    // styles its own logs).
    if (dryRun) {
        logger.lifecycle(
            "gradle-prune (dry-run): would free " +
                formatBytes(plan.freedBytes) +
                " across ${plan.toDelete.size} module version(s). " +
                    "Re-run with -Pprune.modules.dryRun=false to delete for real.",
        )
    } else {
        logger.lifecycle(
            "gradle-prune: freed " +
                formatBytes(result.deletedBytes) +
                " across ${result.deletedModules.size} module version(s).",
        )
    }
    if (verbose && plan.toDelete.isNotEmpty()) {
        plan.toDelete.keys.sortedBy { it.toString() }.forEach {
            logger.lifecycle("  delete ${it}")
        }
    }
    if (result.failed.isNotEmpty()) {
        logger.warn("gradle-prune: ${result.failed.size} deletion(s) failed:")
        result.failed.take(10).forEach { logger.warn("  $it") }
    }
}
