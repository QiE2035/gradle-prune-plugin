/*
 * prune-global-init.gradle.kts — global plugin install for gradle-prune
 * (Kotlin-DSL variant of prune-global-init.gradle; identical behaviour).
 *
 * Makes the io.github.qie2035.gradle-prune PLUGIN itself load globally:
 * every build on this machine automatically
 *   - registers its resolved modules in the gradle-prune registry, and
 *   - gets the gradlePruneModules / gradlePruneVersionCaches tasks,
 * with no per-project plugins{} entry and no duplicated init-script
 * capture code.
 *
 * How it works: the `initscript {}` block below declares the plugin jar
 * (from the local Maven repository, with its transitive dependencies
 * from mavenCentral) on the init script's own classpath; the script then
 * loads the plugin class from that classpath and applies it to every
 * project.
 *
 * KTS-specific workarounds (Gradle 9.7.1, verified):
 *   - the `initscript {}` block must stay at the TOP of the script —
 *     Gradle only wires the init classpath there, and Kotlin can only
 *     reach the initscript handler's members from a top-level block
 *     (a nested second block compiles against the wrong receiver);
 *   - the classpath is force-resolved inside that same block, because
 *     `initscript.configurations` is not reachable from the script body;
 *   - the plugin is applied by CLASS via `project.pluginManager.apply(Class)`
 *     — plugin-id resolution does not consult the init script classpath,
 *     and `PluginContainer.apply` is shadowed in Kotlin by the KTS
 *     `apply(String)` extension, which rejects Class arguments;
 *   - a global init script must never break a build, so per-project
 *     apply failures and load failures are caught and logged only.
 *
 * Prerequisite (run once in the gradle-prune-plugin checkout):
 *   gradle publishToMavenLocal
 *
 * Install (pick ONE — both locations are auto-loaded):
 *   a) init.d directory (recommended — coexists with other init files):
 *      mkdir -p ~/.gradle/init.d
 *      cp /path/to/prune-global-init.gradle.kts ~/.gradle/init.d/
 *   b) single root init file, if ~/.gradle has none yet:
 *      cp /path/to/prune-global-init.gradle.kts ~/.gradle/init.gradle.kts
 *   Then restart daemons: gradle --stop
 *
 * Uninstall: remove the file, then `gradle --stop`.
 *
 * Kill switch: set GRADLE_PRUNE_DISABLE (1/true/yes/on) as an environment
 * variable, or -Dgradle.prune.skip=true, to turn this script into a
 * complete no-op.
 *
 * If the plugin cannot be resolved (publishToMavenLocal not run yet, or a
 * transient problem) the script logs one warning and every build continues
 * unaffected — a global init script must never break builds.
 */

import org.gradle.api.Plugin

// Top level on purpose — see the header above.
initscript {
    repositories {
        // mavenLocal() first: the plugin itself lives there.
        mavenLocal()
        // The plugin's transitive dependencies (clikt, mordant,
        // kotlinx-serialization, kotlin-stdlib) resolve from here.
        mavenCentral()
    }
    dependencies {
        classpath("io.github.qie2035:gradle-prune-plugin:1.0-SNAPSHOT")
    }
    // Force resolution so the plugin class is visible to the init
    // script's class loader in this build.
    configurations["classpath"].files
}

// Kill switch: env GRADLE_PRUNE_DISABLE (1/true/yes/on) or
// -Dgradle.prune.skip=true turns this script into a complete no-op.
fun pruneEnabled(): Boolean {
    val env = System.getenv("GRADLE_PRUNE_DISABLE")
    if (env != null && env.lowercase() in listOf("1", "true", "yes", "on")) {
        return false
    }
    return System.getProperty("gradle.prune.skip")?.lowercase() !in listOf("true", "1")
}

if (pruneEnabled()) {
    try {
        // Explicit Class<Plugin<*>?> so the Java
        // PluginManager.apply(Class<out Plugin<*>>) overload is selected.
        val pluginClass: Class<Plugin<*>?> = initscript.classLoader.loadClass(
            "io.github.qie2035.gradleprune.plugin.GradlePrunePlugin",
        ) as Class<Plugin<*>?>
        allprojects {
            try {
                // Project.pluginManager.apply(Class) — the Java method.
                pluginManager.apply(pluginClass)
            } catch (t: Throwable) {
                // A global init script must never break one project's build.
                logger.debug("gradle-prune: global apply skipped: ${t.message}")
            }
        }
    } catch (t: Throwable) {
        println(
            "gradle-prune: global init disabled — could not load the plugin " +
                "(run 'gradle publishToMavenLocal' in the plugin checkout): ${t.message}",
        )
    }
}
