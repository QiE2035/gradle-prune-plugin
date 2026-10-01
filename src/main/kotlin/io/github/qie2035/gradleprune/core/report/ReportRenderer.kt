package io.github.qie2035.gradleprune.core.report

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.info
import com.github.ajalt.mordant.terminal.muted
import com.github.ajalt.mordant.terminal.success
import com.github.ajalt.mordant.terminal.warning
import io.github.qie2035.gradleprune.core.formatBytes
import io.github.qie2035.gradleprune.core.formatTimestamp
import io.github.qie2035.gradleprune.core.prune.PruneRefusal
import io.github.qie2035.gradleprune.core.prune.PruneReport
import io.github.qie2035.gradleprune.core.registry.ClassifiedEntry

/**
 * Renders a pnpm-style colored prune report to the terminal via Mordant.
 *
 * Mordant auto-detects whether stdout is a TTY and strips ANSI codes when it
 * is not (CI-safe), so no special handling is needed.
 *
 * Styling uses Mordant's [com.github.ajalt.mordant.rendering.TextColors] enum
 * entries (each is a `TextStyle` with an `invoke(String)` operator) and the
 * theme's `info`/`warning`/`success`/`muted` `Terminal` extensions.
 */
object ReportRenderer {

    /** Cap on how many stale build roots are listed individually. */
    private const val MAX_LISTED_ROOTS = 20

    /**
     * Prints the outcome of a prune run.
     *
     * @param report the run's decision, including which registered builds are
     *   still in use (see `RegistryUsage`) and why it refused, if it did.
     * @param dryRun whether this was a no-op preview.
     * @param verbose print every module instead of a truncated sample.
     */
    fun render(
        terminal: Terminal,
        report: PruneReport,
        dryRun: Boolean,
        verbose: Boolean = false,
    ) {
        val mode = if (dryRun) " (dry-run: nothing deleted)" else ""
        terminal.println()
        terminal.muted("gradle user home : ${report.paths.gradleUserHome}")
        terminal.muted("modules dir      : ${report.paths.filesDir}")
        terminal.muted("registry         : ${report.paths.registryDir}")
        terminal.println()

        val plan = report.plan
        terminal.info(
            if (plan == null) "gradle-prune$mode"
            else "gradle-prune — scanned ${plan.present.size} cached module versions$mode",
        )

        renderRegistry(terminal, report, dryRun)

        if (report.lockActive) {
            terminal.warning(
                "The module cache lock (modules-2.lock) was modified recently — a Gradle build " +
                    "may be running. Proceeding anyway: deletion only touches the cache " +
                    "(a re-download, never a broken build). Pass --force to silence this warning.",
            )
        }

        when (report.refusal) {
            PruneRefusal.UNREADABLE_REGISTRY -> terminal.warning(
                "Refusing to delete while unreadable registry files are present. " +
                    "Fix or remove them, or pass --force to override.",
            )

            PruneRefusal.NOTHING_REGISTERED -> terminal.warning(
                if (report.usage.classified.isEmpty()) {
                    "No builds are registered, so nothing can be pruned. " +
                        "Apply the `io.github.qie2035.gradle-prune` plugin to a project and " +
                        "build it, or pass --all to delete the entire module cache (dangerous)."
                } else {
                    "Every registered build is gone, so nothing is still in use and nothing was " +
                        "pruned. Pass --all to delete the entire module cache — that also clears " +
                        "the ${report.usage.stale.size} stale registry " +
                        "${noun(report.usage.stale.size, "entry", "entries")} — or pin a temporarily " +
                        "absent root with --keep-root / keep-roots.txt."
                },
            )

            PruneRefusal.NO_CACHE -> terminal.warning(
                "No module cache found at ${report.paths.filesDir} — nothing to do.",
            )

            null -> renderPlan(terminal, report, dryRun, verbose)
        }
    }

