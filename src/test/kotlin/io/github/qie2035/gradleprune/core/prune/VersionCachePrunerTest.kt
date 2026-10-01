package io.github.qie2035.gradleprune.core.prune

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionCachePrunerTest {

    private fun name(dir: File) = dir.name

    private fun cacheDirs(vararg names: String): Pair<File, List<File>> {
        val root = createTempDirectory("caches").toFile()
        val dirs = names.map { File(root, it).apply { mkdirs() } }
        return root to dirs
    }

    @Test
    fun `picks version-shaped dirs and skips tooling dirs`() {
        val (root, _) = cacheDirs(
            "9.7.1",        // current → kept (excluded)
            "8.5",          // stale version → candidate
            "7.6.4",        // stale version → candidate
            "fabric-loom",  // tooling → skipped
            "neoformruntime",
            "jars-9",       // tooling → skipped
            "metadata-2.107",
            "resources-2.1",
            "modules-2",    // tooling → skipped
            "gc.properties",
        )
        val candidates = VersionCachePruner.candidates(root, "9.7.1")
        assertEquals(setOf("8.5", "7.6.4"), candidates.map(::name).toSet())
        assertTrue(candidates.none { it.name == "9.7.1" })
        assertTrue(candidates.none { it.name == "jars-9" })
        assertTrue(candidates.none { it.name == "modules-2" })
    }

    @Test
    fun `candidate list is sorted by name`() {
        val (root, _) = cacheDirs("9.9", "8.1.2", "7.0.11", "10.0")
        val names = VersionCachePruner.candidates(root, "9.7.1").map(::name)
        assertEquals(listOf("10.0", "7.0.11", "8.1.2", "9.9"), names)
    }

    @Test
    fun `prerelease version dirs are candidates`() {
        val (root, _) = cacheDirs("9.7.1", "9.7-rc-1")
        assertEquals(setOf("9.7-rc-1"), VersionCachePruner.candidates(root, "9.7.1").map(::name).toSet())
    }

    @Test
    fun `non-directory entries are ignored`() {
        val root = createTempDirectory("caches2").toFile()
        File(root, "modules-2.lock").writeText("")
        File(root, "9.0").writeText("not a dir") // version-shaped but a file
        assertTrue(VersionCachePruner.candidates(root, "9.7.1").isEmpty())
    }

    @Test
    fun `dirSize reports bytes of a small tree`() {
        val root = createTempDirectory("size").toFile()
        File(root, "a.bin").writeBytes(ByteArray(100) { 1 })
        File(root, "sub").mkdirs()
        File(File(root, "sub"), "b.bin").writeBytes(ByteArray(50) { 1 })
        val size = VersionCachePruner.dirSize(root)
        assertTrue(size >= 150L)
    }

    @Test
    fun `dirSize of missing dir is zero`() {
        assertEquals(0L, VersionCachePruner.dirSize(File("/nope/nothing")))
    }
}
