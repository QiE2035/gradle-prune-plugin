# gradle-prune

中文说明见 [README-zh.md](README-zh.md)。

A Gradle cache-cleaner with the same semantics as `pnpm store prune`:
**every build registers the modules it actually resolved; `prune` keeps the
union of all registered builds and deletes everything else** from the shared
dependency cache.

```
registered builds: 3
  ~/work/android-app  (214 modules)
  ~/work/desktop-app  (98 modules)
  ~/tools/script      (37 modules)

prune: 1342 cached module versions, union of 3 builds = 251 unique
        → 1091 deletable, would free 1.9 GB
```

Unlike `~/.gradle` "nuke the caches" scripts, this never touches:

- `metadata-*` / `resources-*` / `descriptor*` / `gc.properties` / lock files
  (Gradle's bookkeeping; deleting them corrupts the cache),
- the version-named state dir of the Gradle release in use (only with the
  separate `--all` version-cache step),
- any module that at least one registered build still references.

## How it works

1. **Registration.** The plugin `io.github.qie2035.gradle-prune` hooks the
   build's resolved configurations. On each `buildFinished` it writes one
   JSON file per build root into `$GRADLE_USER_HOME/prune/registry/`:

   ```json
   {
     "buildRoot": "/home/me/work/android-app",
     "lastSeen": 1790824794883,
     "gradleVersion": "9.7.1",
     "modules": ["org.slf4j:slf4j-api:2.0.13", "com.google.code.gson:gson:2.11.0", "…"]
   }
   ```

   File name = first 12 hex chars of the SHA-1 of the absolute build root,
   so a deleted project's file can be removed with the project (see
   `forget`). Writes are atomic (temp file + rename) and merges are
   conservative (union of modules, max `lastSeen`).

2. **Prune.** The CLI (or `gradlePruneModules` task) scans
   `caches/modules-2/files-2.1/<group>/<name>/<version>/` and deletes every
   version dir whose coordinate is **not** in the union. Deletion is
   bottom-up (sha1 → version → name → group), removing directories that end
   up empty. Only `files-2.1` is ever modified.

## Installation

The plugin is a Gradle plugin **and** a standalone CLI from the same jar.
Build it with your system Gradle (≥ 9; developed against 9.7.1):

```bash
gradle build          # compile + unit tests
gradle installDist    # → build/install/gradle-prune-plugin/bin/gradle-prune
```

The same distribution serves two roles:

- **plugin** — apply `io.github.qie2035.gradle-prune` to register a build's
  modules (via `includeBuild`, `mavenLocal()`, or a flat-classpath jar);
- **CLI** — the `gradle-prune` binary from `installDist` does the actual
  pruning, with no Gradle daemon involved.

`prune-init.gradle` offers a third, plugin-free way to register a build
(see below).

### Two ways to register builds

**A. Plugin (recommended for app builds).** Put the plugin on the
classpath and apply it:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { mavenCentral() }  // or mavenLocal() if you published there
}
// build.gradle.kts
plugins { id("io.github.qie2035.gradle-prune") }
```

Every build run of that project refreshes its registry entry. Apply it to
every project whose dependencies you want counted in the union.

**B. Init script (zero-build-file approach).** Apply
[`prune-init.gradle`](prune-init.gradle) with `-I` — a self-contained
Groovy mirror of the plugin's registrar: it hooks the same
`afterResolve` callbacks (project + buildscript configurations) and writes
the same registry file at `buildFinished`. No plugin classes are loaded,
no build file changes needed, and it never forces resolution of a
configuration the build didn't already resolve:

```bash
gradle -I /path/to/prune-init.gradle build
# optional: -Dgradle.prune.registry.dir=/some/dir overrides the registry location
```

Both mechanisms are safe to stack (the registry upsert is a conservative
union merge), and an init script cannot break the build: every hook is
wrapped in try/catch and only logs.

## Using the CLI

```bash
# Dry run (default) — shows what would be freed, deletes nothing
gradle-prune

# Actually delete
gradle-prune --apply

# Flags
--apply          perform deletions (default is a dry-run preview)
--all            delete the ENTIRE module cache regardless of the registry
                 (also the only way to prune an empty registry)
--force          ignore a recent build lock warning (lock < 30 s old)
-v, --verbose    full module list + per-dir sizes
--registry DIR   alternate registry dir (default <GRADLE_USER_HOME>/prune/registry)
--modules-dir DIR
                 alternate files-2.1 dir (default <GRADLE_USER_HOME>/caches/modules-2/files-2.1)
