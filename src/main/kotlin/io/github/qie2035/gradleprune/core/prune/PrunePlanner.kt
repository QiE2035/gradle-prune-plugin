package io.github.qie2035.gradleprune.core.prune

import io.github.qie2035.gradleprune.core.ModuleCoordinate

/**
 * Pure planning for a prune run: given the module versions that physically
 * exist ([present]) and the keep-set (union of registered builds, [keep]),
 * decide which to delete and how much space that frees.
 *
 * Kept pure so the decision logic is trivially unit-testable without any
 * filesystem or Gradle involvement.
 */
object PrunePlanner {

    /**
     * Builds a plan.
     *
     * @param present `coordinate -> bytes` for every `g/n/v` on disk.
     * @param keep the keep-set (union of all registered builds).
     * @param deleteAll when true the keep-set is ignored entirely and every
     *   present module is deleted — the `--all` / `-Pprune.modules.all=true`
     *   escape hatch. When false (the default) an *empty* keep-set means
     *   "we have no idea what is used", so nothing is deleted and
     *   [PrunePlan.keepSetEmpty] is reported instead.
     */
    fun plan(
        present: Map<ModuleCoordinate, Long>,
        keep: Set<ModuleCoordinate>,
        deleteAll: Boolean,
    ): PrunePlan {
        val keepIsEmpty = keep.isEmpty()
        val effectiveKeep = when {
            // --all: ignore the registry completely, wipe the whole cache.
            deleteAll -> emptySet()
            // Nothing registered: refuse rather than guess, unless --all.
            keepIsEmpty -> present.keys
            else -> keep
        }

        val keepMap = present.filterKeys { it in effectiveKeep }
        val deleteMap = present.filterKeys { it !in effectiveKeep }

        return PrunePlan(
            present = present,
            keep = keepMap,
            toDelete = deleteMap,
            freedBytes = deleteMap.values.sum(),
            keepSetEmpty = keepIsEmpty,
        )
    }
}

/**
 * The plan for one prune run.
 */
data class PrunePlan(
    /** Modules present in the cache, with their on-disk size. */
    val present: Map<ModuleCoordinate, Long>,
    /** Modules kept because at least one registered build needs them. */
    val keep: Map<ModuleCoordinate, Long>,
    /** Modules to delete, with their on-disk size. */
    val toDelete: Map<ModuleCoordinate, Long>,
    /** Bytes that would be freed by executing [toDelete]. */
    val freedBytes: Long,
    /** True when the keep-set was empty (nothing registered). */
    val keepSetEmpty: Boolean,
) {
    val totalScannedBytes: Long get() = present.values.sum()
    val keptBytes: Long get() = keep.values.sum()
}
