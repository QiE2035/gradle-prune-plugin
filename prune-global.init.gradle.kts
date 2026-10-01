/*
 * prune-global.init.gradle.kts — global plugin install for gradle-prune.
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
 * from mavenCentral) on the init script's own classpath, so the plugin
 * class is a normal compile-time reference in the script body; the script
 * applies it to every project via `pluginManager.apply(Class)` — plugin-id
 * resolution does not consult the init script classpath, and the KTS
 * `apply(String)` extension would reject a Class argument anyway.
 *
 * The `initscript {}` block must stay at the TOP of the script: Gradle
 * only wires the init classpath there (a nested block does not make the
 * declared classpath visible to the script). Verified on Gradle 9.7.1.
 *
 * Prerequisite (run once in the gradle-prune-plugin checkout):
 *   gradle publishToMavenLocal
 * If the artifact is missing, every build on the machine fails at init
 * with a dependency-resolution error — an intentional hard dependency;
 * remove this file (see uninstall) to back out.
 *
 * Install (pick ONE — both locations are auto-loaded):
 *   a) init.d directory (recommended — coexists with other init files):
 *      mkdir -p ~/.gradle/init.d
 *      cp /path/to/prune-global.init.gradle.kts \
 *         ~/.gradle/init.d/gradle-prune.init.gradle.kts
 *   b) single root init file, if ~/.gradle has none yet:
 *      cp /path/to/prune-global.init.gradle.kts ~/.gradle/init.gradle.kts
 *   Then restart daemons: gradle --stop
 *
 * The installed file name MUST end in `.init.gradle.kts` (or be exactly
 * `init.gradle.kts`). Gradle derives a Kotlin script's kind from its file
 * name: only `init.gradle.kts` and `*.init.gradle.kts` are init scripts,
 * while e.g. `foo-init.gradle.kts` falls through to the `*.gradle.kts`
 * project-script match. Builds still work (init.d is scanned by directory),
 * but the IDE's script-model build then compiles this file as a project
 * build script and reports `Unresolved reference 'initscript'`, an
 * unresolved plugin import and an unresolved `classpath` for it — verified
 * on Gradle 9.7.1. Renaming the file is the whole fix.
 *
 * Uninstall: remove the file, then `gradle --stop`.
 *
 * Kill switch: set GRADLE_PRUNE_DISABLE (1/true/yes/on) as an environment
 * variable, or -Dgradle.prune.skip=true, to turn this script into a
 * complete no-op.
 *
 * A global init script must never break a build over one project's apply
 * failure, so the per-project apply is try/catch-ed (debug-logged only).
 */

import io.github.qie2035.gradleprune.plugin.GradlePrunePlugin

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
}

// Kill switch: env GRADLE_PRUNE_DISABLE (1/true/yes/on) or
// -Dgradle.prune.skip=true turns this script into a complete no-op.
val pruneEnabled = !(
    System.getenv("GRADLE_PRUNE_DISABLE")?.lowercase() in listOf("1", "true", "yes", "on") ||
        System.getProperty("gradle.prune.skip")?.lowercase() in listOf("true", "1")
)

if (pruneEnabled) {
    allprojects {
        try {
            pluginManager.apply(GradlePrunePlugin::class.java)
        } catch (t: Throwable) {
            // A global init script must never break one project's build.
            logger.debug("gradle-prune: global apply skipped: ${t.message}")
        }
    }
}
