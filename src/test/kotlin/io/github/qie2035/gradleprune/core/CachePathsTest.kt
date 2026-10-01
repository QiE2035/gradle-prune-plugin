package io.github.qie2035.gradleprune.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CachePathsTest {

    private fun home(tmp: File): File = tmp

    @Test
    fun `defaults derive from home dir when no env`() {
        val home = File("/home/user").absoluteFile
        val p = CachePaths.from(homeDir = home, env = emptyMap())
        assertEquals(File(home, ".gradle").absoluteFile, p.gradleUserHome)
        assertEquals(
            File(File(File(home, ".gradle"), "caches"), "modules-2").resolve("files-2.1").absoluteFile,
            p.filesDir,
        )
        assertEquals(
            File(File(File(home, ".gradle"), "prune"), "registry").absoluteFile,
            p.registryDir,
        )
        assertEquals(p.filesDir.parentFile, p.modulesDir)
        assertEquals(File(p.modulesDir, "modules-2.lock"), p.modulesLock)
    }

    @Test
    fun `GRADLE_USER_HOME env overrides home`() {
        val guy = File("/opt/gradle-home")
        val p = CachePaths.from(
            homeDir = File("/home/user"),
            env = mapOf("GRADLE_USER_HOME" to guy.absolutePath),
        )
        assertEquals(guy.absoluteFile, p.gradleUserHome)
        assertTrue(p.filesDir.path.startsWith(guy.absolutePath))
    }

    @Test
    fun `explicit filesDir and registryDir win`() {
        val files = File("/cache/files-2.1")
        val reg = File("/cache/prune-registry")
        val p = CachePaths.from(filesDir = files, registryDir = reg)
        assertEquals(files.absoluteFile, p.filesDir)
        assertEquals(reg.absoluteFile, p.registryDir)
        assertEquals(files.absoluteFile.parentFile, p.modulesDir)
        assertEquals(File(p.modulesDir, "modules-2.lock"), p.modulesLock)
    }
}