```

`$GRADLE_USER_HOME` defaults to `~/.gradle` and is used to locate both the
registry and the cache.

### Optional: version-cache cleanup

Per-Gradle-version state dirs (`caches/9.7.1`, `caches/8.5`, …) are **not**
touched by the core prune. To also drop stale version dirs (kept: the
version currently running):

```bash
gradle-prune --all          # core prune over everything
# plus the dedicated version-cache step (opt-in task when the plugin is
# applied, or call the core directly):
gradle gradlePruneVersionCaches            # dry-run
gradle gradlePruneVersionCaches -Pprune.versions.dryRun=false
```

Version dirs are matched conservatively by the regex
`\d+\.\d+(\.\d+)?(-[\w.-]+)?` (so `8.5`, `9.7-rc-1`), never `jars-9`,
`metadata-2.107`, `modules-2`, `fabric-loom`, …

### Gradle task flags (when the plugin is applied)

| Task | Property | Default |
|---|---|---|
| `gradlePruneModules` | `-Pprune.modules.dryRun` | `true` |
| | `-Pprune.modules.force` | `false` |
| | `-Pprune.modules.all` | `false` |
| | `-Pprune.modules.verbose` | `false` |
| | `-Pprune.modules.modulesDir` | `<GUH>/caches/modules-2/files-2.1` |
| | `-Pprune.modules.registryDir` | `<GUH>/prune/registry` |
| `gradlePruneVersionCaches` | `-Pprune.versions.dryRun` | `true` |
| | `-Pprune.versions.cachesDir` | `<GUH>/caches` |

Boolean flags accept `true/false`, `1/0`, `yes/no`, `on/off` (case-insensitive).

Example:

```bash
gradle gradlePruneModules -Pprune.modules.dryRun=false -Pprune.modules.verbose=true
```

## Forgetting a build

Delete the project? Remove its registry entry:

```bash
rm ~/.gradle/prune/registry/$(printf %s /abs/build/root | sha1sum | cut -c1-12).json
```

(or just delete the whole `prune/registry` dir to start fresh — the next
build re-registers itself; an empty union refuses to prune unless `--all`).

## Safety model

- **Dry-run by default** for both the CLI and the tasks.
- **Empty union refuses** to delete anything unless `--all` (guards against
  a wiped/lost registry).
- **Recent build lock** (`modules-2.metadata-*/**` or any `*.lock` touched
  < 30 s ago) → non-blocking warning, skipped unless `--force`.
- **Cache-only deletions**: only `files-2.1/<g>/<n>/<v>` trees, bottom-up,
  with empty-parent cleanup. Metadata, resources, locks, GC state and
  version state dirs are never touched.
- Deletion is verified: the reported freed bytes equal the summed size of
  what was actually removed.

## Building

```bash
gradle build
```

42 unit tests cover the registry store, scanner/planner/executor, lock
guard, version-cache pruner, coordinate parsing and the resolution-graph
walker (against faked Gradle API types). The test suite is driven by the
**system `gradle`** command — run `gradle build` (the template's `gradlew`
wrapper, if present, is not used).

## Project layout

```
src/main/kotlin/io/github/qie2035/gradleprune/
  core/
    ModuleCoordinate.kt      g:n:v value object + parser
    CachePaths.kt            GRADLE_USER_HOME / files-2.1 / registry paths
    Cli.kt                   Clikt command (gradle-prune)
    capture/
      GraphWalker.kt         ResolutionResult → module coordinates
      MtimeDeltaCapture.kt   safety-net capture: modules whose files-2.1 dirs
                             got written during a build (mtime delta);
                             off by default, not yet wired into a task
    registry/
      ModuleRegistry.kt      per-build JSON record (kotlinx-serialization)
      RegistryStore.kt       read/merge/atomic-write of registry files
    prune/
      CacheScanner.kt        enumerate files-2.1/<g>/<n>/<v> + sizes
      PrunePlanner.kt        cache ∖ union → deletion plan
      PruneExecutor.kt       bottom-up deletion + empty-parent cleanup
      VersionCachePruner.kt  stale per-version state dirs (opt-in)
      LockGuard.kt           recent-build detection
    report/
      ReportRenderer.kt      Mordant-colored terminal report
  plugin/
    GradlePrunePlugin.kt     applies registrar + registers tasks
    CaptureRegistrar.kt      hooks buildFinished → registry upsert
    PruneGradleCacheTask.kt  gradlePruneModules
    PruneVersionCachesTask.kt gradlePruneVersionCaches
prune-init.gradle            -I init-script registration (no plugin needed)
README-zh.md                 中文说明
```
