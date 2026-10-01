package io.github.qie2035.gradleprune.core.prune

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrunePlannerTest {

    private fun c(g: String, n: String, v: String) = ModuleCoordinate(g, n, v)

    @Test
    fun `keeps registered and deletes unregistered`() {
        val present = mapOf(
            c("a", "b", "1") to 100L,
            c("c", "d", "2") to 200L,
            c("e", "f", "3") to 300L,
        )
        val plan = PrunePlanner.plan(present, setOf(c("a", "b", "1")), deleteAll = false)
        assertEquals(setOf(c("a", "b", "1")), plan.keep.keys)
        assertEquals(setOf(c("c", "d", "2"), c("e", "f", "3")), plan.toDelete.keys)
        assertEquals(500L, plan.freedBytes)
        assertEquals(600L, plan.totalScannedBytes)
        assertEquals(100L, plan.keptBytes)
        assertEquals(false, plan.keepSetEmpty)
    }

    @Test
    fun `empty keep set refuses to delete by default`() {
        val present = mapOf(c("a", "b", "1") to 100L)
        val plan = PrunePlanner.plan(present, emptySet(), deleteAll = false)
        assertTrue(plan.toDelete.isEmpty())
        assertTrue(plan.keepSetEmpty)
        assertEquals(0L, plan.freedBytes)
        // Everything is "kept" (i.e. untouched) when we refuse.
        assertEquals(present, plan.keep)
    }

    @Test
    fun `empty keep set with all deletes everything`() {
        val present = mapOf(c("a", "b", "1") to 100L, c("x", "y", "9") to 5L)
        val plan = PrunePlanner.plan(present, emptySet(), deleteAll = true)
        assertEquals(present.keys, plan.toDelete.keys)
        assertEquals(105L, plan.freedBytes)
        assertTrue(plan.keepSetEmpty)
    }

    /**
     * Regression: `--all` used to be plumbed to "may the keep-set be empty",
     * so a *non-empty* registry made it behave like an ordinary prune. The
     * flag must ignore the registry entirely.
     */
    @Test
    fun `all ignores a non-empty keep set and deletes everything`() {
        val present = mapOf(c("a", "b", "1") to 100L, c("c", "d", "2") to 200L)
        val plan = PrunePlanner.plan(present, setOf(c("a", "b", "1")), deleteAll = true)
        assertEquals(present.keys, plan.toDelete.keys)
        assertTrue(plan.keep.isEmpty())
        assertEquals(300L, plan.freedBytes)
        assertEquals(false, plan.keepSetEmpty)
    }

    @Test
    fun `keep entries not present on disk are ignored`() {
        val present = mapOf(c("a", "b", "1") to 100L)
        val plan = PrunePlanner.plan(present, setOf(c("a", "b", "1"), c("zz", "missing", "0")), deleteAll = false)
        assertEquals(setOf(c("a", "b", "1")), plan.keep.keys)
        assertTrue(plan.toDelete.isEmpty())
    }
}