    /**
     * Reports what the registry says is still in use: how many builds are live,
     * which ones are gone, and what dropping them releases.
     */
    private fun renderRegistry(terminal: Terminal, report: PruneReport, dryRun: Boolean) {
        val usage = report.usage
        val unknown = usage.unknown
        val stale = usage.stale

        if (usage.classified.isNotEmpty()) {
            terminal.muted(
                "registered builds: ${usage.classified.size} — ${usage.live.size} in use" +
                    (if (unknown.isNotEmpty()) ", ${unknown.size} unverifiable" else "") +
                    (if (stale.isNotEmpty()) ", ${stale.size} gone" else ""),
            )
        }

        listEntries(terminal, "gone (build root no longer exists)", stale)
        listEntries(terminal, "unverifiable (kept)", unknown)

        if (stale.isNotEmpty()) {
            val freed = report.plan?.toDelete?.keys?.count { it in usage.freedByStale } ?: 0
            val bytes = report.bytesFreedByStale
            if (dryRun) {
                terminal.warning(
                    "Would drop ${stale.size} stale registry " +
                        "${noun(stale.size, "entry", "entries")} and free ${formatBytes(bytes)} " +
                        "across $freed module version(s) that no surviving build references.",
                )
            }
        }

        if (report.removedStaleEntries.isNotEmpty()) {
            val n = report.removedStaleEntries.size
            terminal.muted(
                "Dropped $n stale registry ${noun(n, "entry", "entries")}: " +
                    report.removedStaleEntries.joinToString(", ") { it.buildRoot },
            )
        }

        if (usage.unreadable.isNotEmpty()) {
            terminal.warning(
                "${usage.unreadable.size} registry file(s) could not be parsed and were " +
                    "ignored, so anything only they referenced looks unused: " +
                    usage.unreadable.joinToString(", ") { it.name },
            )
        }
    }

    private fun listEntries(terminal: Terminal, label: String, entries: List<ClassifiedEntry>) {
        if (entries.isEmpty()) return
        terminal.muted("  $label:")
        entries.take(MAX_LISTED_ROOTS).forEach { entry ->
            terminal.muted(
                "    ${entry.buildRoot}  (last built ${formatTimestamp(entry.lastSeen)}, " +
                    "${entry.coordinates.size} module version(s))",
            )
        }
        if (entries.size > MAX_LISTED_ROOTS) {
            terminal.muted("    … and ${entries.size - MAX_LISTED_ROOTS} more")
        }
    }

    private fun renderPlan(
        terminal: Terminal,
        report: PruneReport,
        dryRun: Boolean,
        verbose: Boolean,
    ) {
        val plan = report.plan ?: return
        val result = report.result ?: return

        if (plan.keepSetEmpty && plan.toDelete.isEmpty()) {
            terminal.warning(
                "No builds are registered yet, so nothing was pruned. " +
                    "Apply the plugin to a project and build it once, or pass " +
                    "--all to delete the entire cache (dangerous).",
            )
        }

        val summary = if (dryRun) {
            "would free ${green(formatBytes(plan.freedBytes))} across " +
                "${plan.toDelete.size} module version(s)"
        } else {
            "kept ${plan.keep.size} · freed ${green(formatBytes(result.deletedBytes))} " +
                "across ${result.deletedModules.size} module version(s)"
        }
        terminal.success(summary)

        if (plan.toDelete.isNotEmpty()) {
            terminal.println()
            val sample = if (verbose) plan.toDelete.keys.toList()
            else plan.toDelete.keys.sortedBy { it.toString() }.take(20)
            terminal.muted(
                if (verbose) "Removed module versions:"
                else "Sample of removed module versions (pass --verbose for the full list):",
            )
            for (c in sample) {
                val staleOnly = if (c in report.usage.freedByStale) "  ${gray("(only a gone build used it)")}" else ""
                terminal.println("  ${red(c.toString())}$staleOnly")
            }
            if (!verbose && plan.toDelete.size > sample.size) {
                terminal.muted("  … and ${plan.toDelete.size - sample.size} more")
            }
        }

        if (result.failed.isNotEmpty()) {
            terminal.warning("Some deletions failed (will be retried on the next run):")
            for (f in result.failed) {
                terminal.println("  ${yellow(f)}")
            }
        }
    }

    private fun noun(count: Int, singular: String, plural: String): String =
        if (count == 1) singular else plural
}
