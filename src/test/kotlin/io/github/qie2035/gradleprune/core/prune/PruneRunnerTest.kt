package io.github.qie2035.gradleprune.core.prune

import io.github.qie2035.gradleprune.core.CachePaths
import io.github.qie2035.gradleprune.core.ModuleCoordinate
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole core pipeline against a real filesystem: a live build root, a
 * deleted one, and a shared module. This is the guarantee the feature exists
 * for — dropping a dead build's entry frees exactly what nothing else uses.
 */
class PruneRunnerTest {

    private lateinit var home: File
    private lateinit var paths: CachePaths

    private fun setup(): File {
        home = createTempDirectory("runner").toFile()
        paths = CachePaths.from(gradleUserHome = home)
        return home
    }

    private fun liveRoot(name: String): File = File(home, name).apply { mkdirs() }

    /** A build root that is absent below the (existing, readable) temp home. */
    private fun goneRoot(name: String): File = File(home, name)

    private fun cache(g: String, n: String, v: String) {
        val sha = File(moduleDir(g, n, v), "deadbeef").apply { mkdirs() }
        File(sha, "$n-$v.jar").writeBytes(ByteArray(64) { 1 })
    }

    private fun moduleDir(g: String, n: String, v: String) = File(File(File(paths.filesDir, g), n), v)

    private fun registryEntry(buildRoot: String, vararg modules: String): File {
        paths.registryDir.mkdirs()
        val file = File(paths.registryDir, "${sha1(buildRoot).take(12)}.json")
        file.writeText(
            """{"buildRoot":"$buildRoot","lastSeen":1,"modules":[${modules.joinToString(",") { "\"$it\"" }}]}""",
        )
        return file
    }

    private fun run(dryRun: Boolean, vararg keepRoots: String): PruneReport =
        PruneRunner.run(
            PruneRequest(
                paths = paths,
                dryRun = dryRun,
                force = false,
                deleteAll = false,
                keepRoots = keepRoots.toList(),
            ),
        )

    @Test
    fun `a deleted build's entry is dropped and only its exclusive modules are freed`() {
        setup()
        val live = liveRoot("live-project")
        val gone = goneRoot("deleted-project")

        registryEntry(live.path, "shared:lib:9.9", "live:only:1.0")
        val goneFile = registryEntry(gone.path, "shared:lib:9.9", "dead:only:2.0")

        cache("shared", "lib", "9.9")
        cache("live", "only", "1.0")
        cache("dead", "only", "2.0")

        val report = run(dryRun = false)

        assertNull(report.refusal)
        assertEquals(1, report.usage.stale.size)
        assertEquals(1, report.usage.live.size)
        assertEquals(listOf(gone.path), report.removedStaleEntries.map { it.buildRoot })
        assertFalse(goneFile.exists(), "the dead build's registry entry must be removed")

        assertEquals(setOf(ModuleCoordinate("dead", "only", "2.0")), report.usage.freedByStale)
        assertFalse(moduleDir("dead", "only", "2.0").exists(), "only the dead build used this")
        assertTrue(moduleDir("live", "only", "1.0").isDirectory, "the live build still uses this")
        assertTrue(
            moduleDir("shared", "lib", "9.9").isDirectory,
            "a module a surviving build still references must never be released",
        )
    }

    @Test
    fun `a dry run neither deletes nor drops entries`() {
        setup()
        val live = liveRoot("live-project")
        val gone = goneRoot("deleted-project")

        registryEntry(live.path, "live:only:1.0")
        val goneFile = registryEntry(gone.path, "dead:only:2.0")
        cache("live", "only", "1.0")
        cache("dead", "only", "2.0")

        val report = run(dryRun = true)

        assertNull(report.refusal)
        assertEquals(1, report.usage.stale.size, "the dry run must still report what it would do")
        assertTrue(report.removedStaleEntries.isEmpty())
        assertTrue(goneFile.exists(), "a dry run must not touch the registry")
        assertTrue(moduleDir("dead", "only", "2.0").isDirectory, "a dry run must not delete anything")
        assertEquals(1, report.plan?.toDelete?.size, "but it must plan the deletion")
    }

    /** The safety valve: a root that is absent on purpose keeps its modules. */
    @Test
    fun `a pinned absent root is still in use`() {
        setup()
        val gone = goneRoot("unmounted-drive")
        val entryFile = registryEntry(gone.path, "keep:me:1.0")
        cache("keep", "me", "1.0")

        val report = run(dryRun = false, gone.path)

        assertNull(report.refusal)
        assertTrue(report.usage.stale.isEmpty(), "a pinned root is never stale")
        assertTrue(entryFile.exists())
        assertTrue(moduleDir("keep", "me", "1.0").isDirectory)
    }

    @Test
    fun `when every build is gone nothing is deleted and no entry is dropped`() {
        setup()
        val goneA = goneRoot("gone-a")
        val goneB = goneRoot("gone-b")
        val fileA = registryEntry(goneA.path, "a:a:1.0")
        val fileB = registryEntry(goneB.path, "b:b:1.0")
        cache("a", "a", "1.0")
        cache("b", "b", "1.0")

        val report = run(dryRun = false)

        assertEquals(PruneRefusal.NOTHING_REGISTERED, report.refusal)
        assertEquals(2, report.usage.stale.size, "the run must say why nothing is in use")
        assertTrue(moduleDir("a", "a", "1.0").isDirectory)
        assertTrue(moduleDir("b", "b", "1.0").isDirectory)
        assertTrue(fileA.exists() && fileB.exists(), "a refusal must not drop entries")
    }

    private fun sha1(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
