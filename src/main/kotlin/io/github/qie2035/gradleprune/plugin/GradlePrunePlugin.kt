package io.github.qie2035.gradleprune.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `io.github.qie2035.gradle-prune` — the Gradle-side half of the tool.
 *
 * On the project it is applied to, the plugin does two things:
 *
 *  1. **Registration** — [CaptureRegistrar] hooks every resolvable
 *     configuration (project configurations plus build/plugin classpaths) and
 *     records the resolved external module coordinates into a per-build-root
 *     registry file under `$GRADLE_USER_HOME/prune/registry/`. The registry
 *     is written at `buildFinished`, even when the build fails.
 *
 *  2. **Prune tasks** — registers [PruneGradleCacheTask]
 *     (`gradlePruneModules`) and [PruneVersionCachesTask]
 *     (`gradlePruneVersionCaches`) on the root project, so the shared cache
 *     can be cleaned from inside a Gradle build as well as via the CLI.
 *
 * Typical wiring:
 *
 * ```kotlin
 * // settings.gradle.kts or the root build script
 * subprojects {
 *     apply(plugin = "io.github.qie2035.gradle-prune")
 * }
 * apply(plugin = "io.github.qie2035.gradle-prune")
 * ```
 *
 * The deletion logic lives in the `core` package (pure Kotlin, no Gradle API)
 * and is shared verbatim by the CLI.
 */
class GradlePrunePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // 1. Capture this project's resolved modules; flush at build end.
        CaptureRegistrar(project).register()

        // 2. Prune tasks on the root project (register idempotently). The
        //    tasks' flags are driven by -P project properties so they can be
        //    switched from the command line without editing the build script:
        //
        //      gradle gradlePruneModules -Pprune.modules.dryRun=false
        //      gradle gradlePruneVersionCaches -Pprune.versions.dryRun=false
        val root = project.rootProject
        if (root.tasks.findByName(PRUNE_TASK_NAME) == null) {
            root.tasks.register(PRUNE_TASK_NAME, PruneGradleCacheTask::class.java) { task ->
                task.dryRun = project.boolProp(PROP_MODULES_DRY_RUN, true)
                task.force = project.boolProp(PROP_MODULES_FORCE, false)
                task.all = project.boolProp(PROP_MODULES_ALL, false)
                task.verbose = project.boolProp(PROP_MODULES_VERBOSE, false)
                task.modulesDir = project.stringProp(PROP_MODULES_DIR)
                task.registryDir = project.stringProp(PROP_REGISTRY_DIR)
            }
        }
        if (root.tasks.findByName(VERSION_TASK_NAME) == null) {
            root.tasks.register(VERSION_TASK_NAME, PruneVersionCachesTask::class.java) { task ->
                // The version in use at execution time is always the running
                // Gradle's own version.
                task.gradleVersion = project.gradle.gradleVersion
                task.dryRun = project.boolProp(PROP_VERSIONS_DRY_RUN, true)
                task.cachesDir = project.stringProp(PROP_VERSIONS_CACHES_DIR)
            }
        }
    }

    companion object {
        const val PRUNE_TASK_NAME = "gradlePruneModules"
        const val VERSION_TASK_NAME = "gradlePruneVersionCaches"

        const val PROP_MODULES_DRY_RUN = "prune.modules.dryRun"
        const val PROP_MODULES_FORCE = "prune.modules.force"
        const val PROP_MODULES_ALL = "prune.modules.all"
        const val PROP_MODULES_VERBOSE = "prune.modules.verbose"
        const val PROP_MODULES_DIR = "prune.modules.modulesDir"
        const val PROP_REGISTRY_DIR = "prune.modules.registryDir"
        const val PROP_VERSIONS_DRY_RUN = "prune.versions.dryRun"
        const val PROP_VERSIONS_CACHES_DIR = "prune.versions.cachesDir"
    }
}

/**
 * Reads the project property [name] as a boolean, or [default] when it is
 * unset. Accepts `true`/`false`/`1`/`0` (case-insensitive for the words).
 */
private fun Project.boolProp(name: String, default: Boolean): Boolean {
    val raw = providers.gradleProperty(name).orNull ?: return default
    val v = raw.trim().lowercase()
    return when (v) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> default
    }
}

/** Reads the project property [name] as a string, or null when unset/blank. */
private fun Project.stringProp(name: String): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
