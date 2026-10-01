package io.github.qie2035.gradleprune.core.registry

import java.io.File

/**
 * Whether a registered build root can still be observed on disk.
 *
 * This is the whole "is it still in use?" judgement, and it is deliberately
 * **not** time-based: `lastSeen` is never consulted. A build root either exists
 * (so the modules it registered are in use) or it verifiably does not.
 */
enum class RootStatus {
    /** Present, or explicitly pinned: the entry keeps pinning its modules. */
    LIVE,

    /** Verifiably gone: the entry is stale and pins nothing. */
    STALE,

    /**
     * Undecidable — the filesystem could not be inspected (an unreadable or
     * hung ancestor, e.g. a network mount). Treated like [LIVE] so that an
     * infrastructure problem can never be mistaken for "the project was
     * deleted".
     */
    UNKNOWN,
    ;

    /** Only [STALE] releases the modules an entry pins. */
    val pinsModules: Boolean get() = this != STALE
}

/**
 * Decides whether a registered build root still exists.
 *
 * The rule is: the recorded path must be an existing directory. Anything else
 * releases the entry, **except** when the answer cannot be trusted —
 *
 *  - the nearest existing ancestor is not readable (unmounted, hung or
 *    permission-denied mount) → [RootStatus.UNKNOWN], never [RootStatus.STALE];
 *  - the path is covered by a pin → [RootStatus.LIVE].
 *
 * Pins exist because a build root can be legitimately absent for a while: an
 * unmounted removable drive or an offline network share. Pins come from
 * [BuildRootProbe.from] (a `keep-roots.txt` next to the registry) and from the
 * caller's explicit list (`--keep-root` / `-Pprune.keepRoots`). A pinned path
 * covers itself and everything below it.
 *
 * Only [RootStatus.STALE] is acted on, and acting on it costs at most a
 * re-download: the project rebuilds, re-registers, and pulls what it needs.
 */
class BuildRootProbe private constructor(
    pins: List<String>,
    /** Seam for tests; the production check is "is an existing directory". */
    private val isPresent: (File) -> Boolean,
) {
    /** A probe with explicit [pins] over the real filesystem. */
    constructor(pins: List<String>) : this(pins, { it.isDirectory })

    private val pins: List<File> = pins.map(::normalize)

    fun status(buildRoot: String): RootStatus {
        val root = normalize(buildRoot)
        if (isPinned(root)) return RootStatus.LIVE
        if (isPresent(root)) return RootStatus.LIVE

        // Walk up to the nearest ancestor that exists. If that ancestor is not
        // readable we cannot tell "deleted" from "temporarily unavailable", so
        // we refuse to conclude anything.
        val visibleAncestor = generateSequence(root.parentFile) { it.parentFile }
            .firstOrNull { it.exists() }
            ?: return RootStatus.UNKNOWN
        return if (visibleAncestor.canRead()) RootStatus.STALE else RootStatus.UNKNOWN
    }

    /** True when [root] is covered by a pin (it or one of its ancestors). */
    fun isPinned(root: String): Boolean = isPinned(normalize(root))

    private fun isPinned(root: File): Boolean = pins.any { pin ->
        root == pin || root.path.startsWith(pin.path + File.separator)
    }

    companion object {
        /** Optional pins file, read from the registry directory. */
        const val KEEP_ROOTS_FILE = "keep-roots.txt"

        /** A probe with no pins, checking the real filesystem. */
        fun default(): BuildRootProbe = BuildRootProbe(emptyList())

        /**
         * A probe whose pins are the registry's `keep-roots.txt` (one path per
         * line, `#` starts a comment) plus [extraPins].
         */
        fun from(registryDir: File, extraPins: List<String> = emptyList()): BuildRootProbe =
            BuildRootProbe(readPins(registryDir) + extraPins)

        private fun readPins(registryDir: File): List<String> {
            val file = File(registryDir, KEEP_ROOTS_FILE)
            if (!file.isFile) return emptyList()
            return file.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
        }

        /** Lexical normalization only — symlinks are deliberately not resolved. */
        private fun normalize(path: String): File =
            File(path).absoluteFile.toPath().normalize().toFile()
    }
}
