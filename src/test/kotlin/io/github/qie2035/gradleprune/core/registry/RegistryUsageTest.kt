package io.github.qie2035.gradleprune.core.registry

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The registry's "what is still in use?" view, with the filesystem stubbed out
 * through pre-classified entries.
 */
class RegistryUsageTest {

    private fun classified(root: String, status: RootStatus, vararg modules: String) =
        ClassifiedEntry(
            entry = RegistryEntry(
                file = File("/reg/$root.json"),
                registry = ModuleRegistry(buildRoot = root, lastSeen = 1L, modules = modules.toSet()),
            ),
            status = status,
        )

    private fun coord(s: String) = ModuleCoordinate.parse(s)

    @Test
    fun `keep is the union of live builds only`() {
        val usage = RegistryUsage.of(
            listOf(
                classified("/live", RootStatus.LIVE, "g1:n1:1.0"),
                classified("/gone", RootStatus.STALE, "g2:n2:2.0"),
            ),
        )
        assertEquals(setOf(coord("g1:n1:1.0")), usage.keep)
        assertEquals(setOf(coord("g2:n2:2.0")), usage.freedByStale)
        assertEquals(1, usage.live.size)
        assertEquals(1, usage.stale.size)
    }

    /** The "do not mis-delete" guarantee, as a test. */
    @Test
    fun `a module a live build still uses is never released by a stale one`() {
        val usage = RegistryUsage.of(
            listOf(
                classified("/live", RootStatus.LIVE, "shared:lib:9.9"),
                classified("/gone", RootStatus.STALE, "shared:lib:9.9", "g2:n2:2.0"),
            ),
        )
        assertTrue(coord("shared:lib:9.9") in usage.keep)
        assertTrue(coord("shared:lib:9.9") !in usage.freedByStale)
        assertEquals(setOf(coord("g2:n2:2.0")), usage.freedByStale)
    }

    @Test
    fun `unverifiable builds keep pinning their modules`() {
        val usage = RegistryUsage.of(listOf(classified("/maybe", RootStatus.UNKNOWN, "g1:n1:1.0")))
        assertEquals(setOf(coord("g1:n1:1.0")), usage.keep)
        assertTrue(usage.freedByStale.isEmpty())
        assertEquals(1, usage.unknown.size)
        assertEquals(1, usage.live.size)
        assertTrue(usage.stale.isEmpty())
    }

    @Test
    fun `an empty registry keeps nothing`() {
        val usage = RegistryUsage.of(emptyList())
        assertTrue(usage.keep.isEmpty())
        assertTrue(usage.freedByStale.isEmpty())
        assertTrue(usage.live.isEmpty())
    }

    @Test
    fun `every entry stale releases everything they referenced`() {
        val usage = RegistryUsage.of(
            listOf(
                classified("/gone-a", RootStatus.STALE, "g1:n1:1.0"),
                classified("/gone-b", RootStatus.STALE, "g2:n2:2.0"),
            ),
        )
        assertTrue(usage.keep.isEmpty())
        assertEquals(setOf(coord("g1:n1:1.0"), coord("g2:n2:2.0")), usage.freedByStale)
    }
}
