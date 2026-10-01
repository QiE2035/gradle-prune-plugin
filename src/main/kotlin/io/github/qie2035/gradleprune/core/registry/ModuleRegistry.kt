package io.github.qie2035.gradleprune.core.registry

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import kotlinx.serialization.Serializable

/**
 * The set of modules one build declared it uses, persisted per build root.
 *
 * Each build root gets its own file (`sha1(buildRoot).take(12).json`), so a
 * project that is deleted or stops building can simply have its file removed
 * from the union — exactly the pnpm "no project references this store entry"
 * case.
 *
 * [modules] is stored sorted + deduped in the `group:name:version` form
 * (see [ModuleCoordinate.toString]).
 */
@Serializable
data class ModuleRegistry(
    val buildRoot: String,
    /** Epoch millis of the last build that registered this root. */
    val lastSeen: Long,
    /** Gradle version of the registering build (informational). */
    val gradleVersion: String = "",
    val modules: Set<String> = emptySet(),
) {
    val coordinates: Set<ModuleCoordinate>
        get() = modules.mapNotNull {
            try {
                ModuleCoordinate.parse(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }.toSet()
}
