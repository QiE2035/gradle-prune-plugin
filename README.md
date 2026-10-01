# gradle-prune

中文说明见 [README-zh.md](README-zh.md)。

A Gradle cache-cleaner with the same semantics as `pnpm store prune`:
**every build registers the modules it actually resolved; `prune` keeps what
the registered builds that still exist are using and deletes everything else**
from the shared dependency cache.

```
registered builds: 3 — 2 in use, 1 gone
  ~/work/android-app  (214 modules)   in use
  ~/work/desktop-app  (98 modules)    in use
  ~/tools/script      (37 modules)    gone → entry dropped

prune: 1342 cached module versions, in use = 251 unique
        → 1091 deletable, would free 1.9 GB
```

Unlike `~/.gradle` "nuke the caches" scripts, this never touches:

- `metadata-*` / `resources-*` / `descriptor*` / `gc.properties` / lock files
  (Gradle's bookkeeping; deleting them corrupts the cache),
- the version-named state dirs under `caches/` (`9.7.1`, `8.5`, …) — those are
  only touched by the separate, opt-in `gradlePruneVersionCaches` step,
- any module that a registered build which still exists is using.

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

   File name = first 12 hex chars of the SHA-1 of the absolute build root, so a
   deleted project's entry is found — and dropped — by itself (see
   [Stale builds](#stale-builds)); `gradle-prune --forget <build-root>` is the
   manual equivalent. Writes are atomic (temp file + rename) and merges are
   conservative (union of modules, max `lastSeen`), in **both** registrars —
   the plugin and the init script. An existing entry that cannot be parsed is
   never overwritten: its coordinates would be lost silently, so it is
   reported instead.

2. **Prune.** The CLI (or `gradlePruneModules` task) scans
   `caches/modules-2/files-2.1/<group>/<name>/<version>/` and deletes every
   version dir whose coordinate is **not in use**, where

   ```
   in use = referenced by a registered build whose root still exists
   ```

   Deletion is bottom-up (sha1 → version → name → group), removing directories
   that end up empty. Only `files-2.1` is ever modified.

## Stale builds

"The union of everything ever registered" would keep a deleted project's
modules alive forever, so the keep-set is computed from **live** builds only.
A registry entry is judged by one explicit question — *does the recorded build
root still exist?* — with three possible answers, and **no timeout anywhere**:

| Answer | When | Effect |
|---|---|---|
| **in use** | the directory exists, or the path is pinned | its modules are kept |
| **gone** | the directory is verifiably absent | the entry is dropped and its modules become deletable |
| **unverifiable** | the nearest existing ancestor is not readable (unmounted, hung, permission-denied) | kept — an infrastructure problem must never look like a deletion |

Dropping a *gone* entry releases exactly the modules **no surviving build
references**: the keep-set is the union over the entries that remain, so a
module another project still uses can never be released by removing a dead
one. The report says which roots are gone and how much that frees, and
`--verbose` marks the deletions that only a gone build was holding.

A build root can be legitimately absent for a while — an unmounted removable
drive, an offline network share. Pin it and it counts as in use again:

```bash
gradle-prune --keep-root /mnt/usb/app            # CLI, repeatable
gradle gradlePruneModules -Pprune.keepRoots=/mnt/usb/app;/mnt/nas/tool
```

or list the paths, one per line (`#` comments allowed), in
`<registry>/keep-roots.txt` — which is the option that survives a "set and
forget" install. A pin covers the path and everything below it, matched on a
path boundary (`/a` does not pin `/ab`).

Entries are dropped only when a run actually proceeds: a dry run reports what
it would drop, and a run that refuses (see the safety model) changes nothing at
all.

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
                 when nothing is in use)
--force          proceed despite a recently-touched build lock (lock < 30 s
                 old) or unreadable registry files
-v, --verbose    full module list; marks deletions only a gone build used
--forget ROOT    remove ROOT's registry entry and exit (repeatable); no longer
                 needed when the project is really gone — see "Stale builds"
--keep-root DIR  treat DIR (and everything below it) as still in use even when
                 it is absent — an unmounted drive or offline share; also read
                 from <registry>/keep-roots.txt (repeatable)
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
| | `-Pprune.keepRoots` | *(none)* — see [Stale builds](#stale-builds) |
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

A project that is **gone** is dropped automatically: the next prune sees that
its build root no longer exists and removes the entry (see
[Stale builds](#stale-builds)). `--forget` is for the case where you want an
entry gone *now*, without running a prune:

```bash
gradle-prune --forget /abs/build/root      # repeatable
```

The argument is matched against the stored build root (so a relative path or a
trailing slash is fine), and the file that entry was read from is the one
removed. The older `rm ~/.gradle/prune/registry/$(printf %s … | sha1sum | cut -c1-12).json`
recipe still works, and deleting the whole `prune/registry` dir starts fresh —
the next build re-registers itself; an empty keep-set refuses to prune unless
`--all`.

## Safety model

- **Dry-run by default** for both the CLI and the tasks.
- **An empty keep-set refuses** to delete anything unless `--all` (guards
  against a wiped/lost registry, and against every build happening to be
  gone at once).
- **A build root that cannot be inspected is kept**, never dropped: only a
  *verifiably* absent directory is "gone" (see
  [Stale builds](#stale-builds)). Pins cover roots that are absent on purpose.
- **Unreadable registry files** are reported, and block a real deletion
  (`--apply` / `-Pprune.modules.dryRun=false`) unless `--force` /
  `-Pprune.modules.force=true` is passed. A file that cannot be parsed shrinks
  the keep-set, which is the one failure this tool cannot undo; an existing
  entry is also never overwritten while it is unparseable.
- **A refusal changes nothing at all** — no cache content and no registry
  entry.
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

79 tests: 72 unit tests cover the registry store, the build-root probe and the
usage/keep-set computation, the scanner/planner/executor/runner, lock guard,
version-cache pruner, mtime-delta capture, coordinate parsing and the
resolution-graph walker (against faked Gradle API types); 7 TestKit tests drive
the real plugin through real Gradle builds — registration from a resolved
configuration, `--all`, the default dry-run, the unreadable-registry refusal
and the stale-build cleanup (only the gone build's exclusive module freed,
shared modules kept) — all offline, with no network and no `~/.gradle`.

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
- **Within a live build, the entry is still a monotonic union.** Dropping a
  whole build is handled (the root is gone; see
  [Stale builds](#stale-builds)), but a project that still exists and merely
  *changed* its dependencies keeps the old coordinates: upgrading
  `gson:2.10` → `2.11` leaves both pinned until the entry is forgotten, so
  old versions accumulate and prune under-delivers. Fixing that needs
  per-configuration snapshots — replacing what `runtimeClasspath` resolved the
  last time *it* resolved, rather than unioning into one set — because a
  single "the whole build is complete" signal cannot be trusted: a partial
  build (`help`, an early failure, one configuration) must never shrink the
  keep-set when a full one recorded more. Precision here is deliberately
  traded for the guarantee that this tool over-deletes nothing.
- **A build root is identified by its path.** Moving or renaming a project
  registers a second entry under the new path; the old one is dropped on the
  next prune because its path is gone, and the project re-registers itself, so
  the practical effect is a one-time re-download at worst.

## Project layout

```
src/main/kotlin/io/github/qie2035/gradleprune/
  core/
    ModuleCoordinate.kt      g:n:v value object + parser
    CachePaths.kt            GRADLE_USER_HOME / files-2.1 / registry paths
    Format.kt                shared byte/timestamp formatting
    Cli.kt                   Clikt command (gradle-prune)
    capture/
      GraphWalker.kt         ResolutionResult → module coordinates
      MtimeDeltaCapture.kt   opt-in safety-net capture: modules whose
                             files-2.1 dirs were written during a build
                             (mtime delta); -Pprune.captureDownloads=true
    registry/
      ModuleRegistry.kt      per-build JSON record (kotlinx-serialization)
      RegistryStore.kt       read/merge/atomic-write of registry files
      BuildRootProbe.kt      does the build root still exist? (LIVE/STALE/
                             UNKNOWN + keep-roots pins; never time-based)
      RegistryUsage.kt       keep-set = coordinates a live entry references
    prune/
      PruneRunner.kt         the whole decision, shared by CLI + task
      CacheScanner.kt        enumerate files-2.1/<g>/<n>/<v> + sizes
      PrunePlanner.kt        cache ∖ in-use → deletion plan
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
