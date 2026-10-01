// prune.init.gradle.kts — init-script registration for gradle-prune.
//
// Semantics (identical to what the plugin's CaptureRegistrar does):
//
//   - hooks incoming.afterResolve on every resolvable configuration of every
//     project plus each project's buildscript configurations (plugin deps);
//   - merges the collected group:name:version coordinates into
//     <registryDir>/<sha1(buildRoot)[0..11]>.json at buildFinished
//     (success or failure), conservatively: union of modules, max lastSeen,
//     atomic temp-file + rename;
//   - never breaks the build: every hook is try/catch-ed, failures only log.
//
// Usage:
//   gradle -I /path/to/prune.init.gradle.kts build
//        [-Dgradle.prune.registry.dir=/custom/registry/dir]
//
// Global install (auto-registration for every build): the same file can be
// placed anywhere Gradle auto-loads init scripts from — no per-project
// configuration needed. Both locations work (verified on Gradle 9.7.1):
//   a) the init.d/ directory (recommended — coexists with other files):
//      mkdir -p ~/.gradle/init.d
//      cp /path/to/prune.init.gradle.kts ~/.gradle/init.d/gradle-prune.init.gradle.kts
//   b) a single root file — a bare ~/.gradle/init.gradle.kts at the root of
//      the Gradle user home is auto-loaded for every build as well
//      (use it only if you have no other root init file):
//      cp /path/to/prune.init.gradle.kts ~/.gradle/init.gradle.kts
//   Uninstall by removing the file, then `gradle --stop`.
//
// The installed file name MUST end in `.init.gradle.kts` (or be exactly
// `init.gradle.kts`): Gradle derives a Kotlin script's kind from its file
// name, so `foo-init.gradle.kts` is classified as a project build script.
// Builds still work (init.d is scanned by directory), but the IDE's
// script-model build then compiles the file without the init-script
// template and reports unresolved references for it (Gradle 9.7.1).
//
// Kill switch: set GRADLE_PRUNE_DISABLE (1/true/yes/on) as an environment
// variable, or -Dgradle.prune.skip=true, to turn this script into a
// complete no-op — useful while it lives in init.d/.
//
// Why this file needs explicit Action SAM types and workarounds: Gradle's
// callback APIs are Groovy-Closure-first, so plain Kotlin lambdas resolve
// to the Closure overloads and fail to compile (verified against 9.7.1):
//
//   - afterResolve(Action) / allDependencies(Action) need EXPLICIT Action SAM
//     types (Action<ResolvableDependencies>, Action<DependencyResult>);
//   - Configuration.isCanBeResolved() must be called by its getter name —
//     the Kotlin property `canBeResolved` is not generated;
//   - the `gradle` object is `Gradle?` inside top-level and buildFinished
//     script bodies (non-null in project receivers), so it is captured into a
//     val before use;
//   - buildFinished(Action) is deprecated in Java terms; the compiler warning
//     is not an error, it still works;
//   - kotlin.io.path.* imports are required for Path.writeText/moveTo/
//     createDirectories (File extensions have different receiver rules here).
//
import org.gradle.api.Action
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolutionResult
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.moveTo
import kotlin.io.path.writeText

// Shared state: coordinates seen so far in this build (script-scoped).
val resolvedModules = linkedSetOf<String>()
val g = gradle // capture: `gradle` is nullable inside buildFinished closures

// Kill switch: env GRADLE_PRUNE_DISABLE (1/true/yes/on) or
// -Dgradle.prune.skip=true turns this script into a complete no-op.
val pruneEnabled = !(
    System.getenv("GRADLE_PRUNE_DISABLE")?.lowercase() in listOf("1", "true", "yes", "on") ||
        System.getProperty("gradle.prune.skip")?.lowercase() in listOf("true", "1")
)

val capture = Action<ResolvableDependencies> {
    if (!pruneEnabled) return@Action
    try {
        resolutionResult.allDependencies(Action<DependencyResult> {
            if (this is ResolvedDependencyResult) {
                val id = this.selected.id
                // Kotlin template strings produce plain java.lang.String, so
                // the set's dedup works across builds (a Groovy GString does
                // NOT equal a String read back from the registry JSON).
                if (id is ModuleComponentIdentifier) {
                    resolvedModules.add("${id.group}:${id.module}:${id.version}")
                }
            }
        })
    } catch (t: Throwable) { /* never break the build */ }
}

if (pruneEnabled) {
    allprojects {
        configurations.configureEach { if (isCanBeResolved) incoming.afterResolve(capture) }
        buildscript.configurations.configureEach { if (isCanBeResolved) incoming.afterResolve(capture) }
    }
}

fun sha1Hex(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

g.buildFinished(Action {
    if (!pruneEnabled) return@Action
    try {
        if (resolvedModules.isEmpty()) return@Action
        val buildRoot = rootProject.projectDir.absolutePath
        val dir = File(System.getProperty("gradle.prune.registry.dir") ?: g.gradleUserHomeDir.path + "/prune/registry").toPath()
        dir.createDirectories()
        val target = dir.resolve(sha1Hex(buildRoot).take(12) + ".json")
        val esc = { s: String -> s.replace("\\", "\\\\").replace("\"", "\\\"") }
        val json = """{"buildRoot":"${esc(buildRoot)}","lastSeen":${System.currentTimeMillis()},"gradleVersion":"${esc(g.gradleVersion)}","modules":[${resolvedModules.joinToString(",") { "\"${esc(it)}\"" }}]}"""
        val tmp = dir.resolve(sha1Hex(buildRoot).take(12) + ".json.tmp")
        tmp.writeText(json)
        tmp.moveTo(target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        println("gradle-prune(kts): recorded ${resolvedModules.size} modules for $buildRoot")
    } catch (t: Throwable) {
        println("gradle-prune(kts): failed: ${t.message}")
    }
})
