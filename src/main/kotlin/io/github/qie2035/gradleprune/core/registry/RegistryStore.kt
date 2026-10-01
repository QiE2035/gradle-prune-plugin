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
     *
     * @throws IllegalStateException when an entry for [buildRoot] already
     *   exists but cannot be parsed. Overwriting it would silently drop every
     *   coordinate it held — the next `prune --apply` would then delete
     *   modules a registered build still needs — so the caller must resolve it
     *   (the CLI and the prune tasks refuse to delete while such a file
     *   exists). Callers that must not break a build catch and log this.
     */
    fun upsert(
        buildRoot: String,
        now: Long = System.currentTimeMillis(),
        gradleVersion: String = "",
        modules: Collection<ModuleCoordinate> = emptyList(),
    ): ModuleRegistry {
        val target = fileFor(buildRoot)
        val existing = if (target.isFile) {
            parse(target) ?: throw IllegalStateException(
                "registry file ${target.path} exists but cannot be parsed; refusing to overwrite it " +
                    "(repair or delete it to resume registration)",
            )
        } else {
            null
        }
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
        return parse(f)
    }

    /** Writes [registry] atomically to its file for its build root. */
    fun write(registry: ModuleRegistry) {
        registryDir.mkdirs()
        val target = fileFor(registry.buildRoot)
        val tmp = File(registryDir, "${target.name}.tmp-${ProcessHandle.current().pid()}-${Thread.currentThread().threadId()}")
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

    /**
     * Every registry entry together with the file it was read from.
     *
     * Callers that remove an entry should delete [RegistryEntry.file] rather
     * than call [remove] with the parsed build root: the file name is derived
     * from that root, so a file whose name does not match (hand-copied,
     * renamed) would otherwise never be removed.
     */
    fun entries(): List<RegistryEntry> = registryFiles()
        .mapNotNull { file -> parse(file)?.let { RegistryEntry(file, it) } }

    /** All currently-registered builds (sorted by buildRoot). */
    fun all(): List<ModuleRegistry> = entries()
        .map { it.registry }
        .sortedBy { it.buildRoot }

    /**
     * Registry files that exist but could not be parsed.
     *
     * Callers **must** surface these: a registry file that silently fails to
     * parse shrinks the keep-set, which makes modules that a real build still
     * needs look unused. Deleting on top of that is the one failure mode this
     * tool cannot undo.
     */
    fun unreadable(): List<File> = registryFiles()
        .filter { parse(it) == null }
        .sortedBy { it.name }

    /**
     * The union of module coordinates across all registered builds.
     * Returns an empty set if nothing is registered — callers are expected to
     * treat an empty union as "refuse to prune" unless explicitly overridden.
     */
    fun union(): Set<ModuleCoordinate> =
        all().flatMap { it.coordinates }.toSet()

    private fun registryFiles(): List<File> {
        if (!registryDir.isDirectory) return emptyList()
        return registryDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: emptyList()
    }

    private fun parse(file: File): ModuleRegistry? = try {
        json.decodeFromString<ModuleRegistry>(file.readText())
    } catch (e: Exception) {
        null
    }
}

private fun sha1(input: String): String {
    val md = MessageDigest.getInstance("SHA-1")
    val bytes = md.digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

/** A parsed registry entry and the file it lives in. */
data class RegistryEntry(
    val file: File,
    val registry: ModuleRegistry,
)
