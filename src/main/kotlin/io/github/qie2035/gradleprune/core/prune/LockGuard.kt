package io.github.qie2035.gradleprune.core.prune

import java.io.File

/**
 * Heuristic guard against pruning while a Gradle build is actively writing to
 * the shared cache.
 *
 * Gradle holds `modules-2.lock` (a `FileLockManager` file) with a fresh mtime
 * while a build is touching the module cache. We treat a very recent mtime as
 * "a build is probably running" and emit a warning. This is **non-blocking**
 * by design: deleting cache entries only costs a re-download, never breaks a
 * build, so we never refuse on this signal alone.
 *
 * Callers decide what to do with the [LockStatus] (typically: print a yellow
 * warning unless `--force` was passed).
 */
class LockGuard(
    private val lockFile: File,
    /** mtime newer than this (ms) is considered "active". */
    private val freshnessMs: Long = DEFAULT_FRESHNESS_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    enum class LockStatus(val active: Boolean) {
        /** Lock file missing or stale — safe to proceed. */
        INACTIVE(false),
        /** Lock file recently touched — a build may be in flight. */
        ACTIVE(true),
    }

    fun check(): LockStatus {
        if (!lockFile.isFile) return LockStatus.INACTIVE
        val age = now() - lockFile.lastModified()
        return if (age in 0..freshnessMs) LockStatus.ACTIVE else LockStatus.INACTIVE
    }

    companion object {
        /** 30 s default window for "the lock looks live". */
        val DEFAULT_FRESHNESS_MS: Long = 30_000L
    }
}
