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
- the version-named state dirs under `caches/` (`9.7.1`, `8.5`, …) — those are
  only touched by the separate, opt-in `gradlePruneVersionCaches` step,
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
   so a deleted project's file can be removed with the project
   (`gradle-prune --forget <build-root>`). Writes are atomic (temp file +
   rename) and merges are conservative (union of modules, max `lastSeen`),
   in **both** registrars — the plugin and the init script. An existing entry
   that cannot be parsed is never overwritten: its coordinates would be lost
   silently, so it is reported instead.

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
gradle installDist    # → build/install/gradle-prune/bin/gradle-prune
```

The same distribution serves two roles:

- **plugin** — apply `io.github.qie2035.gradle-prune` to register a build's
  modules (via `includeBuild`, `mavenLocal()`, or a flat-classpath jar);
- **CLI** — the `gradle-prune` binary from `installDist` does the actual
  pruning, with no Gradle daemon involved.

`prune.init.gradle.kts` offers a third, plugin-free way to register a
build (see below).

### Ways to register builds

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
[`prune.init.gradle.kts`](prune.init.gradle.kts) with `-I` — a
self-contained Kotlin-DSL mirror of the plugin's registrar: it hooks the
same `afterResolve` callbacks (project + buildscript configurations) and
writes the same registry file at `buildFinished`. No plugin classes are
loaded, no build file changes needed, and it never forces resolution of a
configuration the build didn't already resolve:

```bash
gradle -I /path/to/prune.init.gradle.kts build
# optional: -Dgradle.prune.registry.dir=/some/dir overrides the registry location
```

The script needs explicit `Action` SAM types and a few receiver/property
workarounds because Gradle's callback APIs are Groovy-Closure-first (plain
Kotlin lambdas resolve to the Closure overloads) — the exact list is in
the file header. It registers the same coordinates the plugin does
(verified: same 23-module set on a real build) and merges them into the
existing entry with the same union / max-`lastSeen` contract
(`RegistryStore.upsert`), so a light build can never shrink a previous
build's entry. Progress is logged at debug level, like the plugin — run
`gradle -i` to see it.

**Global install (auto-registration for every build).** The init script is
also the "set and forget" mode: place it where Gradle auto-loads init
scripts and **every** Gradle build on this machine registers itself — no
per-project configuration, and the union grows to exactly "the builds this
machine actually runs", which is the closest analogue to `pnpm store prune`
semantics. Both locations work (verified on Gradle 9.7.1):

```bash
# a) the init.d/ directory (recommended — coexists with other init files):
mkdir -p ~/.gradle/init.d
cp /path/to/prune.init.gradle.kts ~/.gradle/init.d/gradle-prune.init.gradle.kts
# b) a single root file — a bare ~/.gradle/init.gradle.kts at the root of
#    the Gradle user home is also auto-loaded for every build (only if you
#    have no other root init file, or it conflicts with one):
cp /path/to/prune.init.gradle.kts ~/.gradle/init.gradle.kts
# then restart daemons: gradle --stop
```

Notes:

- **Keep the `.init.gradle.kts` suffix.** Gradle derives a Kotlin script's
  kind from its file name: only `init.gradle.kts` and `*.init.gradle.kts`
  are init scripts, and anything else (e.g. `gradle-prune-init.gradle.kts`)
  falls through to the `*.gradle.kts` project-script match. The build itself
  still works — `init.d/` is scanned by directory — but an IDE's script-model
  build then compiles the file as a project build script and reports
  `Unresolved reference 'initscript'` (plus unresolved references for the
  block's contents and imports) for it. Verified on Gradle 9.7.1; renaming
  the file is the whole fix.
- `init.d/` is a **directory** that Gradle scans automatically; a bare
  `~/.gradle/init.gradle` / `init.gradle.kts` at the root of the Gradle user
  home is **also** loaded automatically.
- Uninstall by removing the file, then `gradle --stop`.
- **Kill switch** (recommended while installed globally): set
  `GRADLE_PRUNE_DISABLE=1` in the environment, or
  `-Dgradle.prune.skip=true` on the command line, to turn the script into a
  complete no-op without deleting it (e.g. temporarily while migrating the
  Gradle user home).

**C. Global plugin install (registration + prune tasks, whole machine).**
The plugin itself can be installed globally the same way — the closest
analogue to `pnpm`'s global behaviour: **every** build on the machine
auto-registers, and the `gradlePruneModules` / `gradlePruneVersionCaches`
tasks are available in **every** project, with no per-project `plugins {}`
entry and no duplicated init-script capture code:

```bash
# one time, in this repo:
gradle publishToMavenLocal
# then install the global init script:
mkdir -p ~/.gradle/init.d
cp /path/to/prune-global.init.gradle.kts \
   ~/.gradle/init.d/gradle-prune.init.gradle.kts
