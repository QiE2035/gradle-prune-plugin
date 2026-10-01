# gradle-prune（中文说明）

一个 `pnpm store prune` 语义的 Gradle 缓存清理工具：**每个构建登记自己实际解析过的模块，`prune` 保留所有已登记构建的并集，把其余模块从共享依赖缓存里删掉。**

```
已登记构建: 3
  ~/work/android-app  (214 个模块)
  ~/work/desktop-app  (98 个模块)
  ~/tools/script      (37 个模块)

prune: 缓存中 1342 个模块版本, 3 个构建的并集 = 251 个唯一模块
       → 可删除 1091 个, 预计释放 1.9 GB
```

与"直接清掉 `~/.gradle` 缓存"的做法不同，本工具**永不触碰**：

- `metadata-*` / `resources-*` / `descriptor*` / `gc.properties` / 锁文件
  （Gradle 的记账数据，删了会损坏缓存）；
- `caches/` 下按版本命名的状态目录（`9.7.1`、`8.5`…）——只有另外那个
  可选步骤 `gradlePruneVersionCaches` 才会涉及；
- 任何仍被至少一个已登记构建引用的模块。

## 工作原理

1. **登记（registration）**。插件 `io.github.qie2035.gradle-prune` 钩住构建
   的已解析配置（resolution）。每次 `buildFinished` 时向
   `$GRADLE_USER_HOME/prune/registry/` 写入一个按构建根目录划分的 JSON 文件：

   ```json
   {
     "buildRoot": "/home/me/work/android-app",
     "lastSeen": 1790824794883,
     "gradleVersion": "9.7.1",
     "modules": ["org.slf4j:slf4j-api:2.0.13", "com.google.code.gson:gson:2.11.0", "…"]
   }
   ```

   文件名 = 构建根目录绝对路径 SHA-1 的前 12 位十六进制，因此删掉项目后
   可以直接删掉对应文件（`gradle-prune --forget <构建根目录>`）。写入是原子的
   （临时文件 + rename），合并是保守的（模块取并集、`lastSeen` 取最大值）——
   **插件和 init script 两条路径都是如此**。已存在但解析失败的文件绝不会被
   覆盖：那会静默丢掉它记录的所有坐标，因此工具会把它报出来。

2. **清理（prune）**。CLI（或 `gradlePruneModules` 任务）扫描
   `caches/modules-2/files-2.1/<group>/<name>/<version>/`，删除所有坐标
   **不在并集内**的版本目录。删除自底向上（sha1 → version → name → group），
   并把删空后的父目录一并移除。只修改 `files-2.1`。

## 构建与安装

本仓库同时是一个 Gradle 插件**和一个独立 CLI**，用系统 `gradle`（≥ 9，
开发基于 9.7.1；不使用 `gradlew`）构建：

```bash
gradle build          # 编译 + 单元测试
gradle installDist    # → build/install/gradle-prune/bin/gradle-prune
```

- **插件**：把 `io.github.qie2035.gradle-prune` 加到 classpath 并 apply，
  即可登记构建的模块（可用 `includeBuild` 指向本仓库，或发布到
  `mavenLocal()` 后用 `pluginManagement` 引入）；
- **CLI**：`installDist` 产出的 `gradle-prune` 可执行文件执行真正的
  清理，不经过 Gradle 守护进程；
- **init script**：`prune.init.gradle.kts` 提供一种完全不用插件的登记方式
  （见下文；它同样支持全局安装，还有把插件本身全局加载的
  `prune-global.init.gradle.kts` 脚本）。

### 登记方式

