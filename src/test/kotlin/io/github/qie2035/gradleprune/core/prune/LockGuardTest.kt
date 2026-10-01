package io.github.qie2035.gradleprune.core.prune

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class LockGuardTest {

    private fun freshLock(now: Long, ageMs: Long): File {
        val dir = createTempDirectory("lock").toFile()
        val f = File(dir, "modules-2.lock")
        f.writeText("")
        f.setLastModified(now - ageMs)
        return f
    }

    @Test
    fun `fresh lock is ACTIVE`() {
        val now = 1_000_000L
        val lock = LockGuard(freshLock(now, 5_000), freshnessMs = 30_000, now = { now })
        assertEquals(LockGuard.LockStatus.ACTIVE, lock.check())
    }

    @Test
    fun `stale lock is INACTIVE`() {
        val now = 1_000_000L
        val lock = LockGuard(freshLock(now, 61_000), freshnessMs = 30_000, now = { now })
        assertEquals(LockGuard.LockStatus.INACTIVE, lock.check())
    }

    @Test
    fun `boundary at exactly freshness is ACTIVE`() {
        val now = 1_000_000L
        val lock = LockGuard(freshLock(now, 30_000), freshnessMs = 30_000, now = { now })
        assertEquals(LockGuard.LockStatus.ACTIVE, lock.check())
    }

    @Test
    fun `missing lock is INACTIVE`() {
        val now = 1_000_000L
        val lock = LockGuard(File("/nope/modules-2.lock"), now = { now })
        assertEquals(LockGuard.LockStatus.INACTIVE, lock.check())
    }
}
