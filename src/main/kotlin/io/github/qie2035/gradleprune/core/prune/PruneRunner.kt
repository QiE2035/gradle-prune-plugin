package io.github.qie2035.gradleprune.core.prune

import io.github.qie2035.gradleprune.core.CachePaths
import io.github.qie2035.gradleprune.core.registry.BuildRootProbe
import io.github.qie2035.gradleprune.core.registry.ClassifiedEntry
import io.github.qie2035.gradleprune.core.registry.RegistryStore
import io.github.qie2035.gradleprune.core.registry.RegistryUsage

/** Everything one prune run needs. */
data class PruneRequest(
    val paths: CachePaths,
    /** When true nothing is deleted — no cache content and no registry entry. */
    val dryRun: Boolean,
    /** Override the unreadable-registry refusal. */
    val force: Boolean,
    /** Ignore the registry and delete every cached module version. */
    val deleteAll: Boolean,
    /** Extra build roots to treat as live even when absent (see [BuildRootProbe]). */
    val keepRoots: List<String> = emptyList(),
)

/** Why a run declined to plan any deletion. */
enum class PruneRefusal {
    /** No live build pins anything — the registry is empty, or every build is gone. */
    NOTHING_REGISTERED,

    /** A registry file could not be parsed, so the keep-set cannot be trusted. */
    UNREADABLE_REGISTRY,

    /** There is no module cache at the configured location. */
    NO_CACHE,
}

/**
 * The outcome of one run, shaped so both the CLI and the Gradle task can report
 * it identically.
 *
 * [plan] and [result] are null exactly when [refusal] is set: housekeeping of
 * stale registry entries still happens on a real run (see [removedStaleEntries]),
 * but no cache content is touched when we refuse.
 */
data class PruneReport(
    val paths: CachePaths,
    val usage: RegistryUsage,
    val lockActive: Boolean,
    val removedStaleEntries: List<ClassifiedEntry>,
    val plan: PrunePlan?,
    val result: PruneResult?,
    val refusal: PruneRefusal?,
) {
    /** True when a plan was made (i.e. the run did not refuse). */
    val planned: Boolean get() = refusal == null

    /**
     * Bytes of cache content that becomes deletable **only** because stale
     * entries were dropped — i.e. what the user gains from the cleanup.
     */
    val bytesFreedByStale: Long
        get() = plan?.toDelete?.filterKeys { it in usage.freedByStale }?.values?.sum() ?: 0L
}

/**
 * The single prune pipeline, shared by the CLI and `gradlePruneModules`.
 *
 * They used to carry two copies of this logic, and the copies drifted (the
 * `--all` flag was wired to "may the keep-set be empty" in both, so it silently
 * behaved like an ordinary prune). Everything decision-shaped lives here now;
 * the front-ends only render.
 *
 * The keep-set is derived from **live** builds only:
 *
 *   keep   = coordinates referenced by an entry whose build root still exists
 *   delete = cache ∖ keep
 *
 * so removing a dead project's entry releases precisely the modules no
 * surviving build references, and nothing else.
 */
object PruneRunner {

    fun run(request: PruneRequest): PruneReport {
        val paths = request.paths
        val store = RegistryStore(paths.registryDir)
        val probe = BuildRootProbe.from(paths.registryDir, request.keepRoots)
        val usage = RegistryUsage.of(store.entries(), probe, store.unreadable())

        // A refusal means the run changes nothing at all — no cache content and
        // no registry entry. The stale entries are still reported, so the
        // operator can act on them deliberately (`--all` or `--forget`).
        fun refused(reason: PruneRefusal) = PruneReport(
            paths = paths,
            usage = usage,
            lockActive = false,
            removedStaleEntries = emptyList(),
            plan = null,
            result = null,
            refusal = reason,
        )

        // A registry file we cannot parse shrinks the keep-set, which is the
        // one failure this tool cannot undo.
        if (usage.unreadable.isNotEmpty() && !request.dryRun && !request.force) {
            return refused(PruneRefusal.UNREADABLE_REGISTRY)
        }

        if (usage.keep.isEmpty() && !request.deleteAll) {
            return refused(PruneRefusal.NOTHING_REGISTERED)
        }

        if (!paths.filesDir.isDirectory) {
            return refused(PruneRefusal.NO_CACHE)
        }

        val lockActive = LockGuard(paths.modulesLock).check() == LockGuard.LockStatus.ACTIVE

        val present = CacheScanner.scan(paths.filesDir)
        val plan = PrunePlanner.plan(present, usage.keep, deleteAll = request.deleteAll)
        val result = PruneExecutor(paths.filesDir).execute(plan, dryRun = request.dryRun)

        // A run that proceeds is also the run that curates the registry: a
        // build root that is verifiably gone stops pinning anything from here
        // on. Done last so the registry only changes once the cache work is
        // over.
        val removedStale = if (request.dryRun) emptyList() else removeStaleEntries(usage.stale)

        return PruneReport(
            paths = paths,
            usage = usage,
            lockActive = lockActive,
            removedStaleEntries = removedStale,
            plan = plan,
            result = result,
            refusal = null,
        )
    }

    /**
     * Deletes the registry files of entries whose build root is gone.
     *
     * The file that was actually read is the one removed, so an entry whose
     * name does not match the hash of its build root still goes away.
     */
    private fun removeStaleEntries(stale: List<ClassifiedEntry>): List<ClassifiedEntry> =
        stale.filter { it.entry.file.delete() }
}
