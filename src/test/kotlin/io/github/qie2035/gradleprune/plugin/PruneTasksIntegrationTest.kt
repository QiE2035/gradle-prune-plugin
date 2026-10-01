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
 * This is the half of the codebase unit tests cannot reach, and where several
 * regressions fixed alongside these tests lived:
 *
 *  - `--all` was plumbed to "may the keep-set be empty", so a populated
 *    registry made it behave like an ordinary prune;
 *  - an unparseable registry file silently shrank the keep-set;
 *  - a registered build whose root is gone used to pin its modules forever.
 *
 * Everything is hermetic and offline: the fixtures declare no external
 * repositories, and the registration test serves its dependency from a
 * hand-written Maven layout inside the fixture project. The Gradle user home
 * and TestKit dir are temp dirs, so the tests never touch `~/.gradle`.
 *
 * Build roots in the registry fixtures are real directories (the fixture
 * project itself) unless the test is specifically about a *gone* build —
 * otherwise every entry would be stale under the current semantics.
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

        val registry = File(gradleUserHome, "prune/registry/${sha1(project.absolutePath).take(12)}.json")
        assertTrue(registry.isFile, "expected a registry entry at ${registry.path}")
        assertTrue(
            registry.readText().contains("com.example:demo:1.0"),
            "resolved module missing from the registry entry:\n${registry.readText()}",
        )
    }

    // ---- pruning (the task half) -----------------------------------------

    /**
     * Regression: `--all` must ignore a populated registry and wipe the whole
     * cache, not just the modules no surviving build uses.
     */
    @Test
    fun `all wipes the whole cache even with a populated registry`() {
        val project = newFixture("all")
        val cache = cacheWith("g1", "n1", "1.0", "g2", "n2", "2.0")
        val registry = registryDir(project)
        writeRegistry(registry, project.absolutePath, "g1:n1:1.0")

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

    /** The same run without `--all` must keep everything a live build uses. */
    @Test
    fun `a plain run keeps modules a live build uses and deletes the rest`() {
        val project = newFixture("plain")
        val cache = cacheWith("g1", "n1", "1.0", "g2", "n2", "2.0")
        val registry = registryDir(project)
        writeRegistry(registry, project.absolutePath, "g1:n1:1.0")

        runner(
            project,
            "gradlePruneModules",
            "-Pprune.modules.modulesDir=${cache.path}",
            "-Pprune.modules.registryDir=${registry.path}",
            "-Pprune.modules.dryRun=false",
        ).build()

        assertTrue(moduleDir(cache, "g1", "n1", "1.0").isDirectory, "a live build still uses this")
        assertFalse(moduleDir(cache, "g2", "n2", "2.0").exists(), "unregistered module must be deleted")
    }

    /** Dry-run is the default: it must not touch the cache or the registry. */
    @Test
    fun `the default run is a dry run that deletes nothing`() {
        val project = newFixture("dry")
        val cache = cacheWith("g1", "n1", "1.0", "g2", "n2", "2.0")
        val registry = registryDir(project)
        writeRegistry(registry, project.absolutePath, "g1:n1:1.0")

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
     * The feature under test: a build root that no longer exists stops pinning
     * its modules, and a module another surviving build still references is
     * never released.
     */
    @Test
    fun `a gone build's entry is dropped and only its exclusive modules are freed`() {
        val project = newFixture("stale")
        val cache = cacheWith("shared", "lib", "9.9", "live", "only", "1.0", "dead", "only", "2.0")
        val registry = registryDir(project)

        writeRegistry(registry, project.absolutePath, "shared:lib:9.9", "live:only:1.0")
        val goneRoot = File(project, "deleted-project").absolutePath
        val goneEntry = writeRegistry(registry, goneRoot, "shared:lib:9.9", "dead:only:2.0")

        runner(
            project,
            "gradlePruneModules",
            "-Pprune.modules.modulesDir=${cache.path}",
            "-Pprune.modules.registryDir=${registry.path}",
            "-Pprune.modules.dryRun=false",
        ).build()

        assertFalse(goneEntry.exists(), "the gone build's registry entry must be dropped")
        assertFalse(moduleDir(cache, "dead", "only", "2.0").exists(), "only the gone build used this")
        assertTrue(moduleDir(cache, "live", "only", "1.0").isDirectory, "a live build still uses this")
        assertTrue(
            moduleDir(cache, "shared", "lib", "9.9").isDirectory,
            "a module a surviving build still references must never be released",
        )
    }

    /** The dry run must report the cleanup without performing it. */
    @Test
    fun `a dry run neither drops the gone entry nor deletes its modules`() {
        val project = newFixture("stale-dry")
        val cache = cacheWith("dead", "only", "2.0", "live", "only", "1.0")
        val registry = registryDir(project)

        writeRegistry(registry, project.absolutePath, "live:only:1.0")
        val goneEntry = writeRegistry(registry, File(project, "deleted-project").absolutePath, "dead:only:2.0")

        runner(
            project,
            "gradlePruneModules",
            "-Pprune.modules.modulesDir=${cache.path}",
            "-Pprune.modules.registryDir=${registry.path}",
        ).build()

        assertTrue(goneEntry.exists(), "a dry run must not touch the registry")
        assertTrue(moduleDir(cache, "dead", "only", "2.0").isDirectory, "a dry run must not delete")
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
        val registry = registryDir(project).apply { mkdirs() }
        writeRegistry(registry, project.absolutePath, "g1:n1:1.0")
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
        assertTrue(moduleDir(cache, "g1", "n1", "1.0").isDirectory)
        assertTrue(moduleDir(cache, "g2", "n2", "2.0").isDirectory)

        // ...and --force overrides it.
        runner(project, *common, "-Pprune.modules.force=true").build()
        assertFalse(moduleDir(cache, "g2", "n2", "2.0").exists())
        assertTrue(moduleDir(cache, "g1", "n1", "1.0").isDirectory, "a live build still uses this")
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

    private fun registryDir(project: File): File = File(project, "registry")

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

    /** Writes one registry entry and returns the file it lives in. */
    private fun writeRegistry(registry: File, buildRoot: String, vararg modules: String): File {
        registry.mkdirs()
        val file = File(registry, "${sha1(buildRoot).take(12)}.json")
        file.writeText(
            """{"buildRoot":"$buildRoot","lastSeen":1,"modules":[${modules.joinToString(",") { "\"$it\"" }}]}""",
        )
        return file
    }

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
