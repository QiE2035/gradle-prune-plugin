package io.github.qie2035.gradleprune.plugin

import io.github.qie2035.gradleprune.core.CachePaths
import io.github.qie2035.gradleprune.core.formatBytes
import io.github.qie2035.gradleprune.core.formatTimestamp
import io.github.qie2035.gradleprune.core.prune.PruneRefusal
import io.github.qie2035.gradleprune.core.prune.PruneReport
import io.github.qie2035.gradleprune.core.prune.PruneRequest
import io.github.qie2035.gradleprune.core.prune.PruneRunner
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
 * what the **surviving** registered builds still use — the Gradle equivalent
 * of `pnpm store prune`.
 *
 * Registered by [GradlePrunePlugin] on the root project as
 * `gradlePruneModules`. The decision itself lives in `core`'s `PruneRunner`,
 * shared verbatim with the CLI, so the two front-ends cannot drift apart
 * again. The task is never marked `@Output`-based, so it always runs.
 *
 * Defaults mirror the CLI: dry-run, refuse on an empty keep-set, warn on a live
 * cache lock, and drop the registry entries of builds whose root is gone.
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
        description = "Delete module versions from the shared Gradle cache that no surviving registered build uses (like `pnpm store prune`). " +
            "Dry-run by default; delete with -Pprune.modules.dryRun=false " +
            "(flags: -Pprune.modules.force/all/verbose=true)."
        notCompatibleWithConfigurationCache("reads/writes the on-disk module cache and registry at execution time")
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
     * Build roots to treat as still in use even when absent —
     * `-Pprune.keepRoots=/mnt/usb/app;/mnt/nas/tool`, for an unmounted drive or
     * an offline share. Roots can also live in `<registry>/keep-roots.txt`.
     */
    @get:Input
    @get:Optional
    var keepRoots: String? = null

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
        val report = PruneRunner.run(
            PruneRequest(
                paths = paths,
                dryRun = dryRun,
                force = force,
                deleteAll = all,
                keepRoots = parseKeepRoots(keepRoots),
            ),
        )
        logReport(report, logger, dryRun, verbose)

        if (report.refusal == PruneRefusal.UNREADABLE_REGISTRY) {
            throw GradleException(
                "gradle-prune: refusing to delete while unreadable registry files are present " +
                    "(${report.usage.unreadable.joinToString(", ") { it.name }}). Fix or remove them, " +
                    "or run with -Pprune.modules.force=true to override.",
            )
        }
    }
}

/** Splits `-Pprune.keepRoots=a;b,c` into individual paths. */
internal fun parseKeepRoots(raw: String?): List<String> =
    raw?.split(';', ',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

private const val MAX_LOGGED_ROOTS = 20

/**
 * Reports a run through Gradle's logger.
 *
 * Deliberately separate from `ReportRenderer`, which is Mordant-based: Gradle
 * styles its own logs, so the task must not emit ANSI codes. The *decisions*
 * are shared through `PruneRunner`; only the wording differs.
 */
private fun logReport(report: PruneReport, logger: Logger, dryRun: Boolean, verbose: Boolean) {
    val usage = report.usage
    fun log(message: String) = logger.lifecycle("gradle-prune: $message")

    if (usage.classified.isNotEmpty()) {
        log(
            "registered builds: ${usage.classified.size} — ${usage.live.size} in use" +
                (if (usage.unknown.isNotEmpty()) ", ${usage.unknown.size} unverifiable" else "") +
                (if (usage.stale.isNotEmpty()) ", ${usage.stale.size} gone" else ""),
        )
    }
    usage.stale.take(MAX_LOGGED_ROOTS).forEach {
        log(
            "  gone: ${it.buildRoot} (last built ${formatTimestamp(it.lastSeen)}, " +
                "${it.coordinates.size} module version(s))",
        )
    }
    usage.unknown.take(MAX_LOGGED_ROOTS).forEach {
        log("  unverifiable, kept: ${it.buildRoot}")
    }

    if (dryRun && usage.stale.isNotEmpty()) {
        val freed = report.plan?.toDelete?.keys?.count { it in usage.freedByStale } ?: 0
        log(
            "(dry-run) would drop ${usage.stale.size} stale registry " +
                plural(usage.stale.size, "entry", "entries") + " and free " +
                "${formatBytes(report.bytesFreedByStale)} across $freed module version(s) that no " +
                "surviving build references.",
        )
    }
    if (report.removedStaleEntries.isNotEmpty()) {
        val n = report.removedStaleEntries.size
        log(
            "dropped $n stale registry ${plural(n, "entry", "entries")}: " +
                report.removedStaleEntries.joinToString(", ") { it.buildRoot },
        )
    }
    if (usage.unreadable.isNotEmpty()) {
        logger.warn(
            "gradle-prune: ${usage.unreadable.size} registry file(s) could not be parsed and were " +
                "ignored, so anything only they referenced looks unused: " +
                usage.unreadable.joinToString(", ") { it.name },
        )
    }
    if (report.lockActive) {
        log(
            "the module cache lock looks freshly written — a Gradle build may be running. " +
                "Proceeding anyway (deletion is cache-only and safe); use " +
                "-Pprune.modules.force=true to silence this warning.",
        )
    }

    when (report.refusal) {
        // Handled by the caller, which turns it into a build failure.
        PruneRefusal.UNREADABLE_REGISTRY -> Unit

        PruneRefusal.NOTHING_REGISTERED -> log(
            if (usage.classified.isEmpty()) {
                "no builds are registered under ${report.paths.registryDir}. Nothing was deleted. " +
                    "Apply the plugin to a project and build it, or run with " +
                    "-Pprune.modules.all=true to delete the entire module cache."
            } else {
                "every registered build is gone, so nothing is still in use and nothing was " +
                    "pruned. Run with -Pprune.modules.all=true to delete the entire module cache " +
                    "(that also clears the ${usage.stale.size} stale registry " +
                    plural(usage.stale.size, "entry", "entries") + "), or pin a temporarily " +
                    "absent root with -Pprune.keepRoots=… ."
            },
        )

        PruneRefusal.NO_CACHE -> log(
            "no module cache found at ${report.paths.filesDir} — nothing to do.",
        )

        null -> {
            val plan = report.plan ?: return
            val result = report.result ?: return
            log(
                "${plan.present.size} cached module version(s), ${plan.keep.size} in use, " +
                    "${plan.toDelete.size} to delete" +
                    (if (plan.keepSetEmpty) " (--all: the registry is ignored)." else "."),
            )
            if (dryRun) {
                log(
                    "(dry-run) would free ${formatBytes(plan.freedBytes)} across " +
                        "${plan.toDelete.size} module version(s). Re-run with " +
                        "-Pprune.modules.dryRun=false to delete for real.",
                )
            } else {
                log(
                    "freed ${formatBytes(result.deletedBytes)} across " +
                        "${result.deletedModules.size} module version(s).",
                )
            }
            if (verbose && plan.toDelete.isNotEmpty()) {
                plan.toDelete.keys.sortedBy { it.toString() }.forEach {
                    val staleOnly = if (it in usage.freedByStale) "  (only a gone build used it)" else ""
                    log("  delete $it$staleOnly")
                }
            }
            if (result.failed.isNotEmpty()) {
                logger.warn("gradle-prune: ${result.failed.size} deletion(s) failed (retried next run):")
                result.failed.take(10).forEach { logger.warn("  $it") }
            }
        }
    }
}

private fun plural(count: Int, singular: String, plural: String): String =
    if (count == 1) singular else plural