**A. 插件（应用构建推荐）。** 把插件加进 classpath 并 apply：

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { mavenCentral() }  // 或 mavenLocal()（如果发布到了本地仓库）
    // 或直接 includeBuild 本仓库
}
// build.gradle.kts
plugins { id("io.github.qie2035.gradle-prune") }
```

该项目的每次构建都会刷新它的注册表条目。想被计入并集的每个项目都应 apply
此插件（多项目可用 `subprojects { apply(plugin = "io.github.qie2035.gradle-prune") }`）。

**B. init script（零构建脚本改动）。** 用 `-I` 应用
[`prune.init.gradle.kts`](prune.init.gradle.kts) —— 它是插件注册器的
自包含 Kotlin DSL 镜像：钩住同样的 `afterResolve` 回调（每个 project +
各自 buildscript 的 configuration），在 `buildFinished` 写同一个注册表
文件。不加载任何插件类、不改构建脚本，也绝不会强制解析构建本来不解析
的配置：

```bash
gradle -I /path/to/prune.init.gradle.kts build
# 可选: -Dgradle.prune.registry.dir=/some/dir 覆盖注册表位置
```

由于 Gradle 的回调 API 是 Groovy-Closure-first 的（纯 Kotlin lambda 会
被解析到 Closure 重载而编译失败），脚本需要显式 `Action` SAM 类型和若干
receiver/属性技巧——完整列表见文件头注释。登记内容与插件逐字节等价
（实测同一构建各登记 23 个模块、集合相同）。

**全局安装（每次构建自动登记）**。init script 同时也是"一劳永逸"模式：
把它放到 Gradle 自动加载 init 脚本的位置，**本机所有 Gradle 构建**都会
自动登记自己——不需要任何 per-project 配置，union 自然长成"这台机器
实际跑过的构建"，这正是最接近 `pnpm store prune` 语义的用法。两个位置
都生效（已在 Gradle 9.7.1 上实测验证）：

```bash
# a) init.d/ 目录（推荐——与其他 init 文件共存）：
mkdir -p ~/.gradle/init.d
cp /path/to/prune.init.gradle.kts ~/.gradle/init.d/gradle-prune.init.gradle.kts
# b) 单个根文件——~/.gradle/ 根下的 init.gradle.kts 同样会被自动加载
#    （仅当没有其他根 init 文件，或与其不冲突时）：
cp /path/to/prune.init.gradle.kts ~/.gradle/init.gradle.kts
# 然后重启守护进程：gradle --stop
```

注意：

- **安装后的文件名必须保留 `.init.gradle.kts` 后缀。** Gradle 依据文件名
  判定 Kotlin 脚本的种类：只有 `init.gradle.kts` 和 `*.init.gradle.kts` 是
  init script，其他名字（如 `gradle-prune-init.gradle.kts`）会落到
  `*.gradle.kts` 的「项目构建脚本」匹配上。构建本身照常工作（`init.d/`
  是按目录扫描的），但 IDE 的 script model 构建会把该文件当作项目构建脚本
  编译，于是为它报出 `Unresolved reference 'initscript'`，以及块内
  `classpath` 与插件 import 的未解析引用。已在 Gradle 9.7.1 上复现验证；
  改名即可修复。
- `init.d/` 是一个**目录**，Gradle 会自动扫描其中所有脚本；直接放在
  `~/.gradle/` 根下的单个 `init.gradle` / `init.gradle.kts` **同样会被
  自动加载**（两种位置都生效，已在 Gradle 9.7.1 上实测验证）。
- 卸载 = 删掉该文件，然后 `gradle --stop`。
- **关闭开关**（全局安装后建议保留这个能力）：设环境变量
  `GRADLE_PRUNE_DISABLE=1`，或命令行加 `-Dgradle.prune.skip=true`，
  即可不删文件地让脚本完全空转（例如迁移 Gradle 用户主目录期间）。

**C. 插件全局安装（登记 + 清理任务，整机生效）**。插件本身也可以用
同样的方式全局安装——最接近 `pnpm` 全局行为的用法：**本机所有构建**
自动登记，且 `gradlePruneModules` / `gradlePruneVersionCaches` 任务在
**每个项目**里都可用，无需任何 per-project 的 `plugins {}` 配置，也无需
重复实现一遍捕获逻辑：

```bash
# 一次性，在本仓库内：
gradle publishToMavenLocal
# 然后安装全局 init 脚本：
mkdir -p ~/.gradle/init.d
cp /path/to/prune-global.init.gradle.kts \
   ~/.gradle/init.d/gradle-prune.init.gradle.kts
gradle --stop
```

（安装后的文件名要保留 `.init.gradle.kts` 后缀，否则 IDE 会对它报未解析
引用——详见 B 中的命名说明。）

原理：脚本顶层的 `initscript {}` 块把插件 jar（来自 `mavenLocal()`，
传递依赖来自 `mavenCentral()`）放进 init 脚本自己的 classpath——插件类
在脚本体里就是普通的编译期引用（不需要反射）；脚本通过
`pluginManager.apply(Class)` **按类** apply 到每个 project，因为按 id
解析不看 init script 的 classpath（且 KTS 的 `apply(String)` 扩展会拒绝
Class 实参）。`initscript {}` 块必须留在脚本**顶部**——只有那里 Gradle
才会接上 init classpath。插件构件缺失（例如没跑过
`publishToMavenLocal`）时，所有构建会在 init 阶段以清晰的依赖解析错误
失败——有意的硬依赖，删掉该文件即可退出。关闭开关与上面完全相同。
卸载 = 删掉文件 + `gradle --stop`（`~/.m2` 里的发布产物可另行删除）。

**D. mtime 兜底采集**（可选，默认关闭，已接入登记流程）：
打开 `-Pprune.captureDownloads=true`（或 `-Dgradle.prune.captureDownloads=true`）
后，`MtimeDeltaCapture` 按构建期间 `files-2.1` 下被写过的目录反推模块坐标，
覆盖"下载了但不出现在 `ResolutionResult` 里"的文件（例如只取 metadata 的
抓取、settings / `pluginManagement` 解析）。

上述方式可叠加使用（插件和 init script 的注册表写入都是保守并集合并），
两条路径也都不会弄坏构建：所有钩子都包在 try/catch 里，失败只打日志。

## CLI 使用

```bash
# 干跑（默认）—— 只预览将释放多少，不删任何东西
gradle-prune