gradle --stop
```

(Keep the `.init.gradle.kts` suffix on the installed file, or the IDE
reports unresolved references for it — see the naming note in B.)

Mechanics: the script's top-level `initscript {}` block puts the plugin jar
(from `mavenLocal()`, transitive dependencies from `mavenCentral()`) on the
init script's own classpath — so the plugin class is a normal compile-time
reference in the script body (no reflection); the script applies it to
every project **by class** via `pluginManager.apply(Class)`, because
plugin-id resolution does not consult the init script classpath (and the
KTS `apply(String)` extension would reject a Class argument). The
`initscript {}` block must stay at the **top** of the script — that is the
only place Gradle wires the init classpath. If the plugin artifact is
missing (e.g. `publishToMavenLocal` was never run), every build fails at
init with a clear dependency-resolution error — an intentional hard
dependency; remove the file to back out. The kill switch works exactly as
above. Uninstall: remove the file + `gradle --stop` (the `~/.m2`
publication can be deleted separately).

Both global mechanisms are safe to stack (the registry upsert is a
conservative union merge), and neither can break the build: every hook is
wrapped in try/catch and only logs.

## Using the CLI

```bash
# Dry run (default) — shows what would be freed, deletes nothing
gradle-prune

# Actually delete
gradle-prune --apply

# Flags
--apply          perform deletions (default is a dry-run preview)
--all            ignore the registry entirely and delete the ENTIRE module
                 cache (the only way to wipe it; also the only way to prune
                 when nothing is registered)
--force          proceed despite a recently-touched build lock (lock < 30 s
                 old) or unreadable registry files
-v, --verbose    full module list + per-dir sizes
--forget ROOT    remove ROOT's registry entry and exit (repeatable); the
                 "I deleted that project" case
--registry DIR   alternate registry dir (default <GRADLE_USER_HOME>/prune/registry)
--modules-dir DIR
                 alternate files-2.1 dir (default <GRADLE_USER_HOME>/caches/modules-2/files-2.1)
```

`$GRADLE_USER_HOME` defaults to `~/.gradle` and is used to locate both the
registry and the cache. Inside a Gradle build the plugin and its tasks use
`Gradle.getGradleUserHomeDir()` instead, so `-g` / `--gradle-user-home` is
honoured there too.

### Optional: version-cache cleanup

Per-Gradle-version state dirs (`caches/9.7.1`, `caches/8.5`, …) are **not**
touched by the core prune. To also drop stale version dirs (kept: the
version currently running):

```bash
gradle-prune --all          # ignore the registry and wipe the module cache
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
| registration (plugin) | `-Pprune.captureDownloads` | `false` |

Boolean flags accept `true/false`, `1/0`, `yes/no`, `on/off` (case-insensitive).

`-Pprune.captureDownloads=true` additionally records every `files-2.1`
directory written during the build (the `MtimeDeltaCapture` safety net). It
covers modules Gradle downloads but never reports as resolved — metadata-only
fetches, settings/`pluginManagement` resolution — at the cost of one cache
scan at build end. Off by default; `-Dgradle.prune.captureDownloads=true`
works too.

Example:

```bash
gradle gradlePruneModules -Pprune.modules.dryRun=false -Pprune.modules.verbose=true
```

## Forgetting a build

Delete the project? Remove its registry entry:

```bash
gradle-prune --forget /abs/build/root      # repeatable
```

