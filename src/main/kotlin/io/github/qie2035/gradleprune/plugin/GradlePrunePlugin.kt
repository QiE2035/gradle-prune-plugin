package io.github.qie2035.gradleprune.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `io.github.qie2035.gradle-prune` — the Gradle-side half of the tool.
 *
 * On the project it is applied to, the plugin does two things:
 *
 *  1. **Registration** — one [CaptureRegistrar] per *build root* hooks every
 *     resolvable configuration of every project in the build (plus their
 *     buildscript classpaths) and records the resolved external module
 *     coordinates into a per-build-root registry file under
 *     `$GRADLE_USER_HOME/prune/registry/`. The registry is written at
 *     `buildFinished`, even when the build fails.
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
 * Applying it to several projects of the same build is safe and idempotent:
 * the first apply creates the (single) build-wide registrar, the rest reuse
 * it. The registry is keyed by build root, so "what did this build resolve"
 * is the right granularity — not "what did this one project resolve".
 *
 * The deletion logic lives in the `core` package (pure Kotlin, no Gradle API)
 * and is shared verbatim by the CLI.
 */
class GradlePrunePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val root = project.rootProject

        // 1. Capture this build's resolved modules; flush at build end.
        //
        //    Guarded so that applying the plugin to N projects (which the
        //    global install in prune-global.init.gradle.kts does) creates ONE
        //    registrar instead of N: N registrars would each hold a partial
        //    coordinate set and each re-read + rewrite the same registry file
        //    at build end.
        //
        //    The marker is a plain Boolean in the root project's extra
        //    properties, so it stays configuration-cache serializable.
        val marker = root.extensions.extraProperties
        if (!marker.has(CAPTURE_REGISTRAR_KEY)) {
            marker.set(CAPTURE_REGISTRAR_KEY, true)
            CaptureRegistrar(root).register()
        }

        // 2. Prune tasks on the root project (register idempotently). The
        //    tasks' flags are driven by -P project properties so they can be
        //    switched from the command line without editing the build script:
        //
        //      gradle gradlePruneModules -Pprune.modules.dryRun=false
        //      gradle gradlePruneVersionCaches -Pprune.versions.dryRun=false
        //
        //    Both tasks are told where the *running* build's Gradle user home
        //    is: inside a build the source of truth is
        //    Gradle.getGradleUserHomeDir() (which honours -g/--gradle-user-home),
        //    not the GRADLE_USER_HOME environment variable.
        val gradleUserHome = project.gradle.gradleUserHomeDir
        if (root.tasks.findByName(PRUNE_TASK_NAME) == null) {
            root.tasks.register(PRUNE_TASK_NAME, PruneGradleCacheTask::class.java) { task ->
                task.gradleUserHome = gradleUserHome
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
                task.gradleUserHome = gradleUserHome
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

        /** Opt-in mtime-delta capture (`-Pprune.captureDownloads=true`). */
        const val PROP_CAPTURE_DOWNLOADS = "prune.captureDownloads"

        /** Root-project extra-property marker: "the build registrar exists". */
        internal const val CAPTURE_REGISTRAR_KEY = "io.github.qie2035.gradle-prune.registrar"
    }
}

/**
 * Parses a Gradle-style boolean (`true/false`, `1/0`, `yes/no`, `on/off`,
 * case-insensitive), or null when the value is absent or unrecognised.
 */
internal fun parseBool(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
    null -> null
    "true", "1", "yes", "on" -> true
    "false", "0", "no", "off" -> false
    else -> null
}

/**
 * Reads the project property [name] as a boolean, or [default] when it is
 * unset or unparseable.
 */
private fun Project.boolProp(name: String, default: Boolean): Boolean =
    parseBool(providers.gradleProperty(name).orNull) ?: default

/** Reads the project property [name] as a string, or null when unset/blank. */
private fun Project.stringProp(name: String): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
