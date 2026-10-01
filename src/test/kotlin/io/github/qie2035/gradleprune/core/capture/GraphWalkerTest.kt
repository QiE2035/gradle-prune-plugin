package io.github.qie2035.gradleprune.core.capture

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import groovy.lang.Closure
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ComponentSelectionReason
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [GraphWalker] against minimal fakes of the Gradle
 * resolution-result interfaces. Signatures verified against Gradle 9.7.1
 * (javap); methods the walker never calls are stubbed with
 * `UnsupportedOperationException`.
 */
class GraphWalkerTest {

    // ---- fakes -----------------------------------------------------------

    private class FakeSelector : ComponentSelector {
        override fun getDisplayName(): String = "fake"
        override fun matchesStrictly(id: ComponentIdentifier): Boolean = false
        override fun getAttributes(): org.gradle.api.attributes.AttributeContainer =
            unsupported()
        override fun getRequestedCapabilities(): List<org.gradle.api.capabilities.Capability> =
            unsupported()
        override fun getCapabilitySelectors(): Set<org.gradle.api.artifacts.capability.CapabilitySelector> =
            unsupported()
    }

    private class FakeSelectionReason : ComponentSelectionReason {
        override fun isForced(): Boolean = false
        override fun isConflictResolution(): Boolean = false
        override fun isSelectedByRule(): Boolean = false
        override fun isExpected(): Boolean = true
        override fun isCompositeSubstitution(): Boolean = false
        override fun isConstrained(): Boolean = false
        override fun getDescriptions(): List<out org.gradle.api.artifacts.result.ComponentSelectionDescriptor> =
            emptyList()
    }

    /** A module component with a stable identity. */
    private class ModuleIdentifier(
        private val group: String,
        private val `module`: String,
        private val version: String,
    ) : ModuleComponentIdentifier {
        // Explicit getter overrides: Kotlin property-style overrides
        // (`override val group`) of these Java getters are rejected by the
        // compiler in this setup, while `override fun get…()` works.
        override fun getGroup(): String = group
        override fun getModule(): String = `module`
        override fun getVersion(): String = version
        // The nested name shadows the Gradle `ModuleIdentifier` import; refer
        // to it fully qualified. The walker never calls this getter.
        override fun getModuleIdentifier(): org.gradle.api.artifacts.ModuleIdentifier =
            throw IllegalStateException("not needed by the walker")
        override fun getDisplayName(): String = "$group:$module:$version"
    }

    /** A non-module (project) component id — must be skipped by the walker. */
    private object ProjectIdentifier : ComponentIdentifier {
        override fun getDisplayName(): String = "project :app"
    }

    private class FakeMvi(
        private val group: String,
        private val `name`: String,
        private val version: String,
    ) : ModuleVersionIdentifier {
        override fun getGroup(): String = group
        override fun getName(): String = `name`
        override fun getVersion(): String = version
        override fun getModule(): org.gradle.api.artifacts.ModuleIdentifier =
            throw IllegalStateException("not needed by the walker")
    }

    private class FakeComponent(
        private val id: ComponentIdentifier,
        private val mvi: ModuleVersionIdentifier?,
    ) : ResolvedComponentResult {
        override fun getId(): ComponentIdentifier = id
        override fun getModuleVersion(): ModuleVersionIdentifier =
            checkNotNull(mvi) { "module version not available for $id" }
        override fun getDependencies(): Set<out DependencyResult> = emptySet()
        override fun getDependents(): Set<out ResolvedDependencyResult> = emptySet()
        override fun getSelectionReason(): ComponentSelectionReason = FakeSelectionReason()
        override fun getVariants(): List<ResolvedVariantResult> = emptyList()
        override fun getDependenciesForVariant(variant: ResolvedVariantResult): List<DependencyResult> =
            unsupported()
    }

    private class FakeResolvedDep(
        private val selected: ResolvedComponentResult,
    ) : ResolvedDependencyResult {
        override fun getRequested(): ComponentSelector = FakeSelector()
        override fun getFrom(): ResolvedComponentResult = selected
        override fun isConstraint(): Boolean = false
        override fun getSelected(): ResolvedComponentResult = selected
        override fun getResolvedVariant(): ResolvedVariantResult = unsupported()
    }

