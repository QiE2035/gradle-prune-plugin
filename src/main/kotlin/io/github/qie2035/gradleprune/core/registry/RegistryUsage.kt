package io.github.qie2035.gradleprune.core.registry

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import java.io.File

/** A registry entry together with the verdict about its build root. */
data class ClassifiedEntry(
    val entry: RegistryEntry,
    val status: RootStatus,
) {
    val buildRoot: String get() = entry.registry.buildRoot

    /** Informational only — never a pruning input. */
    val lastSeen: Long get() = entry.registry.lastSeen

    val coordinates: Set<ModuleCoordinate> get() = entry.registry.coordinates
}

/**
 * The explicit "what is still in use?" view of the registry.
 *
 * A coordinate is in use iff at least one entry whose build root is still
 * present references it. There is no aging and no heuristic beyond
 * [BuildRootProbe]: the answer depends only on which projects exist, never on
 * when they last ran.
 *
 * That is also what makes dropping a stale entry safe: the keep-set is the
 * union over the entries that remain, so a module a *live* build still
 * references can never be released by removing a dead one.
 */
data class RegistryUsage(
    val classified: List<ClassifiedEntry>,
    /** Files that could not be parsed; they pin nothing and are reported. */
    val unreadable: List<File>,
) {
    /** Entries that keep pinning their modules ([RootStatus.LIVE] + [RootStatus.UNKNOWN]). */
    val live: List<ClassifiedEntry> get() = classified.filter { it.status.pinsModules }

    /** Entries whose build root is verifiably gone. */
    val stale: List<ClassifiedEntry> get() = classified.filter { it.status == RootStatus.STALE }

    /** Entries that could not be judged; kept, and worth telling the user about. */
    val unknown: List<ClassifiedEntry> get() = classified.filter { it.status == RootStatus.UNKNOWN }

    /** The keep-set: coordinates referenced by at least one live entry. */
    val keep: Set<ModuleCoordinate> =
        classified.asSequence()
            .filter { it.status.pinsModules }
            .flatMap { it.coordinates.asSequence() }
            .toCollection(LinkedHashSet())

    /**
     * Coordinates referenced **only** by stale entries — exactly what becomes
     * deletable once those entries go away. Anything still referenced by a
     * live build is subtracted, so dropping an entry can never release a
     * module another project is using.
     */
    val freedByStale: Set<ModuleCoordinate> =
        classified.asSequence()
            .filter { it.status == RootStatus.STALE }
            .flatMap { it.coordinates.asSequence() }
            .filterNot { it in keep }
            .toCollection(LinkedHashSet())

    companion object {
        /** Usage for already-classified entries. */
        fun of(classified: List<ClassifiedEntry>, unreadable: List<File> = emptyList()): RegistryUsage =
            RegistryUsage(classified = classified, unreadable = unreadable)

        /** Classifies [entries] against [probe] and computes their usage. */
        fun of(
            entries: List<RegistryEntry>,
            probe: BuildRootProbe,
            unreadable: List<File> = emptyList(),
        ): RegistryUsage = of(classify(entries, probe), unreadable)

        /** Verdict for each entry's build root. */
        fun classify(entries: List<RegistryEntry>, probe: BuildRootProbe): List<ClassifiedEntry> =
            entries.map { ClassifiedEntry(it, probe.status(it.registry.buildRoot)) }
    }
}
