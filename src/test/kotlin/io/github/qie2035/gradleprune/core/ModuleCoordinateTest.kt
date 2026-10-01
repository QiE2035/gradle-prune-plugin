package io.github.qie2035.gradleprune.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModuleCoordinateTest {

    @Test
    fun `parses simple coordinate`() {
        val c = ModuleCoordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
        assertEquals("org.jetbrains.kotlin", c.group)
        assertEquals("kotlin-stdlib", c.name)
        assertEquals("2.4.20", c.version)
    }

    @Test
    fun `parses group with dots`() {
        val c = ModuleCoordinate.parse("com.google.android.gms:play-services:18.0.1")
        assertEquals("com.google.android.gms", c.group)
        assertEquals("play-services", c.name)
    }

    @Test
    fun `round trips through toString`() {
        val c = ModuleCoordinate("a.b.c", "d-e", "1.2.3-RC1")
        assertEquals(c, ModuleCoordinate.parse(c.toString()))
        assertEquals("a.b.c:d-e:1.2.3-RC1", c.toString())
    }

    @Test
    fun `snapshot version parses verbatim`() {
        val c = ModuleCoordinate.parse("g:n:1.0-20240101.120000-42-SNAPSHOT")
        assertEquals("1.0-20240101.120000-42-SNAPSHOT", c.version)
    }

    @Test
    fun `rejects missing group`() {
        assertFailsWith<IllegalArgumentException> { ModuleCoordinate.parse(":n:1") }
    }

    @Test
    fun `rejects missing version`() {
        assertFailsWith<IllegalArgumentException> { ModuleCoordinate.parse("g:n:") }
    }

    @Test
    fun `rejects blank parts`() {
        assertFailsWith<IllegalArgumentException> { ModuleCoordinate(" ", "n", "1") }
        assertFailsWith<IllegalArgumentException> { ModuleCoordinate("g", "n", "1:2") }
    }

    @Test
    fun `toDir builds cache-relative path`() {
        val c = ModuleCoordinate("org.jetbrains", "kotlin-stdlib", "2.4.20")
        val dir = c.toDir(java.io.File("/tmp/files-2.1"))
        assertEquals(java.io.File("/tmp/files-2.1/org.jetbrains/kotlin-stdlib/2.4.20"), dir)
        assertTrue(c.toDir("/tmp/files-2.1").endsWith("org.jetbrains/kotlin-stdlib/2.4.20"))
    }
}
