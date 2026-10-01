package io.github.qie2035.gradleprune.core.registry

import io.github.qie2035.gradleprune.core.ModuleCoordinate
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Reads and writes per-build [ModuleRegistry] files under a registry
 * directory, and computes the **union** of all registered builds — the
 * "keep set" for pruning (mirrors pnpm's "scan every project that uses the
 * store").
 *
 * Writes are atomic (temp file + rename) and partitioned per build root, so
 * concurrent builds writing different files never clobber each other.
 */
class RegistryStore(
    val registryDir: File,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    },
) {
    /** The file that stores [registry]'s build root. */
    fun fileFor(buildRoot: String): File {
        val key = sha1(buildRoot).take(12)
        return File(registryDir, "$key.json")
    }

    /**
     * Atomically (re)writes the registry for [buildRoot]. Merges with any
     * existing entry for the same root (union of modules, newest lastSeen) so
     * a partial in-flight build never loses previously-seen coordinates.
     */
    fun upsert(
        buildRoot: String,
        now: Long = System.currentTimeMillis(),
        gradleVersion: String = "",
        modules: Collection<ModuleCoordinate> = emptyList(),
    ): ModuleRegistry {
        val existing = read(buildRoot)
        val mergedModules = (existing?.modules ?: emptySet()) + modules.map { it.toString() }
        val merged = ModuleRegistry(
            buildRoot = buildRoot,
            lastSeen = existing?.lastSeen?.coerceAtLeast(now) ?: now,
            gradleVersion = gradleVersion.ifBlank { existing?.gradleVersion ?: "" },
            modules = mergedModules,
        )
        write(merged)
        return merged
    }

    /** Reads the registry for [buildRoot], or null if absent/unreadable. */
    fun read(buildRoot: String): ModuleRegistry? {
        val f = fileFor(buildRoot)
        if (!f.isFile) return null
        return try {
            json.decodeFromString(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Writes [registry] atomically to its file for its build root. */
    fun write(registry: ModuleRegistry) {
        registryDir.mkdirs()
        val target = fileFor(registry.buildRoot)
        val tmp = File(registryDir, "${target.name}.tmp-${ProcessHandle.current().pid()}-${Thread.currentThread().id}")
        try {
            tmp.writeText(json.encodeToString(registry))
            // Atomic replace.
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    /** Deletes the registry file for [buildRoot]. Returns true if a file existed. */
    fun remove(buildRoot: String): Boolean = fileFor(buildRoot).delete()

    /** All currently-registered builds (sorted by buildRoot). */
    fun all(): List<ModuleRegistry> {
        if (!registryDir.isDirectory) return emptyList()
        return registryDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                try {
                    json.decodeFromString<ModuleRegistry>(f.readText())
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedBy { it.buildRoot }
            ?: emptyList()
    }

    /**
     * The union of module coordinates across all registered builds.
     * Returns an empty set if nothing is registered — callers are expected to
     * treat an empty union as "refuse to prune" unless explicitly overridden.
     */
    fun union(): Set<ModuleCoordinate> =
        all().flatMap { it.coordinates }.toSet()
}

private fun sha1(input: String): String {
    val md = MessageDigest.getInstance("SHA-1")
    val bytes = md.digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
