package io.github.qie2035.gradleprune.core

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.muted
import com.github.ajalt.mordant.terminal.success
import com.github.ajalt.mordant.terminal.warning
import io.github.qie2035.gradleprune.core.prune.CacheScanner
import io.github.qie2035.gradleprune.core.prune.LockGuard
import io.github.qie2035.gradleprune.core.prune.PruneExecutor
import io.github.qie2035.gradleprune.core.prune.PrunePlanner
import io.github.qie2035.gradleprune.core.registry.RegistryStore
import io.github.qie2035.gradleprune.core.report.ReportRenderer
import java.io.File

/**
 * The standalone prune CLI — the user-facing half of the tool, runnable via
 * `gradle run --args="…"` (application plugin) or a fat jar.
 *
 * Semantics (registry union, the Gradle equivalent of `pnpm store prune`):
 *
 *   keep  = union of module coordinates registered by ALL builds
 *   delete = `modules-2/files-2.1/<g>/<n>/<v>` directories NOT in keep
 *
 * Safety defaults:
 *
 *   - **dry-run by default** — nothing is deleted unless `--apply` is passed;
 *   - an **empty registry refuses** to delete anything unless `--all` (wipe
 *     everything) is passed;
 *   - a **fresh `modules-2.lock` mtime** triggers a non-blocking warning
 *     (silenced by `--force`);
 *   - only the dependency cache is ever touched; `metadata-*`, `resources-*`,
 *     `gc.properties` and the lock file are never modified.
 */
class PruneCommand : CliktCommand(name = "gradle-prune") {

    override fun help(context: Context) =
        "Prune unused module versions from the shared Gradle dependency cache (like `pnpm store prune`)."

    override fun helpEpilog(context: Context) =
        "Example: gradle-prune --apply   # delete modules no registered build uses"

    /** Delete for real. Off by default (dry-run). */
    private val apply by option("--apply", help = "Actually delete. Without this flag the run is a dry-run.")
        .flag()

    /** Silences the active-lock warning. */
    private val force by option("--force", help = "Proceed without the active-lock warning.")
        .flag()

    /** Delete everything, even when no build is registered (dangerous). */
    private val all by option("--all", help = "Delete ALL cached modules even if no build is registered.")
        .flag()

    private val verbose by option("--verbose", "-v", help = "List every module to be deleted.")
        .flag()

    private val registry by option("--registry", help = "Registry directory (default: \$GRADLE_USER_HOME/prune/registry).")
        .default("")

    private val modulesDir by option("--modules-dir", help = "The modules-2/files-2.1 directory to prune (default: \$GRADLE_USER_HOME/caches/modules-2/files-2.1).")
        .default("")

    override fun run() {
        val terminal = Terminal()
        val paths = CachePaths.from(
            filesDir = modulesDir.takeIf { it.isNotBlank() }?.let(::File),
            registryDir = registry.takeIf { it.isNotBlank() }?.let(::File),
        )

        terminal.muted(
            "gradle user home : ${paths.gradleUserHome}",
        )
        terminal.muted(
            "modules dir      : ${paths.filesDir}",
        )
        terminal.muted(
            "registry         : ${paths.registryDir}",
        )

        val store = RegistryStore(paths.registryDir)
        val keep = store.union()
        val registered = store.all()

        if (registered.isNotEmpty()) {
            terminal.muted(
                "registered builds: ${registered.size} " +
                    "(${registered.joinToString(", ") { it.buildRoot }})",
            )
        }

        if (keep.isEmpty() && !all) {
            terminal.warning(
                "No builds are registered, so nothing can be pruned. " +
                    "Apply the `io.github.qie2035.gradle-prune` plugin to a project and build it, " +
                    "or pass --all to delete the entire module cache (dangerous).",
            )
            return
        }

        val lock = LockGuard(paths.modulesLock)
        if (!force && lock.check() == LockGuard.LockStatus.ACTIVE) {
            terminal.warning(
                "The module cache lock (modules-2.lock) was modified within the last " +
                    "${LockGuard.DEFAULT_FRESHNESS_MS / 1000}s — a Gradle build may be running. " +
                    "Proceeding anyway: deletion only touches the cache (a re-download, never a " +
                    "broken build). Pass --force to silence this warning.",
            )
        }

        if (!paths.filesDir.isDirectory) {
            terminal.warning("No module cache found at ${paths.filesDir} — nothing to do.")
            return
        }

        val present = CacheScanner.scan(paths.filesDir)
        val plan = PrunePlanner.plan(present, keep, allowEmptyKeepSet = all)
        val executor = PruneExecutor(paths.filesDir)
        val result = executor.execute(plan, dryRun = !apply)

        ReportRenderer.render(
            terminal = terminal,
            plan = plan,
            result = result,
            dryRun = !apply,
            verbose = verbose,
            keepSetEmpty = keep.isEmpty(),
        )

        if (!result.success) {
            // Signal failure to the caller (shell, gradle run, …).
            throw ProgramResult(1)
        }
        if (apply && plan.toDelete.isNotEmpty()) {
            terminal.success("Done.")
        }
    }
}

fun main(args: Array<String>) {
    PruneCommand().main(args)
}
