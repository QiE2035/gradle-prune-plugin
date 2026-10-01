package io.github.qie2035.gradleprune.plugin

import io.github.qie2035.gradleprune.core.CachePaths
import io.github.qie2035.gradleprune.core.ModuleCoordinate
import io.github.qie2035.gradleprune.core.capture.GraphWalker
import io.github.qie2035.gradleprune.core.registry.RegistryStore
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolutionResult
import java.io.File

/**
 * Wires the registration hook into a [Project]:
 *
 *  - every *resolvable* project configuration records its resolved external
 *    modules after resolution;
 *  - the same is done for the project buildscript classpath and the root
 *    project's buildscript classpath (plugin dependencies live there);
 *  - the accumulated coordinates are merged into the registry for this build
 *    root and written at `gradle.buildFinished` — including when the build
 *    fails, so a failing build still gets its *already-resolved* modules
 *    recorded and does not get pruned.
 *
 * The registry write is deliberately done at `buildFinished` rather than in
 * each `afterResolve` callback:
 *
 *  - it keeps the hook side-effect free (no I/O on the configuration
 *    resolution path, which also avoids surprising configuration-cache
 *    behaviour);
 *  - a single atomic write per build is all that is needed;
 *  - if the build is cancelled mid-run, the previous registry file is left
 *    untouched (we only overwrite at the very end), which is the
 *    conservative, safe behaviour.
 */
class CaptureRegistrar(private val project: Project) {

    private val buildRoot: File = project.rootProject.projectDir
    private val buildRootPath: String = buildRoot.absolutePath
    private val gradleVersion: String = project.gradle.gradleVersion

    /** Coordinates resolved so far in this build, keyed by nothing (a set). */
    private val resolved = LinkedHashSet<ModuleCoordinate>()
    private val lock = Any()

    /** Registry store; resolved lazily so the directory is created on write. */
    private val store: RegistryStore by lazy {
        RegistryStore(CachePaths.from().registryDir)
    }

    fun register() {
        // Project configurations (resolvable ones only).
        project.configurations.all { conf ->
            if (conf.isCanBeResolved()) {
                conf.incoming.afterResolve { deps ->
                    record(deps.resolutionResult)
                }
            }
        }

        // Buildscript classpaths: this project's + the root project's.
        project.buildscript.configurations.all { conf ->
            conf.incoming.afterResolve { deps ->
                record(deps.resolutionResult)
            }
        }

        // Flush at the end of the build, success or failure.
        project.gradle.buildFinished { result ->
            flush()
        }
    }

    /** Records the external module coordinates from one resolution. */
    private fun record(result: ResolutionResult) {
        try {
            val coords = GraphWalker.coordinates(result)
            if (coords.isNotEmpty()) {
                synchronized(lock) {
                    resolved += coords
                }
            }
        } catch (e: Exception) {
            // Never break the build over a capture failure; the previous
            // registry remains valid and conservative.
            project.logger.debug(
                "gradle-prune: failed to capture resolution graph: ${e.message}"
            )
        }
    }

    /** Merges this build's coordinates into the registry file. */
    private fun flush() {
        val snapshot: Set<ModuleCoordinate> = synchronized(lock) { resolved.toSet() }
        if (snapshot.isEmpty()) return
        try {
            store.upsert(
                buildRoot = buildRootPath,
                now = System.currentTimeMillis(),
                gradleVersion = gradleVersion,
                modules = snapshot,
            )
            project.logger.debug(
                "gradle-prune: recorded ${snapshot.size} modules for build root $buildRootPath"
            )
        } catch (e: Exception) {
            project.logger.warn(
                "gradle-prune: failed to write module registry: ${e.message}"
            )
        }
    }
}
