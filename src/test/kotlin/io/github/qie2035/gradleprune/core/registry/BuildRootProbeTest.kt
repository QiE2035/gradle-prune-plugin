package io.github.qie2035.gradleprune.core.registry

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the explicit "is this build still there?" judgement. Everything
 * here runs against the real filesystem (temp dirs), so the readability guard
 * is exercised for real rather than mocked away.
 */
class BuildRootProbeTest {

    private fun tempDir(prefix: String = "probe"): File = createTempDirectory(prefix).toFile()

    @Test
    fun `an existing directory is live`() {
        val dir = tempDir()
        assertEquals(RootStatus.LIVE, BuildRootProbe.default().status(dir.path))
    }

    @Test
    fun `a missing directory below a readable parent is stale`() {
        val parent = tempDir()
        val gone = File(parent, "deleted-project")
        assertEquals(RootStatus.STALE, BuildRootProbe.default().status(gone.path))
    }

    @Test
    fun `a path that exists but is not a directory is stale`() {
        val parent = tempDir()
        val notADir = File(parent, "project").apply { writeText("not a project") }
        assertEquals(RootStatus.STALE, BuildRootProbe.default().status(notADir.path))
    }

    @Test
    fun `a nested missing path is stale, not unknown`() {
        val parent = tempDir()
        assertEquals(RootStatus.STALE, BuildRootProbe.default().status(File(parent, "a/b/c").path))
    }

    /**
     * The important one: an ancestor we cannot read must never be taken for
     * "the project was deleted" (an unmounted or hung network share).
     */
    @Test
    fun `an unreadable ancestor is unknown, not stale`() {
        val parent = tempDir()
        val blocked = File(parent, "mnt").apply { mkdirs() }
        blocked.setReadable(false, false)
        try {
            if (blocked.canRead()) return // running as root: the bit is ignored
            assertEquals(
                RootStatus.UNKNOWN,
                BuildRootProbe.default().status(File(blocked, "project").path),
            )
        } finally {
            blocked.setReadable(true, false)
        }
    }

    @Test
    fun `a pin keeps an absent root live`() {
        val parent = tempDir()
        val gone = File(parent, "unmounted-drive")

        assertEquals(RootStatus.LIVE, BuildRootProbe(listOf(gone.path)).status(gone.path))
        // A pin covers everything below it …
        assertEquals(RootStatus.LIVE, BuildRootProbe(listOf(parent.path)).status(File(parent, "sub/proj").path))
        // … but not a sibling whose name merely shares the prefix.
        assertEquals(
            RootStatus.STALE,
            BuildRootProbe(listOf(File(parent, "a").path)).status(File(parent, "ab").path),
        )
    }

    @Test
    fun `pins are read from keep-roots-txt next to the registry`() {
        val registryDir = tempDir("reg")
        val pinned = File(tempDir(), "offline-share")
        File(registryDir, BuildRootProbe.KEEP_ROOTS_FILE).writeText(
            """
            # temporarily offline
            ${pinned.path}

              ${File(registryDir.parentFile, "other").path}  # trailing comment
            """.trimIndent(),
        )

        val probe = BuildRootProbe.from(registryDir)
        assertEquals(RootStatus.LIVE, probe.status(pinned.path))
        assertTrue(probe.isPinned(pinned.path))
    }

    @Test
    fun `extra pins are honoured alongside the file`() {
        val registryDir = tempDir("reg2")
        val pinned = File(tempDir(), "elsewhere")
        assertEquals(RootStatus.LIVE, BuildRootProbe.from(registryDir, listOf(pinned.path)).status(pinned.path))
    }
}