    private class FakeResult(
        private val root: ResolvedComponentResult,
        private val deps: List<DependencyResult>,
    ) : ResolutionResult {
        override fun getRoot(): ResolvedComponentResult = root
        override fun getRootComponent(): org.gradle.api.provider.Provider<ResolvedComponentResult> =
            unsupported()
        override fun getRootVariant(): org.gradle.api.provider.Provider<ResolvedVariantResult> =
            unsupported()
        override fun getAllDependencies(): Set<out DependencyResult> = deps.toSet()
        override fun allDependencies(action: org.gradle.api.Action<in DependencyResult>) {
            deps.forEach(action::execute)
        }
        override fun allDependencies(closure: Closure<*>) = unsupported<Unit>()
        override fun getAllComponents(): Set<ResolvedComponentResult> =
            deps.filterIsInstance<ResolvedDependencyResult>().map { it.selected }.toSet() + root
        override fun allComponents(action: org.gradle.api.Action<in ResolvedComponentResult>) {
            Unit
        }
        override fun allComponents(closure: Closure<*>) = unsupported<Unit>()
        override fun getRequestedAttributes(): org.gradle.api.attributes.AttributeContainer =
            unsupported()
    }

    // ---- helpers ---------------------------------------------------------

    private fun module(id: ModuleIdentifier, mvi: FakeMvi) = FakeComponent(id, mvi)

    private fun resolvable(
        root: ResolvedComponentResult,
        vararg deps: DependencyResult,
    ) = FakeResult(root, deps.toList())

    // ---- tests -----------------------------------------------------------

    @Test
    fun `records external modules from resolved dependencies`() {
        val root = module(ModuleIdentifier("g", "root", "1"), FakeMvi("g", "root", "1"))
        val a = module(ModuleIdentifier("com.a", "lib-a", "1.0.0"), FakeMvi("com.a", "lib-a", "1.0.0"))
        val b = module(ModuleIdentifier("com.b", "lib-b", "2.1.3"), FakeMvi("com.b", "lib-b", "2.1.3"))
        val result = resolvable(root, FakeResolvedDep(a), FakeResolvedDep(b))

        val coords = GraphWalker.coordinates(result)
        assertEquals(
            setOf(
                ModuleCoordinate("com.a", "lib-a", "1.0.0"),
                ModuleCoordinate("com.b", "lib-b", "2.1.3"),
            ),
            coords,
        )
    }

    @Test
    fun `skips project (non-module) components`() {
        val projectRoot = FakeComponent(ProjectIdentifier, null)
        val m = module(ModuleIdentifier("g", "n", "1"), FakeMvi("g", "n", "1"))
        val result = resolvable(projectRoot, FakeResolvedDep(m))

        val coords = GraphWalker.coordinates(result)
        assertEquals(setOf(ModuleCoordinate("g", "n", "1")), coords)
    }

    @Test
    fun `empty result yields empty set`() {
        val root = module(ModuleIdentifier("g", "root", "1"), FakeMvi("g", "root", "1"))
        assertTrue(GraphWalker.coordinates(resolvable(root)).isEmpty())
    }

    @Test
    fun `deduplicates coordinates appearing in multiple dependencies`() {
        val root = module(ModuleIdentifier("g", "root", "1"), FakeMvi("g", "root", "1"))
        val m = module(ModuleIdentifier("g", "n", "1"), FakeMvi("g", "n", "1"))
        val result = resolvable(root, FakeResolvedDep(m), FakeResolvedDep(m))
        assertEquals(setOf(ModuleCoordinate("g", "n", "1")), GraphWalker.coordinates(result))
    }
}

/** Stub for interface members the walker never calls. */
@Suppress("UNCHECKED_CAST", "UNCHECKED_WARNING")
private fun <T> unsupported(): T = throw UnsupportedOperationException("not used by GraphWalker")
