package io.github.qie2035.gradleprune.core.registry

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryStoreTest {

    private lateinit var dir: File

    private fun store(): RegistryStore {
        dir = createTempDirectory("registry").toFile()
        return RegistryStore(File(dir, "registry"))
    }

    private fun c(g: String, n: String, v: String) = ModuleCoordinate(g, n, v)

    @Test
    fun `upsert then read round trips`() {
        val s = store()
        s.upsert(
            buildRoot = "/proj/a",
            now = 1000L,
            gradleVersion = "9.7.1",
            modules = setOf(c("g1", "n1", "1.0"), c("g2", "n2", "2.0")),
        )
        val r = s.read("/proj/a")!!
        assertEquals("/proj/a", r.buildRoot)
        assertEquals(1000L, r.lastSeen)
        assertEquals("9.7.1", r.gradleVersion)
        assertEquals(setOf("g1:n1:1.0", "g2:n2:2.0"), r.modules)
    }

    @Test
    fun `upsert merges with existing rather than losing modules`() {
        val s = store()
        s.upsert("/proj/a", now = 1000, modules = setOf(c("g1", "n1", "1.0")))
        // Simulate a later partial build that only resolved one of the modules.
        s.upsert("/proj/a", now = 2000, modules = setOf(c("g2", "n2", "2.0")))
        val r = s.read("/proj/a")!!
        assertEquals(2000L, r.lastSeen)
        assertEquals(setOf("g1:n1:1.0", "g2:n2:2.0"), r.modules)
    }

    @Test
    fun `read returns null for missing root`() {
        assertNull(store().read("/nope"))
    }

    @Test
    fun `union across builds`() {
        val s = store()
        s.upsert("/proj/a", modules = setOf(c("g1", "n1", "1.0")))
        s.upsert("/proj/b", modules = setOf(c("g1", "n1", "1.0"), c("g3", "n3", "3.0")))
        val u = s.union()
        assertEquals(setOf(c("g1", "n1", "1.0"), c("g3", "n3", "3.0")), u)
        assertEquals(2, s.all().size)
    }

    @Test
    fun `union is empty when nothing registered`() {
        assertTrue(store().union().isEmpty())
    }

    @Test
    fun `remove deletes the file for that root only`() {
        val s = store()
        s.upsert("/proj/a", modules = setOf(c("g1", "n1", "1.0")))
        s.upsert("/proj/b", modules = setOf(c("g1", "n1", "1.0")))
        assertTrue(s.remove("/proj/a"))
        assertFalse(s.remove("/proj/a"))
        assertEquals(setOf(c("g1", "n1", "1.0")), s.union())
        assertEquals(1, s.all().size)
    }

    @Test
    fun `different build roots get different files`() {
        val s = store()
        val fa = s.fileFor("/proj/a")
        val fb = s.fileFor("/proj/b")
        assertTrue(fa.name != fb.name)
        assertTrue(fa.name.endsWith(".json"))
        assertEquals(12, fa.name.removeSuffix(".json").length)
    }

    /**
     * `entries()` hands back the file each entry was read from, so `--forget`
     * can delete it even when its name does not match the build root's hash.
     */
    @Test
    fun `entries reports the file behind each entry`() {
        val s = store()
        s.upsert("/proj/a", modules = setOf(c("g1", "n1", "1.0")))
        s.upsert("/proj/b", modules = setOf(c("g2", "n2", "2.0")))

        val entries = s.entries().sortedBy { it.registry.buildRoot }
        assertEquals(listOf("/proj/a", "/proj/b"), entries.map { it.registry.buildRoot })
        assertTrue(entries.all { it.file.isFile })
        assertEquals(s.fileFor("/proj/a"), entries[0].file)
    }

    @Test
    fun `corrupt file is ignored by all and union`() {
        val s = store()
        s.upsert("/proj/a", modules = setOf(c("g1", "n1", "1.0")))
        File(s.registryDir, s.fileFor("/proj/a").name).writeText("not json {")
        assertTrue(s.all().isEmpty())
        assertTrue(s.union().isEmpty())
    }

    @Test
    fun `corrupt files are reported by unreadable so callers can refuse`() {
        val s = store()
        s.upsert("/proj/a", modules = setOf(c("g1", "n1", "1.0")))
        assertEquals(emptyList(), s.unreadable())

        val corrupt = File(s.registryDir, s.fileFor("/proj/b").name)
        corrupt.writeText("not json {")
        assertEquals(listOf(corrupt.name), s.unreadable().map { it.name })
        // The good entry is still usable; only the bad one is flagged.
        assertEquals(1, s.all().size)
    }

    /**
     * Overwriting an unparseable entry would silently drop every coordinate it
     * held, so `upsert` must refuse rather than lose them.
     */
    @Test
    fun `upsert refuses to overwrite an unreadable entry`() {
        val s = store()
        s.upsert("/proj/a", modules = setOf(c("g1", "n1", "1.0")))
        val file = File(s.registryDir, s.fileFor("/proj/a").name)
        file.writeText("not json {")

        val failure = assertFailsWith<IllegalStateException> {
            s.upsert("/proj/a", modules = setOf(c("g2", "n2", "2.0")))
        }
        assertTrue(failure.message!!.contains("cannot be parsed"))
        // The original bytes are untouched, so the damage stays detectable.
        assertEquals("not json {", file.readText())
    }
}
