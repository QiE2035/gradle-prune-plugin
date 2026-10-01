package io.github.qie2035.gradleprune.core.capture

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/**
 * Extracts the set of third-party module coordinates a Gradle build resolved,
 * from its [ResolutionResult] graphs.
 *
 * Only *external modules* (identifiers of type [ModuleComponentIdentifier])
 * are recorded — project components, settings plugins, and build-platform
 * artifacts that are not regular modules are skipped. This is what makes the
 * registry the "keep set": the union of modules the build actually used.
 *
 * Verified against Gradle 9.7.1:
 *  - `ResolutionResult.getAllDependencies(): Set<out DependencyResult>` —
 *    flat view over the whole graph, including the root dependency.
 *  - `ResolvedDependencyResult.getSelected(): ResolvedComponentResult`
 *  - `ResolvedComponentResult.getModuleVersion()` is only safe to call when
 *    the component id is a [ModuleComponentIdentifier] (project components
 *    throw), so we gate on the id type.
 */
object GraphWalker {

    /**
     * All external module coordinates resolved in [result].
     *
     * Only the *dependency* nodes of the graph are inspected. The graph root
     * ([ResolutionResult.root]) is always the owning project (or its
     * buildscript), never an external module, so it is skipped.
     *
     * @param result the resolution result of a resolvable configuration.
     */
    fun coordinates(result: ResolutionResult): Set<ModuleCoordinate> {
        val out = LinkedHashSet<ModuleCoordinate>()
        for (dep in result.allDependencies) {
            if (dep is ResolvedDependencyResult) {
                selectedModule(dep).let { if (it != null) out += it }
            }
        }
        return out
    }

    /** The selected external module of a resolved dependency, if any. */
    private fun selectedModule(dep: ResolvedDependencyResult): ModuleCoordinate? {
        val selected: ResolvedComponentResult = dep.selected
        return moduleOf(selected)
    }

    /**
     * Returns the [ModuleCoordinate] for [component] when it is an external
     * module component, else null. Never throws: anything that is not a
     * module identifier (project components, platform components, …) is
     * simply not recorded.
     */
    private fun moduleOf(component: ResolvedComponentResult): ModuleCoordinate? {
        val id = component.id
        if (id !is ModuleComponentIdentifier) return null
        return try {
            val g = id.group
            val n = id.`module`
            val v = id.version
            if (g.isNotBlank() && n.isNotBlank() && v.isNotBlank()) {
                ModuleCoordinate(g, n, v)
            } else {
                null
            }
        } catch (e: Exception) {
            // A component that advertises a module id but fails to report a
            // version (malformed edge cases) is skipped rather than aborting.
            null
        }
    }
}
