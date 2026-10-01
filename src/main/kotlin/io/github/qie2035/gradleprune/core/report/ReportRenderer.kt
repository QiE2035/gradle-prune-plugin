package io.github.qie2035.gradleprune.core.report

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.info
import com.github.ajalt.mordant.terminal.muted
import com.github.ajalt.mordant.terminal.success
import com.github.ajalt.mordant.terminal.warning
import io.github.qie2035.gradleprune.core.prune.PrunePlan
import io.github.qie2035.gradleprune.core.prune.PruneResult

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

    /**
     * Prints the outcome of a prune run.
     *
     * @param plan the plan that was executed (or would be, for dry-run).
     * @param result the execution result (echoes the plan when dry-run).
     * @param dryRun whether this was a no-op preview.
     * @param verbose print every removed module instead of a truncated sample.
     * @param keepSetEmpty true when nothing was registered (kept as a refusal
     *   note rather than a success line).
     */
    fun render(
        terminal: Terminal,
        plan: PrunePlan,
        result: PruneResult,
        dryRun: Boolean,
        verbose: Boolean = false,
        keepSetEmpty: Boolean = false,
    ) {
        terminal.println()
        val mode = if (dryRun) " (dry-run: nothing deleted)" else ""
        terminal.info(
            "gradle-prune — scanned ${plan.present.size} cached module versions" + mode,
        )

        if (keepSetEmpty && plan.toDelete.isEmpty()) {
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
                terminal.println("  ${red(c.toString())}")
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

    /** Human-readable byte formatting (B/KB/MB/GB/TB/PB); see [formatBytes]. */
    fun formatBytes(bytes: Long): String = io.github.qie2035.gradleprune.core.formatBytes(bytes)
}