The argument is matched against the stored build root (so a relative path or a
trailing slash is fine), and the file that entry was read from is the one
removed. The older `rm ~/.gradle/prune/registry/$(printf %s … | sha1sum | cut -c1-12).json`
recipe still works, and deleting the whole `prune/registry` dir starts fresh —
the next build re-registers itself; an empty union refuses to prune unless
`--all`).

## Safety model

- **Dry-run by default** for both the CLI and the tasks.
- **Empty union refuses** to delete anything unless `--all` (guards against
  a wiped/lost registry).
- **Unreadable registry files** are reported, and block a real deletion
  (`--apply` / `-Pprune.modules.dryRun=false`) unless `--force` /
  `-Pprune.modules.force=true` is passed. A file that cannot be parsed shrinks
  the keep-set, which is the one failure this tool cannot undo; an existing
  entry is also never overwritten while it is unparseable.
- **Recent build lock** (`modules-2.lock` touched < 30 s ago) → non-blocking
  warning, silenced by `--force`.
- **Cache-only deletions**: only `files-2.1/<g>/<n>/<v>` trees, bottom-up,
  with empty-parent cleanup. Metadata, resources, locks, GC state and
  version state dirs are never touched. Symlinks are unlinked, never followed.
- Deletion is verified: the reported freed bytes equal the summed size of
  what was actually removed.

## Building

```bash
gradle build
```

60 tests: 55 unit tests cover the registry store, scanner/planner/executor,
lock guard, version-cache pruner, mtime-delta capture, coordinate parsing and
the resolution-graph walker (against faked Gradle API types), and 5 TestKit
tests drive the real plugin through a real Gradle build —
`PruneTasksIntegrationTest` covers registration from a resolved configuration,
`--all`, the default dry-run and the unreadable-registry refusal, all offline.

The suite is driven by the **system `gradle`** command — run `gradle build`
(the template's `gradlew` wrapper, if present, is not used). The init script is
still verified by hand against a real build.

## Known limitations

- **Capture completeness.** The resolution graph only reports modules that
  appear as a *selected dependency*. Modules fetched for metadata only, or
  resolved through `settings` / `pluginManagement`, are missed unless
  `-Pprune.captureDownloads=true` is on. A miss costs a re-download, never a
  broken build.
- **`Gradle.buildFinished` is deprecated** in Gradle 9. It is used
  deliberately, with the deprecation suppressed and reasoned about in
  `CaptureRegistrar`: in 9.7.1 `GradleLifecycle` has no build-end hook, and
  `FlowScope` / `BuildEventsListenerRegistry` only fire once the *work graph*
  completes — a build that fails during **configuration** would never register
  its already-resolved modules, which is precisely when the registry must stay
  conservative. Registration depends on that one hook; `PruneTasksIntegrationTest`
  would catch it breaking, so the migration can be done safely as a follow-up.
- **The registry union only grows.** A build that stops using a module keeps
  its coordinate until its registry entry is removed (`--forget`, or deleting
  the file after removing the project). That is the conservative direction:
  it can under-prune, never over-prune.

## Project layout

```
src/main/kotlin/io/github/qie2035/gradleprune/
  core/
    ModuleCoordinate.kt      g:n:v value object + parser
    CachePaths.kt            GRADLE_USER_HOME / files-2.1 / registry paths
    Bytes.kt                 shared human-readable byte formatting
    Cli.kt                   Clikt command (gradle-prune)
    capture/
      GraphWalker.kt         ResolutionResult → module coordinates
      MtimeDeltaCapture.kt   opt-in safety-net capture: modules whose
                             files-2.1 dirs were written during a build
                             (mtime delta); -Pprune.captureDownloads=true
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
    GradlePrunePlugin.kt     one registrar per build + registers tasks
    CaptureRegistrar.kt      hooks buildFinished → registry upsert
    PruneGradleCacheTask.kt  gradlePruneModules
    PruneVersionCachesTask.kt gradlePruneVersionCaches
prune.init.gradle.kts        -I init-script registration (Kotlin DSL;
                             see its header for compiler workarounds)
prune-global.init.gradle.kts global plugin install (initscript classpath)
README-zh.md                 中文说明
```
