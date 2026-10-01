// prune-init.gradle.kts — Kotlin-DSL variant of the init-script registration
// for gradle-prune. Same semantics as prune-init.gradle (the Groovy original):
//
//   - hooks incoming.afterResolve on every resolvable configuration of every
//     project plus each project's buildscript configurations (plugin deps);
//   - merges the collected group:name:version coordinates into
//     <registryDir>/<sha1(buildRoot)[0..11]>.json at buildFinished
//     (success or failure), conservatively: union of modules, max lastSeen,
//     atomic temp-file + rename;
//   - never breaks the build: every hook is try/catch-ed, failures only log.
//
// Usage (identical to the Groovy variant):
//   gradle -I /path/to/prune-init.gradle.kts build
//        [-Dgradle.prune.registry.dir=/custom/registry/dir]
//
// Why the Groovy version is still the recommended default: this Kotlin variant
// only compiles because Gradle's callback APIs are Groovy-Closure-first, so
// several traps had to be worked around (verified against Gradle 9.7.1):
//
//   - afterResolve(Action) / allDependencies(Action) need EXPLICIT Action SAM
//     types (Action<ResolvableDependencies>, Action<DependencyResult>) — the
//     plain lambda form resolves to the Closure overload and fails to compile;
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
// Prefer prune-init.gradle unless the surrounding ecosystem is Kotlin-only.
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

val resolvedModules = linkedSetOf<String>()
val g = gradle // capture: `gradle` is nullable inside buildFinished closures

val capture = Action<ResolvableDependencies> {
    try {
        resolutionResult.allDependencies(Action<DependencyResult> {
            if (this is ResolvedDependencyResult) {
                val id = this.selected.id
                if (id is ModuleComponentIdentifier) resolvedModules.add("${id.group}:${id.module}:${id.version}")
            }
        })
    } catch (t: Throwable) { /* never break the build */ }
}

allprojects {
    configurations.configureEach { if (isCanBeResolved) incoming.afterResolve(capture) }
    buildscript.configurations.configureEach { if (isCanBeResolved) incoming.afterResolve(capture) }
}

fun sha1Hex(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

g.buildFinished(Action {
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
