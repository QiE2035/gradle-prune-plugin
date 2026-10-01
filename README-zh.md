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
- 当前正在使用的 Gradle 版本的状态目录（只有可选的 `--all` 版本缓存步骤
  才可能涉及其它版本）；
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
   可以直接删掉对应文件（见下文"注销一个构建"）。写入是原子的
   （临时文件 + rename），合并是保守的（模块取并集、`lastSeen` 取最大值）。

2. **清理（prune）**。CLI（或 `gradlePruneModules` 任务）扫描
   `caches/modules-2/files-2.1/<group>/<name>/<version>/`，删除所有坐标
   **不在并集内**的版本目录。删除自底向上（sha1 → version → name → group），
   并把删空后的父目录一并移除。只修改 `files-2.1`。

## 构建与安装

本仓库同时是一个 Gradle 插件**和一个独立 CLI**，用系统 `gradle`（≥ 9，
开发基于 9.7.1；不使用 `gradlew`）构建：

```bash
gradle build          # 编译 + 单元测试
gradle installDist    # → build/install/gradle-prune-plugin/bin/gradle-prune-plugin
```

- **插件**：把 `io.github.qie2035.gradle-prune` 加到 classpath 并 apply，
  即可登记构建的模块（可用 `includeBuild` 指向本仓库，或发布到
  `mavenLocal()` 后用 `pluginManagement` 引入）；
- **CLI**：`installDist` 产出的 `gradle-prune-plugin` 可执行文件执行真正的
  清理，不经过 Gradle 守护进程；
- **init script**：`prune-init.gradle` 提供第三种、完全不用插件的登记方式
  （见下文）。

### 三种登记方式

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
[`prune-init.gradle`](prune-init.gradle) —— 它是插件注册器的自包含 Groovy
镜像：钩住同样的 `afterResolve` 回调（每个 project + 各自 buildscript 的
configuration），在 `buildFinished` 写同一个注册表文件。不加载任何插件
类、不改构建脚本，也绝不会强制解析构建本来不解析的配置：

```bash
gradle -I /path/to/prune-init.gradle build
# 可选: -Dgradle.prune.registry.dir=/some/dir 覆盖注册表位置
```

**C. mtime 兜底采集**（代码内已备好，默认关闭，尚未接到任务上）：
`MtimeDeltaCapture` 按构建期间 `files-2.1` 下新增/变动的目录反推模块坐标，
可覆盖"下载了但不出现在 `ResolutionResult` 里"的文件（例如某些
buildscript 工件）。

三种方式可叠加使用（注册表 upsert 是保守并集合并），init script 也不会
弄坏构建：所有钩子都包在 try/catch 里，失败只打日志。

## CLI 使用

```bash
# 干跑（默认）—— 只预览将释放多少，不删任何东西
gradle-prune-plugin

# 实际删除
gradle-prune-plugin --apply
```

```
--apply          执行删除（默认是干跑预览）
--all            无视注册表、删除整个模块缓存
                 （也是空注册表时唯一允许 prune 的方式）
--force          忽略"构建锁刚被写过"的警告（锁 < 30 秒新）
-v, --verbose    完整模块列表 + 每个目录的大小
--registry DIR   自定义注册表目录（默认 <GRADLE_USER_HOME>/prune/registry）
--modules-dir DIR
                 自定义 files-2.1 目录（默认 <GRADLE_USER_HOME>/caches/modules-2/files-2.1）
```

`$GRADLE_USER_HOME` 默认为 `~/.gradle`，CLI 用它定位注册表和缓存。

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

布尔属性接受 `true/false`、`1/0`、`yes/no`、`on/off`（单词不区分大小写）。

示例：

```bash
gradle gradlePruneModules -Pprune.modules.dryRun=false -Pprune.modules.verbose=true
```

## 注销一个构建

删掉某个项目后，删掉它的注册表条目即可：

```bash
rm ~/.gradle/prune/registry/$(printf %s /abs/build/root | sha1sum | cut -c1-12).json
```

也可以直接删掉整个 `prune/registry` 目录重新开始（下次构建会重新登记；
空并集会拒绝删除，除非 `--all`）。

## 安全模型

- **默认干跑**：CLI 和任务都是先预览。
- **空并集拒绝删除**：没有任何已登记构建时，除非 `--all` 否则什么都不删
  （防止注册表丢失/清空后误删）。
- **构建锁新鲜度**：`modules-2` 锁文件在 30 秒内被写过 → 发出非阻塞警告
  （可能有构建在跑），`--force` 可静默；删除只动缓存文件，最多触发
  重新下载，不会弄坏构建。
- **只删缓存内容**：只删 `files-2.1/<g>/<n>/<v>` 目录树，自底向上、顺手
  清理空父目录；metadata、resources、锁、GC 状态、版本状态目录一律不碰。
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

42 个单元测试覆盖：注册表存储、扫描/计划/执行器、锁守卫、版本缓存清理器、
坐标解析、resolution 图遍历（对 Gradle API 类型打桩）。测试套件用
**系统 `gradle`** 驱动（模板自带的 `gradlew` wrapper 不使用）。

## 项目布局

```
src/main/kotlin/io/github/qie2035/gradleprune/
  core/
    ModuleCoordinate.kt      g:n:v 值对象 + 解析器
    CachePaths.kt            GRADLE_USER_HOME / files-2.1 / 注册表路径解析
    Cli.kt                   Clikt 命令（CLI 入口）
    capture/
      GraphWalker.kt         ResolutionResult → 模块坐标
      MtimeDeltaCapture.kt   兜底采集：构建期间 files-2.1 新增/变动的
                             模块（mtime 差量）；默认关闭，尚未接到任务
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
prune-init.gradle            -I init-script 登记（无需插件）
README.md                    英文说明
```
