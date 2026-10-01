package io.github.qie2035.gradleprune.plugin

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end tests that drive the plugin through a **real Gradle build** via
 * TestKit.
 *
 * This is the half of the codebase unit tests cannot reach, and where two of
 * the regressions fixed alongside this test lived:
 *
 *  - `--all` was plumbed to "may the keep-set be empty", so a populated
 *    registry made it behave like an ordinary prune;
 *  - an unparseable registry file silently shrank the keep-set.
 *
 * Everything is hermetic and offline: the fixtures declare no external
 * repositories, and the registration test serves its dependency from a
 * hand-written Maven layout inside the fixture project. The Gradle user home
 * and TestKit dir are temp dirs, so the tests never touch `~/.gradle`.
 */
class PruneTasksIntegrationTest {

    // ---- shared, so every test reuses one Gradle daemon -------------------

    private val testKitDir: File get() = sharedTestKitDir
    private val gradleUserHome: File get() = sharedGradleUserHome

    // ---- registration (the plugin half) ----------------------------------

    /**
     * The plugin must record exactly the external modules the build resolved,
     * from a configuration the build actually resolved.
     */
    @Test
    fun `registers the modules a build resolves`() {
        val project = newFixture("register")
        writeMavenModule(File(project, "repo"), "com.example", "demo", "1.0")
        File(project, "settings.gradle.kts").writeText("""rootProject.name = "register"""")
        File(project, "build.gradle.kts").writeText(
            """
            plugins {
                java
                id("io.github.qie2035.gradle-prune")
            }
            repositories { maven { url = uri("repo") } }
            dependencies { implementation("com.example:demo:1.0") }
            tasks.register("resolveAll") {
                doLast { configurations.filter { it.isCanBeResolved() }.forEach { it.resolve() } }
            }
            """.trimIndent(),
        )

        val result = runner(project, "resolveAll").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":resolveAll")?.outcome)

