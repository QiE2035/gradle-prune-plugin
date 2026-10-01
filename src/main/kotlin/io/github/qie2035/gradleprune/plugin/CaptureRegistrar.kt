package io.github.qie2035.gradleprune.plugin

import io.github.qie2035.gradleprune.core.CachePaths
import io.github.qie2035.gradleprune.core.ModuleCoordinate
import io.github.qie2035.gradleprune.core.capture.GraphWalker
import io.github.qie2035.gradleprune.core.capture.MtimeDeltaCapture
import io.github.qie2035.gradleprune.core.registry.RegistryStore
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.result.ResolutionResult
import java.io.File

/**
 * Wires the registration hook into a build. Instantiated once per build root
 * by [GradlePrunePlugin] (see [GradlePrunePlugin.CAPTURE_REGISTRAR_KEY]).
 *
 *  - every *resolvable* configuration of every project in the build records
 *    its resolved external modules after resolution;
 *  - the same is done for each project's buildscript classpath (plugin
 *    dependencies live there);
 *  - the accumulated coordinates are merged into the registry for this build
 *    root and written at `gradle.buildFinished` — including when the build
 *    fails, so a failing build still gets its *already-resolved* modules
 *    recorded and does not get pruned.
 *
 * **Capture completeness.** [GraphWalker] can only see modules that appear as
 * a *selected dependency* in a `ResolutionResult`. Modules Gradle downloads
 * for metadata-only reasons, for settings/`pluginManagement` resolution, or
 * through a `detachedConfiguration` are invisible to it. The opt-in
 * [MtimeDeltaCapture] safety net (`-Pprune.captureDownloads=true`) covers them
 * by recording every `files-2.1` directory written since this build started.
 * Anything still missed is a *re-download*, never a broken build.
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

    /** Root project dir — the identity of "one build" for registry purposes. */
    private val buildRoot: File = project.projectDir
    private val buildRootPath: String = buildRoot.absolutePath
    private val gradleVersion: String = project.gradle.gradleVersion

    /**
     * Wall-clock start of this build, used as the `since` bound of the opt-in
     * mtime-delta capture. Captured while the build is still configuring, so
     * every artifact downloaded during dependency resolution is newer.
     */
    private val buildStartMs: Long = System.currentTimeMillis()

    /** Coordinates resolved so far in this build. */
    private val resolved = LinkedHashSet<ModuleCoordinate>()
    private val lock = Any()

    /**
     * Cache/registry locations for the *running* build.
     *
     * Uses [org.gradle.api.invocation.Gradle.getGradleUserHomeDir] rather than
     * the `GRADLE_USER_HOME` environment variable, which `-g` /
     * `--gradle-user-home` does not update — resolving from the environment
     * would read/write a different Gradle home than the build uses.
     * Registry-only use here; the same resolution happens in the prune tasks.
     */
    private val paths: CachePaths by lazy {
        CachePaths.from(gradleUserHome = project.gradle.gradleUserHomeDir)
    }

    /** Registry store; resolved lazily so the directory is created on write. */
    private val store: RegistryStore by lazy { RegistryStore(paths.registryDir) }

    /** Opt-in mtime-delta safety net, off by default. */
    private val captureDownloads: Boolean =
        parseBool(project.providers.gradleProperty(GradlePrunePlugin.PROP_CAPTURE_DOWNLOADS).orNull)
            ?: parseBool(System.getProperty("gradle.prune.captureDownloads"))
            ?: false

    fun register() {
        // Every project of the build, not just the one the plugin was applied
        // to: the registry is keyed by build root, and with the global install
        // the plugin *is* applied to every project — the first apply wins and
        // the rest are no-ops (guarded by the caller).
        project.allprojects { p ->
            hook(p.configurations)
            hook(p.buildscript.configurations)
        }
        registerFlush()
    }

    /** Records the resolved external modules of every resolvable configuration. */
    private fun hook(configurations: ConfigurationContainer) {
        configurations.all { conf ->
            if (conf.isCanBeResolved()) {
                conf.incoming.afterResolve { deps -> record(deps.resolutionResult) }
            }
        }
    }

    /**
     * Flushes the registry at the end of the build.
     *
     * `Gradle.buildFinished` is deprecated in Gradle 9 (the replacement is a
     * build service driven by `BuildEventsListenerRegistry` / `FlowScope`),
     * but it is still the only build-end hook usable from both a plain plugin
     * and the `prune.init.gradle.kts` init script without extra machinery. The
     * deprecation is suppressed explicitly and tracked here and in the README
     * so it cannot be lost silently.
     */
    @Suppress("DEPRECATION")
    private fun registerFlush() {
        project.gradle.buildFinished { flush() }
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
        val modules = if (captureDownloads) snapshot + captureByMtimeDelta() else snapshot
        if (modules.isEmpty()) return
        try {
            store.upsert(
                buildRoot = buildRootPath,
                now = System.currentTimeMillis(),
                gradleVersion = gradleVersion,
                modules = modules,
            )
            project.logger.debug(
                "gradle-prune: recorded ${modules.size} modules for build root $buildRootPath"
            )
        } catch (e: Exception) {
            project.logger.warn(
                "gradle-prune: failed to write module registry: ${e.message}"
            )
        }
    }

    /** The opt-in mtime-delta capture; never fails the build. */
    private fun captureByMtimeDelta(): Set<ModuleCoordinate> = try {
        MtimeDeltaCapture.capture(paths.filesDir, buildStartMs)
    } catch (e: Exception) {
        project.logger.debug("gradle-prune: mtime-delta capture failed: ${e.message}")
        emptySet()
    }
}
