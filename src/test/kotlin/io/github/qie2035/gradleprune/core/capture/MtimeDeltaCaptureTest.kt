package io.github.qie2035.gradleprune.core.capture

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the opt-in mtime-delta safety net, which previously had none
 * despite being documented.
 */
class MtimeDeltaCaptureTest {

    /**
     * Creates `files-2.1/<g>/<n>/<v>/<file>` and back-dates the directory and
     * file by [ageMs] so the capture boundary is deterministic.
     */
    private fun module(files: File, g: String, n: String, v: String, ageMs: Long) {
        val dir = File(File(File(files, g), n), v)
        dir.mkdirs()
        val file = File(dir, "$n-$v.jar")
        file.writeBytes(ByteArray(16) { 1 })
        val stamp = System.currentTimeMillis() - ageMs
        file.setLastModified(stamp)
        dir.setLastModified(stamp)
    }

    @Test
    fun `captures only directories touched at or after the build start`() {
        val root = createTempDirectory("mtime").toFile()
        val files = File(root, "files-2.1")
        module(files, "old", "lib", "1.0", ageMs = 60 * 60 * 1000)
        module(files, "fresh", "lib", "2.0", ageMs = 0)

        val captured = MtimeDeltaCapture.capture(files, System.currentTimeMillis() - 60_000)
        assertEquals(setOf(ModuleCoordinate("fresh", "lib", "2.0")), captured)
    }

    @Test
    fun `a since bound in the future captures nothing`() {
        val root = createTempDirectory("mtime2").toFile()
        val files = File(root, "files-2.1")
        module(files, "g", "n", "1.0", ageMs = 0)

        assertTrue(MtimeDeltaCapture.capture(files, System.currentTimeMillis() + 60_000).isEmpty())
    }

    @Test
    fun `missing cache directory yields an empty set`() {
        assertTrue(MtimeDeltaCapture.capture(File("/does/not/exist/files-2.1"), 0L).isEmpty())
    }

    /**
     * A directory name the coordinate grammar rejects (a `:` is legal in a
     * Linux file name) must be skipped rather than aborting the capture.
     */
    @Test
    fun `malformed directory names are skipped instead of throwing`() {
        val root = createTempDirectory("mtime3").toFile()
        val files = File(root, "files-2.1")
        module(files, "g", "n", "1.0", ageMs = 0)
        File(File(File(files, "g"), "n"), "bad:version").mkdirs()

        val captured = MtimeDeltaCapture.capture(files, System.currentTimeMillis() - 60_000)
        assertEquals(setOf(ModuleCoordinate("g", "n", "1.0")), captured)
    }

    @Test
    fun `lastModifiedOf reports the newest file below a directory`() {
        val dir = createTempDirectory("newest").toFile()
        val sub = File(dir, "sub").apply { mkdirs() }
        val file = File(sub, "f.bin").apply { writeBytes(ByteArray(4)) }
        // Back-date outermost-last so the newest entry really is the file.
        file.setLastModified(1_000_000L)
        sub.setLastModified(1_000_000L)
        dir.setLastModified(999_000L)

        assertEquals(1_000_000L, MtimeDeltaCapture.lastModifiedOf(dir))
        assertEquals(0L, MtimeDeltaCapture.lastModifiedOf(File("/does/not/exist")))
    }
}
