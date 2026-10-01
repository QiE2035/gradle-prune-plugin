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
        val plan = PrunePlanner.plan(present, setOf(c("a", "b", "1")), allowEmptyKeepSet = false)
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
        val plan = PrunePlanner.plan(present, emptySet(), allowEmptyKeepSet = false)
        assertTrue(plan.toDelete.isEmpty())
        assertTrue(plan.keepSetEmpty)
        assertEquals(0L, plan.freedBytes)
        // Everything is "kept" (i.e. untouched) when we refuse.
        assertEquals(present, plan.keep)
    }

    @Test
    fun `empty keep set with all deletes everything`() {
        val present = mapOf(c("a", "b", "1") to 100L, c("x", "y", "9") to 5L)
        val plan = PrunePlanner.plan(present, emptySet(), allowEmptyKeepSet = true)
        assertEquals(present.keys, plan.toDelete.keys)
        assertEquals(105L, plan.freedBytes)
        assertTrue(plan.keepSetEmpty)
    }

    @Test
    fun `keep entries not present on disk are ignored`() {
        val present = mapOf(c("a", "b", "1") to 100L)
        val plan = PrunePlanner.plan(present, setOf(c("a", "b", "1"), c("zz", "missing", "0")), allowEmptyKeepSet = false)
        assertEquals(setOf(c("a", "b", "1")), plan.keep.keys)
        assertTrue(plan.toDelete.isEmpty())
    }
}
