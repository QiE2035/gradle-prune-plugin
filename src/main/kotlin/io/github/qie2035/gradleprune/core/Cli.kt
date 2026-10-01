package io.github.qie2035.gradleprune.core

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.success
import com.github.ajalt.mordant.terminal.warning
import io.github.qie2035.gradleprune.core.prune.PruneRefusal
import io.github.qie2035.gradleprune.core.prune.PruneRequest
import io.github.qie2035.gradleprune.core.prune.PruneRunner
import io.github.qie2035.gradleprune.core.registry.RegistryStore
import io.github.qie2035.gradleprune.core.report.ReportRenderer
import java.io.File

/**
 * The standalone prune CLI — the user-facing half of the tool, runnable via
 * `gradle run --args="…"` (application plugin) or the `installDist` launcher.
 *
 * Semantics (the Gradle equivalent of `pnpm store prune`):
 *
 *   in use = module coordinates referenced by a build whose root still exists
 *   delete = `modules-2/files-2.1/<g>/<n>/<v>` directories NOT in use
 *
 * A registered build whose root is gone is *not* in use: its entry is dropped
 * and its modules become deletable — unless another surviving build still
 * references them, which the union guarantees. There is no aging and no
 * timeout anywhere in this decision.
 *
 * Safety defaults:
 *
 *   - **dry-run by default** — nothing is deleted unless `--apply` is passed;
 *   - a keep-set that ends up **empty refuses** to delete anything unless
 *     `--all` (wipe everything) is passed;
 *   - **unreadable registry files** are reported, and block `--apply` unless
 *     `--force` is passed (a file we cannot parse may have referenced modules
 *     that are about to look unused);
 *   - a **fresh `modules-2.lock` mtime** triggers a non-blocking warning
 *     (silenced by `--force`);
 *   - a build root that cannot be inspected (unreadable or hung mount) is
 *     treated as *still in use*, never as gone; `--keep-root` pins roots that
 *     are expected to be absent for a while;
 *   - only the dependency cache is ever touched; `metadata-*`, `resources-*`,
 *     `gc.properties` and the lock file are never modified.
 */
class PruneCommand : CliktCommand(name = "gradle-prune") {

    override fun help(context: Context) =
        "Prune module versions the registered builds no longer use from the shared Gradle dependency cache (like `pnpm store prune`)."

    override fun helpEpilog(context: Context) =
        "Example: gradle-prune --apply   # delete modules no surviving build references"

    /** Delete for real. Off by default (dry-run). */
    private val apply by option("--apply", help = "Actually delete. Without this flag the run is a dry-run.")
        .flag()

    /** Silences the active-lock and unreadable-registry blocks. */
    private val force by option(
        "--force",
        help = "Proceed despite a recently-touched build lock or unreadable registry files.",
    ).flag()

    /** Delete everything, even when nothing is in use. */
    private val all by option(
        "--all",
        help = "Ignore the registry and delete EVERY cached module version (dangerous).",
    ).flag()

    private val verbose by option("--verbose", "-v", help = "List every module to be deleted.")
        .flag()

    /** Removes registry entries instead of pruning. */
    private val forget by option(
        "--forget",
        help = "Remove the registry entry for this build root and exit (repeatable).",
    ).multiple()

    /** Keeps entries for roots that are legitimately absent right now. */
    private val keepRoot by option(
        "--keep-root",
        help = "Treat this build root (and everything below it) as still in use even when the " +
            "directory is absent — an unmounted drive or offline network share. Repeatable; " +
            "roots can also be listed in <registry>/keep-roots.txt.",
    ).multiple()

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

        if (forget.isNotEmpty()) {
            forgetEntries(terminal, RegistryStore(paths.registryDir))
            return
        }

        val dryRun = !apply
        val report = PruneRunner.run(
            PruneRequest(
                paths = paths,
                dryRun = dryRun,
                force = force,
                deleteAll = all,
                keepRoots = keepRoot,
            ),
        )
        ReportRenderer.render(terminal, report, dryRun = dryRun, verbose = verbose)

        val result = report.result
        if (result != null && !result.success) {
            // Signal failure to the caller (shell, gradle run, …).
            throw ProgramResult(1)
        }
        if (report.refusal == PruneRefusal.UNREADABLE_REGISTRY) {
            // The user asked for a real deletion and we declined to make it.
            throw ProgramResult(1)
        }
        if (apply && report.plan?.toDelete?.isNotEmpty() == true) {
            terminal.success("Done.")
        }
    }

    /**
     * Handles `--forget <buildRoot>`: drops the registry entries for the given
     * build roots (the "the project is gone, and I want it forgotten now"
     * case) instead of pruning.
     *
     * The path is matched against the *stored* build root, so it is tolerant
     * of a trailing slash or a relative path — it is not a raw file-name
     * lookup, which would silently do nothing for any path spelling that
     * differs from the one the build registered. The file that was actually
     * read is the one deleted, so a renamed entry still goes away.
     */
    private fun forgetEntries(terminal: Terminal, store: RegistryStore) {
        val entries = store.entries()
        var failed = false
        for (raw in forget) {
            val target = File(raw).absolutePath
            val match = entries.firstOrNull {
                it.registry.buildRoot == target || it.registry.buildRoot == raw
            }
            when {
                match == null -> {
                    terminal.warning("No registry entry for $raw — nothing removed.")
                    failed = true
                }
                match.file.delete() -> terminal.success("Forgot ${match.registry.buildRoot}.")
                else -> {
                    terminal.warning("Could not remove ${match.file.path}.")
                    failed = true
                }
            }
        }
        if (failed) {
            throw ProgramResult(1)
        }
    }
}

fun main(args: Array<String>) {
    PruneCommand().main(args)
}
