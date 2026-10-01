package io.github.qie2035.gradleprune.core.prune

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PruneExecutorTest {

    private fun c(g: String, n: String, v: String) = ModuleCoordinate(g, n, v)

    /** Builds a fake `files-2.1` tree with the given coordinates. */
    private fun makeCache(root: File, vararg coords: ModuleCoordinate): Map<ModuleCoordinate, Long> {
        val sizes = LinkedHashMap<ModuleCoordinate, Long>()
        for (coord in coords) {
            val shaDir = File(File(File(File(root, coord.group), coord.name), coord.version), "deadbeef")
            shaDir.mkdirs()
            val file = File(shaDir, "${coord.name}-${coord.version}.jar")
            file.writeBytes(ByteArray(64) { 1.toByte() })
            sizes[coord] = 64L
        }
        return sizes
    }

    @Test
    fun `scanner reports existing module versions`() {
        val root = createTempDirectory("scan").toFile()
        val files = File(root, "files-2.1")
        val expected = makeCache(files, c("g1", "n1", "1.0"), c("g2", "n2", "2.0"), c("g1", "n3", "0.1"))
        val scanned = CacheScanner.scan(files)
        assertEquals(expected, scanned)
    }

    @Test
    fun `scanner returns empty map for missing dir`() {
        assertTrue(CacheScanner.scan(File("/does/not/exist/files-2.1")).isEmpty())
    }

    @Test
    fun `executor deletes planned modules and frees bytes`() {
        val root = createTempDirectory("exec").toFile()
        val files = File(root, "files-2.1")
        val present = makeCache(files, c("keep", "me", "1.0"), c("delete", "me", "2.0"))
        val plan = PrunePlanner.plan(present, setOf(c("keep", "me", "1.0")), deleteAll = false)

        val result = PruneExecutor(files).execute(plan, dryRun = false)
        assertTrue(result.success)
        assertEquals(64L, result.deletedBytes)
        assertEquals(listOf(c("delete", "me", "2.0")), result.deletedModules)

        // Deleted tree is gone; kept tree is intact.
        val deletedDir = File(File(File(files, "delete"), "me"), "2.0")
        val keptDir = File(File(File(files, "keep"), "me"), "1.0")
        assertFalse(deletedDir.exists())
        assertTrue(keptDir.isDirectory)

        // Parent `name` dir of the deleted module is pruned when empty.
        assertFalse(File(File(files, "delete"), "me").exists())
    }

    @Test
    fun `executor prunes empty group dir after last version is deleted`() {
        val root = createTempDirectory("exec2").toFile()
        val files = File(root, "files-2.1")
        val present = makeCache(files, c("lonely", "group", "1.0"))
        val plan = PrunePlanner.plan(present, emptySet(), deleteAll = true)
        PruneExecutor(files).execute(plan, dryRun = false)
        assertFalse(File(files, "lonely").exists())
    }

    @Test
    fun `dry run deletes nothing`() {
        val root = createTempDirectory("exec3").toFile()
        val files = File(root, "files-2.1")
        val present = makeCache(files, c("g", "n", "1"))
        val plan = PrunePlanner.plan(present, emptySet(), deleteAll = true)
        val result = PruneExecutor(files).execute(plan, dryRun = true)
        assertTrue(result.dryRun)
        assertEquals(0L, result.deletedBytes)
        assertTrue(result.deletedModules.isEmpty())
        assertTrue(File(File(File(files, "g"), "n"), "1").exists())
    }

    /**
     * A directory name the coordinate grammar rejects must be skipped rather
     * than aborting the whole scan (a `:` is legal in a Linux file name).
     */
    @Test
    fun `scanner skips malformed directory names instead of throwing`() {
        val root = createTempDirectory("scan-bad").toFile()
        val files = File(root, "files-2.1")
        val expected = makeCache(files, c("g", "n", "1.0"))
        File(File(File(files, "g"), "n"), "bad:version").mkdirs()

        assertEquals(expected, CacheScanner.scan(files))
    }

    /**
     * A symlinked version directory must be unlinked, never followed: the
     * linked target's contents belong to someone else.
     */
    @Test
    fun `executor unlinks a symlinked version dir without deleting its target`() {
        val root = createTempDirectory("exec-link").toFile()
        val files = File(root, "files-2.1")
        val outside = File(root, "outside").apply { mkdirs() }
        val precious = File(outside, "keep.txt").apply { writeText("keep me") }

        val nameDir = File(File(files, "g"), "n").apply { mkdirs() }
        val link = File(nameDir, "1.0")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            return // Filesystem without symlink support (e.g. Windows w/o privilege).
        }

        val plan = PrunePlanner.plan(
            CacheScanner.scan(files),
            emptySet(),
            deleteAll = true,
        )
        PruneExecutor(files).execute(plan, dryRun = false)

        assertFalse(link.exists())
        assertTrue(precious.isFile, "the symlink target's contents must survive")
        assertEquals("keep me", precious.readText())
    }
}