# 实际删除
gradle-prune --apply
```

```
--apply          执行删除（默认是干跑预览）
--all            完全无视注册表，删除整个模块缓存
                 （也是空注册表时唯一允许 prune 的方式）
--force          忽略"构建锁刚被写过"的警告（锁 < 30 秒新），
                 或忽略无法解析的注册表文件
-v, --verbose    完整模块列表 + 每个目录的大小
--forget ROOT    删除 ROOT 对应的注册表条目后退出（可重复）；
                 "我把那个项目删了"的场景
--registry DIR   自定义注册表目录（默认 <GRADLE_USER_HOME>/prune/registry）
--modules-dir DIR
                 自定义 files-2.1 目录（默认 <GRADLE_USER_HOME>/caches/modules-2/files-2.1）
```

`GRADLE_USER_HOME` 默认是 `~/.gradle`，用于定位注册表和缓存。在 Gradle
构建内部，插件和两个任务改用 `Gradle.getGradleUserHomeDir()`，因此
`-g` / `--gradle-user-home` 同样生效。

> 说明：本工具默认**干跑**，用 `--apply` 才真正删除（与 `pnpm store prune`
> 的 `--dry-run` 开关方向相反，但安全性等价：不显式确认就不会删）。

### 可选：版本缓存清理

按 Gradle 版本命名的状态目录（`caches/9.7.1`、`caches/8.5`、…）**不在**
核心 prune 范围内。要清理过期的版本目录（保留当前正在运行的版本）：

```bash
# 单独清理版本目录（插件已 apply 时的专用任务）
gradle gradlePruneVersionCaches                       # 干跑
gradle gradlePruneVersionCaches -Pprune.versions.dryRun=false
```

版本目录用保守的正则 `\d+\.\d+(\.\d+)?(-[\w.-]+)?` 匹配（所以 `8.5`、
`9.7-rc-1` 算），`jars-9`、`metadata-2.107`、`modules-2`、`fabric-loom` 等
工具目录永不误伤。

### 任务属性（插件 apply 后）

| 任务 | 属性 | 默认 |
|---|---|---|
| `gradlePruneModules` | `-Pprune.modules.dryRun` | `true` |
| | `-Pprune.modules.force` | `false` |
| | `-Pprune.modules.all` | `false` |
| | `-Pprune.modules.verbose` | `false` |
| | `-Pprune.modules.modulesDir` | `<GUH>/caches/modules-2/files-2.1` |
| | `-Pprune.modules.registryDir` | `<GUH>/prune/registry` |
| `gradlePruneVersionCaches` | `-Pprune.versions.dryRun` | `true` |
| | `-Pprune.versions.cachesDir` | `<GUH>/caches` |
| 登记（插件） | `-Pprune.captureDownloads` | `false` |

布尔属性接受 `true/false`、`1/0`、`yes/no`、`on/off`（单词不区分大小写）。

`-Pprune.captureDownloads=true` 会额外登记构建期间在 `files-2.1` 下被写过的
目录（即 `MtimeDeltaCapture` 兜底采集）。它覆盖 Gradle 下载了但从未作为
"已解析依赖"上报的模块（只取 metadata 的抓取、settings /
`pluginManagement` 解析等），代价是构建结束时多扫一遍缓存。默认关闭；
`-Dgradle.prune.captureDownloads=true` 同样可用。

示例：

```bash
gradle gradlePruneModules -Pprune.modules.dryRun=false -Pprune.modules.verbose=true
```

## 注销一个构建

删掉某个项目后，删掉它的注册表条目即可：

```bash
gradle-prune --forget /abs/build/root      # 可重复
```

参数与注册表里存的构建根目录匹配（相对路径、结尾多一个 `/` 都能对上），
删除的是真正读到的那个文件。原来的
`rm ~/.gradle/prune/registry/$(printf %s … | sha1sum | cut -c1-12).json`
配方依然有效；也可以直接删掉整个 `prune/registry` 目录重新开始（下次构建会
重新登记；空并集会拒绝删除，除非 `--all`）。

## 安全模型

- **默认干跑**：CLI 和任务都是先预览。
- **空并集拒绝删除**：没有任何已登记构建时，除非 `--all` 否则什么都不删
  （防止注册表丢失/清空后误删）。
- **无法解析的注册表文件会被报出来**，并且在真正删除时
  （`--apply` / `-Pprune.modules.dryRun=false`）直接拒绝，除非显式
  `--force` / `-Pprune.modules.force=true`。解析失败会缩小保留集，这是本
  工具唯一无法挽回的失败模式；已存在但解析失败的条目也绝不会被覆盖。
- **构建锁新鲜度**：`modules-2.lock` 在 30 秒内被写过 → 发出非阻塞警告
  （可能有构建在跑），`--force` 可静默；删除只动缓存文件，最多触发
  重新下载，不会弄坏构建。
- **只删缓存内容**：只删 `files-2.1/<g>/<n>/<v>` 目录树，自底向上、顺手
  清理空父目录；metadata、resources、锁、GC 状态、版本状态目录一律不碰。
  符号链接只解除链接本身，绝不跟进。
- **删除有对账**：报告的释放字节数 = 实际删除内容的总大小。

## 实测效果（本机验证数据）

- 真机 `~/.gradle`（994 MB 模块缓存、802 个模块版本）：干跑预览
  "可删 733 个版本、871 MB" → `--apply` 实测释放 871 MB（1000M → 125M，
  耗时 0.7 秒）→ 之后 `gradle build` 与强制重编译全部成功、无重新下载。
- 临时 `GRADLE_USER_HOME` 全链路：插件登记 → CLI `--apply` 只删未登记
  模块（65/66 个版本）→ 已登记模块（jar + pom）完整保留 → 二次干跑零删除。

## 构建与测试

```bash
gradle build
```

55 个单元测试覆盖：注册表存储、扫描/计划/执行器、锁守卫、版本缓存清理器、
mtime 差量采集、坐标解析、resolution 图遍历（对 Gradle API 类型打桩）。
测试套件用**系统 `gradle`** 驱动（模板自带的 `gradlew` wrapper 不使用）。
插件 / init script 这两半目前靠真实构建手工验证，还没有自动化集成测试。

## 已知限制

- **采集完整性**：resolution 图只能看到作为"已选依赖"出现的模块。只为取
  metadata 而抓取、或经由 `settings` / `pluginManagement` 解析的模块会漏掉，
  除非打开 `-Pprune.captureDownloads=true`。漏掉的代价是重新下载，绝不会
  弄坏构建。
- **`Gradle.buildFinished` 在 Gradle 9 已废弃**。它仍是唯一同时适用于普通
  插件和 init script 的构建结束钩子，因此仍在使用（代码里显式抑制了该
  弃用警告并注明原因）。迁移到 `BuildEventsListenerRegistry` / `FlowScope`
  是已记录的后续工作；在那之前登记依赖这个钩子。
- **注册表并集只增不减**：某个构建不再使用某模块后，它的坐标会一直保留，
  直到手动移除该条目（`--forget`，或删掉项目后删文件）。这是保守方向：
  可能少删，绝不会多删。

## 项目布局

```
src/main/kotlin/io/github/qie2035/gradleprune/
  core/
    ModuleCoordinate.kt      g:n:v 值对象 + 解析器
    CachePaths.kt            GRADLE_USER_HOME / files-2.1 / 注册表路径解析
    Bytes.kt                 共享的字节数格式化
    Cli.kt                   Clikt 命令（CLI 入口）
    capture/
      GraphWalker.kt         ResolutionResult → 模块坐标
      MtimeDeltaCapture.kt   可选兜底采集：构建期间 files-2.1 被写过的
                             模块（mtime 差量）；
                             -Pprune.captureDownloads=true 开启
    registry/
      ModuleRegistry.kt      每构建一条 JSON 记录（kotlinx-serialization）
      RegistryStore.kt       注册表文件的读取/合并/原子写入
    prune/
      CacheScanner.kt        枚举 files-2.1/<g>/<n>/<v> 及大小
      PrunePlanner.kt        缓存 ∖ 并集 → 删除计划
      PruneExecutor.kt       自底向上删除 + 空父目录清理
      VersionCachePruner.kt  过期版本状态目录（可选）
      LockGuard.kt           "构建正在跑"检测
    report/
      ReportRenderer.kt      Mordant 彩色终端报告
  plugin/
    GradlePrunePlugin.kt     应用注册器 + 注册任务
    CaptureRegistrar.kt      钩住 buildFinished → 注册表 upsert
    PruneGradleCacheTask.kt  gradlePruneModules
    PruneVersionCachesTask.kt gradlePruneVersionCaches
prune.init.gradle.kts        -I init-script 登记（Kotlin DSL；头注释列出
                             编译所需的工作区技巧）
prune-global.init.gradle.kts 插件全局安装（initscript classpath）
README.md                    英文说明
```