        val registry = registryFileFor(project)
        assertTrue(registry.isFile, "expected a registry entry at ${registry.path}")
        val modules = registry.readText()
        assertTrue(
            modules.contains("com.example:demo:1.0"),
            "resolved module missing from the registry entry:\n$modules",
        )
    }

    // ---- pruning (the task half) -----------------------------------------

    /**
     * Regression: `--all` must ignore a populated registry and wipe the whole
     * cache, not just the modules no registered build uses.
     */
    @Test
    fun `all wipes the whole cache even with a populated registry`() {
        val project = newFixture("all")
        val cache = cacheWith("g1", "n1", "1.0", "g2", "n2", "2.0")
        val registry = File(project, "registry")
        writeRegistry(registry, buildRoot = "/proj/a", modules = listOf("g1:n1:1.0"))

        runner(
            project,
            "gradlePruneModules",
            "-Pprune.modules.modulesDir=${cache.path}",
            "-Pprune.modules.registryDir=${registry.path}",
            "-Pprune.modules.dryRun=false",
            "-Pprune.modules.all=true",
        ).build()

        assertFalse(moduleDir(cache, "g1", "n1", "1.0").exists(), "registered module must be deleted by --all")
        assertFalse(moduleDir(cache, "g2", "n2", "2.0").exists(), "unregistered module must be deleted")
    }

    /** The same run without `--all` must keep everything the registry covers. */
    @Test
    fun `a plain run keeps registered modules and deletes the rest`() {
        val project = newFixture("plain")
        val cache = cacheWith("g1", "n1", "1.0", "g2", "n2", "2.0")
        val registry = File(project, "registry")
        writeRegistry(registry, buildRoot = "/proj/a", modules = listOf("g1:n1:1.0"))

        runner(
            project,
            "gradlePruneModules",
            "-Pprune.modules.modulesDir=${cache.path}",
            "-Pprune.modules.registryDir=${registry.path}",
            "-Pprune.modules.dryRun=false",
        ).build()

        assertTrue(moduleDir(cache, "g1", "n1", "1.0").isDirectory, "registered module must be kept")
        assertFalse(moduleDir(cache, "g2", "n2", "2.0").exists(), "unregistered module must be deleted")
    }

    /** Dry-run is the default: it must not touch the cache. */
    @Test
    fun `the default run is a dry run that deletes nothing`() {
        val project = newFixture("dry")
        val cache = cacheWith("g1", "n1", "1.0", "g2", "n2", "2.0")
        val registry = File(project, "registry")
        writeRegistry(registry, buildRoot = "/proj/a", modules = listOf("g1:n1:1.0"))

        runner(
            project,
            "gradlePruneModules",
            "-Pprune.modules.modulesDir=${cache.path}",
            "-Pprune.modules.registryDir=${registry.path}",
        ).build()

        assertTrue(moduleDir(cache, "g1", "n1", "1.0").isDirectory)
        assertTrue(moduleDir(cache, "g2", "n2", "2.0").isDirectory)
    }

    /**
     * Regression: a registry file we cannot parse shrinks the keep-set, which
     * makes modules a build still needs look unused. The task must refuse to
     * delete, and say so, unless explicitly forced.
     */
    @Test
    fun `an unreadable registry file blocks a real deletion`() {
        val project = newFixture("corrupt")
        val cache = cacheWith("g1", "n1", "1.0", "g2", "n2", "2.0")
        val registry = File(project, "registry").apply { mkdirs() }
        writeRegistry(registry, buildRoot = "/proj/a", modules = listOf("g1:n1:1.0"))
        File(registry, "ffffffffffff.json").writeText("not json {")

        val common = arrayOf(
            "gradlePruneModules",
            "-Pprune.modules.modulesDir=${cache.path}",
            "-Pprune.modules.registryDir=${registry.path}",
            "-Pprune.modules.dryRun=false",
        )

        val failure = assertFailsWith<UnexpectedBuildFailure> { runner(project, *common).build() }
        assertTrue(
            failure.message!!.contains("unreadable registry files"),
            "expected the refusal message, got:\n${failure.message}",
        )
        // Nothing was deleted.
        assertTrue(moduleDir(cache, "g1", "n1", "1.0").isDirectory)
        assertTrue(moduleDir(cache, "g2", "n2", "2.0").isDirectory)

        // ...and --force overrides it.
        runner(project, *common, "-Pprune.modules.force=true").build()
        assertFalse(moduleDir(cache, "g2", "n2", "2.0").exists())
        assertTrue(moduleDir(cache, "g1", "n1", "1.0").isDirectory, "registered module is still kept")
    }

    // ---- helpers ---------------------------------------------------------

    private fun newFixture(name: String): File {
        val dir = createTempDirectory("prune-$name").toFile()
        File(dir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        File(dir, "build.gradle.kts").writeText(
            "plugins { id(\"io.github.qie2035.gradle-prune\") }",
        )
        return dir
    }

    private fun runner(projectDir: File, vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withTestKitDir(testKitDir)
        .withArguments(
            *args,
            "-g", gradleUserHome.absolutePath,
            "--offline",
            "--stacktrace",
        )
        .forwardOutput()

    private fun moduleDir(cache: File, g: String, n: String, v: String): File =
        File(File(File(cache, g), n), v)

    /** A synthetic `files-2.1`; the trailing triples are `g, n, v`. */
    private fun cacheWith(vararg gnv: String): File {
        val files = File(createTempDirectory("cache").toFile(), "files-2.1")
        gnv.toList().chunked(3).forEach { (g, n, v) ->
            val sha = File(moduleDir(files, g, n, v), "deadbeef").apply { mkdirs() }
            File(sha, "$n-$v.jar").writeBytes(ByteArray(64) { 1 })
        }
        return files
    }

    private fun writeRegistry(registry: File, buildRoot: String, modules: List<String>) {
        registry.mkdirs()
        File(registry, "${sha1(buildRoot).take(12)}.json").writeText(
            """{"buildRoot":"$buildRoot","lastSeen":1,"modules":[${modules.joinToString(",") { "\"$it\"" }}]}""",
        )
    }

    private fun registryFileFor(buildRoot: File): File =
        File(gradleUserHome, "prune/registry/${sha1(buildRoot.absolutePath).take(12)}.json")

    /** A minimal but valid Maven layout so resolution stays fully offline. */
    private fun writeMavenModule(repo: File, group: String, name: String, version: String) {
        val dir = File(repo, "${group.replace('.', '/')}/$name/$version").apply { mkdirs() }
        File(dir, "$name-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$name</artifactId>
              <version>$version</version>
            </project>
            """.trimIndent(),
        )
        File(dir, "$name-$version.jar").writeBytes(ByteArray(0))
    }

    private fun sha1(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private val sharedTestKitDir: File by lazy {
            createTempDirectory("prune-testkit").toFile()
        }
        private val sharedGradleUserHome: File by lazy {
            createTempDirectory("prune-guh").toFile()
        }
    }
}
