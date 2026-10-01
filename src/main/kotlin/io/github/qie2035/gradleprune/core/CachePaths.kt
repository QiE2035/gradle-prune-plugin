package io.github.qie2035.gradleprune.core

import java.io.File

/**
 * Resolves the on-disk locations of the Gradle dependency cache and the
 * prune registry.
 *
 * Layout under the Gradle user home (default `~/.gradle`):
 *
 * ```
 * <guy>/
 *   caches/
 *     modules-2/
 *       files-2.1/<group>/<name>/<version>/<sha1>/<file>   <- deletion root
 *       metadata-2.107/                                     (never touched)
 *       resources-2.1/                                      (never touched)
 *       modules-2.lock                                      (only read for lock freshness)
 *       gc.properties                                       (never touched)
 *   prune/
 *     registry/<sha1(buildRoot).take(12)>.json              <- registry
 * ```
 *
 * [filesDir] is the *deletion root* — the only directory the prune engine
 * ever deletes from.
 */
class CachePaths(
    val gradleUserHome: File,
    /** Deletion root, i.e. `modules-2/files-2.1`. */
    val filesDir: File,
    /** Directory holding per-build registry files. */
    val registryDir: File,
) {
    /** The `modules-2` directory (parent of [filesDir]). */
    val modulesDir: File get() = filesDir.parentFile ?: gradleUserHome

    /** The shared-cache lock file; a recent mtime hints an active build. */
    val modulesLock: File get() = File(modulesDir, "modules-2.lock")

    companion object {
        /**
         * Builds a [CachePaths] from explicit overrides or sensible defaults.
         *
         * - [gradleUserHome]: the Gradle user home; default the
         *   `GRADLE_USER_HOME` env var or `~/.gradle`.
         * - [filesDir]: the deletion root (`files-2.1`); default
         *   `<guy>/caches/modules-2/files-2.1`. This is what the CLI's
         *   `--modules-dir` points at.
         * - [registryDir]: the registry directory; default
         *   `<guy>/prune/registry`.
         */
        fun from(
            gradleUserHome: File? = null,
            filesDir: File? = null,
            registryDir: File? = null,
            env: Map<String, String> = System.getenv(),
            homeDir: File = File(System.getProperty("user.home") ?: "."),
        ): CachePaths {
            val guy = (gradleUserHome
                ?: File(env["GRADLE_USER_HOME"] ?: File(homeDir, ".gradle").absolutePath))
                .absoluteFile

            val files = (filesDir
                ?: File(File(File(guy, "caches"), "modules-2"), "files-2.1"))
                .absoluteFile

            val reg = (registryDir ?: File(File(guy, "prune"), "registry")).absoluteFile

            return CachePaths(gradleUserHome = guy, filesDir = files, registryDir = reg)
        }
    }
}
