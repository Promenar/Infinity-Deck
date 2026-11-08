# Infinity Workbench 跨端适配规划：Compose Multiplatform，首发 macOS

> 日期：2026-11-07
> 状态：D1–D8 已经用户决策，计划可执行；本文件只含调研与规划，没有改业务代码或依赖
> 基线：`main` @ `3a84fe6d9`，版本 0.34.0-beta（versionCode 94）
> 标记约定：**【事实】** 已从本仓库源码、本机系统或官方文档、原始仓库核验；**【推断】** 基于事实的判断，尚未实测；**【待确认】** 需要 PoC 实测。

---

## 1. 结论

1. **macOS 走 Compose Multiplatform 的桌面目标。** 它在 Kotlin 里是 `jvm("desktop")` 目标，界面由 Skiko（Skia 的 Kotlin 绑定）绘制，运行在随包附带的 JVM 上。数据层依赖的 OkHttp、JSch、BouncyCastle、Apache POI、PDFBox、`ProcessBuilder` 都能在桌面 JVM 上原样运行。
2. **先在 `main` 上升级依赖，同时在分支上做 PoC。** Room 2.8.5、Lifecycle 2.11.0、Coil 3.6.3、markdown-renderer 0.46.0、quickjs-kt KMP 构件这些升级本身对 Android 有价值，也是 KMP 的前提，逐项升级、逐项跑门禁。PoC 同时验证这些库的 JVM 构件能不能用。
3. **模块路线：先整体搬进一个 KMP 模块，再逐步上浮，最后按需细拆。** 除风味、NDK、BuildConfig、Manifest 和应用入口外，`:app` 的源码和测试整体搬进 `:shared`（`com.android.kotlin.multiplatform.library`），起初全部放在 `androidMain`。源码契约测试、CI 脚本和门禁命令只改一次。之后逐文件把纯 Kotlin 代码上浮到 `commonMain` 或 JVM 共享源码集。细粒度组件化等桌面跑通后，再按构建耗时和边界需要拆分。`:app` 保留为 Android 应用壳。
4. **界面以共享为主，平台差异集中在少数查看器。** 设计系统、主题令牌、组件和绝大部分动画放进 `commonMain`。AGSL 光效经 expect/actual 在桌面改用 Skia RuntimeEffect，不做降级。只有网页与产物查看、终端两类查看器需要平台实现；diff、代码、文档查看优先用 Compose 共享实现。桌面在交互上侧重查看与浏览：多栏、标签页、快捷键、悬停和右键菜单。
5. **Agent 命令在宿主 shell 上跑，用 Seatbelt 沙盒约束。** Codex CLI 和 Claude Code 都用 macOS 自带的 `sandbox-exec` 加 Seatbelt 配置文件来限制命令能读写的路径和网络。需要完整 Linux 隔离时，再接入 Apple `container`（macOS 26、Apple 芯片）。桌面不移植 proot，也不移植 Termux：宿主终端加系统自带的权限隔离已经能给 Agent 提供执行环境，不再需要虚拟环境。
6. **macOS 首发功能集**：会话（云端提供商）、提供商与模型管理、本地文件夹工作区、Seatbelt 沙盒里的宿主 shell 与终端、网页与产物查看、diff、代码与文档查看、Markdown（含公式与图表）、SSH、知识库检索、设置、钥匙串密钥。之后再做：Computer Use（对应 Android 的 PhoneUse）、本地推理、Python 运行时、Media3 替代方案、分享导入。桌面不内置 Tailnet，直接用系统 Tailscale。
7. **开发期不做正式签名分发。** 开发期用本机未签名或临时签名的构建。Developer ID 签名、公证和官网分发放到最后阶段。
8. **增量备份与恢复是桌面与手机互导的前置专项。** 现有备份是分层的全量流式备份；增量备份做好后，同步也就完成了一大半。

## 2. 用户决策（D1–D8）

| 编号 | 决策 | 用户结论 | 落到计划里的内容 |
|---|---|---|---|
| D1 | 桌面运行时 | 同意走 CMP 桌面分支 | `jvm("desktop")` + Skiko；不做 Kotlin/Native macOS |
| D2 | 首发功能集 | 同意，并补充三点：桌面用 macOS 成熟的沙盒方案代替 proot；PhoneUse 在桌面改名 Computer Use；桌面不内置 Tailscale，因为桌面上 Tailscale 能与 VPN 共存 | 首发功能集见结论 6；沙盒见 5.4；Computer Use 见 5.5；Tailnet 归入"桌面不做" |
| D3 | 界面复用方式 | 桌面和 Android 的界面侧重点不同，但整体风格和大部分动画应该复用；桌面要强调查看浏览（diff、网页、产物、代码、文档、状态） | 设计系统和动画放 `commonMain`；平台差异只在查看器（5.6）；桌面交互侧重见 5.7；网页与产物查看进首发 |
| D4 | 分发 | 方向同意；以后会建官网，开发期不考虑正式签名证书 | 开发期本机构建；签名、公证、官网分发在 P6 |
| D5 | 执行环境 | 同意直接用宿主终端 | 宿主 zsh + PTY + Seatbelt |
| D6 | 依赖升级 | 同意升级，并要求提前到最前面验证 | P0-A，在 `main` 上与 PoC 并行 |
| D7 | 数据互通 | 同意；重点是增量备份与恢复 | P0-C 增量备份与恢复专项 |
| D8 | 模块路线 | 要求比较"先完整移植再拆"和"边移植边拆"的效率 | 推荐先整体搬入单一 KMP 模块，对比见第 6 节 |

## 3. 现状盘点【事实】

### 3.1 工程结构

- `native-ui/settings.gradle.kts` 只有 `:app`、`:mainactivity-e2e`、`:blackbox-fixture` 三个模块，后两个是测试模块。`buildSrc` 里是 Linux 运行时、打包 Python、Tailnet 的构建任务（Java）。
- 工具链：Kotlin `2.4.10`、AGP `9.2.1`、KSP `2.3.2`、`compileSdk 37`、`minSdk 31`、`targetSdk 36`、Compose BOM `2026.06.00`。
- 源码规模：`main` 有 1476 个 `.kt` 文件、290,907 行；`test` 有 1186 个文件、197,523 行，其中 201 个用 Robolectric、247 个导入 `android.*`；`androidTest` 有 136 个；`screenshotTest` 有 81 个。
- 风味：`distribution` 维度分 `play`、`sideload`。风味专属的 Kotlin 源码只有 `RealPathMountSupport.kt` 一对，另有 `testPlay`、`testSideload` 各 2 个测试文件。sideload 额外打包 proot/Alpine 运行时、CPython、Tailnet 可执行文件和 llama.cpp（JNI，`infinity_llama`）。`res/values` 和 `res/values-zh-rCN` 各约 1550 条字符串。
- `BuildConfig` 在 19 个文件里引用，包括 `InfinityApplication`、`MainActivity`、`DistributionCapabilities`、`InfinityLogger`、`ProviderRequestRouter`、`BackupRuntime` 等。
- 依赖注入：没有用 Hilt 或 Koin。`InfinityApplication.kt`（3335 行）用 160 个 `by lazy` 手写组装全部服务。ViewModel 有 37 个，其中 2 个继承 `AndroidViewModel`，多数通过构造参数拿到 `Application`。
- 可见性：`main` 里有 3247 处 `internal` 声明，分布在 759 个文件中。
- `ui/chat` 里混有业务逻辑：`ChatViewModel.kt` 有 3778 行，`ui/chat/manager/skills` 下是工具实现。

### 3.2 按包统计的 Android 依赖分布

统计口径：一个文件只要有一行 `import android.*` 或 `androidx.*`，就算依赖。"不依赖 Android"指既不导入 `android.*` 也不导入任何 `androidx.*`；"可直接进 commonMain"指在此基础上也不导入 `java.*`、`javax.*` 和 OkHttp。同包内的隐式引用没有计入，所以是近似值。

| 层 | 文件 | 行数 | 不依赖 Android | 可直接进 commonMain | 导入 `android.*` | 导入 Room |
|---|---:|---:|---:|---:|---:|---:|
| `domain` | 90 | 6,156 | 88 | 70 | 2 | 0 |
| `data` | 676 | 111,425 | 467 | 236 | 98 | 112 |
| `ui` | 633 | 159,481 | 199 | 148 | 139 | 1 |
| `background` | 23 | 2,204 | 7 | 6 | 16 | 0 |
| 其它（navigation、share、startup 等） | 54 | 11,641 | 27 | 14 | 20 | 1 |
| **合计** | **1476** | **290,907** | **788** | **474** | **275** | **114** |

`ui` 下有 373 个 Compose 文件。其中 213 个不碰 `android.*`、`LocalContext`、`LocalConfiguration`、`AndroidView`，也不用 `activity`、`core`、`webkit`、`media3`；这 213 个里又有 87 个连 `R` 也不用。

### 3.3 Android 专有能力与 macOS 对应方案

"归类"一栏的含义：**共享** = 直接进共享模块；**接口** = 共享接口加各平台实现；**查看器** = 平台各自实现的查看组件（见 5.6）；**桌面不做** = macOS 不实现；**后续** = 不进首发。

| 能力 | 当前实现（文件数） | macOS 方案 | 归类 |
|---|---|---|---|
| 数据库 | Room `2.7.0-alpha11` + KSP；38 个文件声明 `@Entity`，32 个文件声明 `@Dao`；schema v36；10 个文件用 `SupportSQLiteDatabase`，20 处 `migrate`，35 个文件用 `withTransaction` | 升到 Room 2.8.5（KMP，JVM 端用 `BundledSQLiteDriver`）。迁移函数改用 `SQLiteConnection` 签名，`withTransaction` 改为 `useWriterConnection`，非 Android 目标的 DAO 必须是 `suspend` | 共享（需改造） |
| 键值配置 | 66 个文件用 SharedPreferences；`datastore-preferences:1.0.0` 已声明，但没有任何导入 | 抽象为共享接口 `KeyValueStore`。Android 继续用 SharedPreferences 实现，桌面用 DataStore 1.3.0 `preferences-core` | 接口 |
| 后台任务 | 没有 WorkManager；14 个文件用前台服务，4 个用 JobScheduler，2 个用 AlarmManager | 桌面靠进程常驻：关窗口后缩到托盘继续运行，定时任务用协程调度 | 接口 |
| Context 与文件 | 103 个文件注入 `Context`；8 个文件用 SAF；109 个文件用 `Uri` | 共享接口 `AppDirectories`、`DocumentPicker`。桌面数据放在 `~/Library/Application Support/Infinity Workbench`，选文件夹用原生对话框 | 接口 |
| 执行环境 | proot + Alpine（`domain/linux`、`data/linux` 共 47 个文件），用 `ProcessBuilder` 启动 | 宿主 zsh + PTY + Seatbelt 沙盒（5.4）；可选接入 Apple `container` | 接口 |
| 终端界面 | Termux `terminal-emulator`/`terminal-view` 0.118.0（8 个文件）；`terminal-view` 是 Android View | 桌面不用 Termux，改用宿主 PTY 加桌面终端组件（5.6）；Termux 留在 `:app` 只服务 Android | 查看器 |
| Tailnet | Go tsnet 编译成可执行文件，打进 jniLibs，用 `ProcessBuilder` 启动 | 用系统安装的 Tailscale，桌面 SSH 直接连 tailnet 地址 | 桌面不做 |
| 本地推理 | llama.cpp JNI（12 个文件），CMake 构建 | JNI 接口可以复用；macOS 上编译 Metal 版 dylib【推断】 | 后续 |
| Python 运行时 | 官方 CPython Android 包 | 使用系统 python3 或 python-build-standalone【待确认】 | 后续 |
| 网络 | OkHttp 4.12 + okhttp-sse；Ktor 2.3.7（android、okhttp 两个引擎） | 桌面 JVM 上 OkHttp 可原样使用，不必为 macOS 迁到 Ktor | 共享（JVM 共享源码集） |
| SSH | JSch 2.28.8 + BouncyCastle | 纯 JVM，可原样使用 | 共享（JVM 共享源码集） |
| 文档解析 | `pdfbox-android`、Apache POI 5.2.5、jsoup | 桌面换成原版 Apache PDFBox；POI 和 jsoup 原样使用 | 接口（PDF）+ 共享 |
| JS 运行时 | `quickjs-kt-android:1.0.15` | 换成 `io.github.dokar3:quickjs-kt` KMP 构件（README 写明支持 JVM） | 共享（需升级） |
| Markdown | `org.jetbrains:markdown` 0.7.3 + mikepenz `multiplatform-markdown-renderer` 0.41.0 | 该库 README 写明支持 Desktop（JVM），升级到 0.46.0 | 共享 |
| WebView 富内容 | 20 个文件：KaTeX 行内公式、Mermaid、ECharts、HTML 产物、画布、Agent 浏览器、导出渲染 | 网页与产物查看器（5.6），进首发 | 查看器 |
| diff、代码、文档查看 | 已有差异视图组件、代码高亮（markdown-renderer-code）和文档查看页 | Compose 共享实现，桌面加多栏和并排对比布局 | 共享 |
| 图片 | Coil 3.0.0（含 `coil-video`、`coil-svg`） | Coil 3 是 Compose Multiplatform 库，升级到 3.6.3；`coil-video` 只给 Android 用【推断】 | 共享 |
| 媒体播放 | Media3 1.11.1 | 桌面先调用系统播放器打开 | 后续 |
| 通知 | 12 个文件用 `NotificationCompat`/`NotificationManager` | Compose Desktop 的 `Tray` + `TrayState.sendNotification()`；官方注明 macOS 上要打包后才会显示 | 接口 |
| 振动 | 2 个文件 | 桌面为空实现 | 接口 |
| 安全存储 | `AndroidKeystoreSecretStore`（5 个文件用 Keystore）；`BiometricPrompt` | macOS 钥匙串：java-keyring（JNA）或调用 `security` 命令。开发期构建身份不稳定，钥匙串访问控制以签名构建为准 | 接口 |
| 安全窗口 | `FLAG_SECURE`（`SecureWindowEffect`） | 首发为空实现，后续评估 macOS 窗口共享限制 | 接口 |
| 剪贴板、Toast、Intent | `ClipboardManager`；43 个文件用 `Intent` | 剪贴板用 Compose 公共 API；Intent 统一收到 `PlatformActions` 接口（打开链接、分享、打开文件） | 接口 |
| 分享导入 | Android 分享 Intent（`share/*`） | 桌面用拖放和"打开方式" | 后续 |
| PhoneUse / Computer Use | 计划中，尚未落地 | 桌面称 Computer Use，见 5.5 | 后续 |
| 依赖注入 | 手写服务定位器 | 拆成共享 `AppGraph` 加平台模块，继续手写注入，不引入框架 | 共享（需改造） |
| ViewModel | AndroidX Lifecycle（声明 2.7.0，navigation 2.10 传递升级到 2.11） | JetBrains `org.jetbrains.androidx.lifecycle` 2.11.0；去掉对 `Application` 的依赖 | 共享（需改造） |
| 导航 | navigation-compose 2.10.2，字符串路由（`navigation/` 下 9 个文件） | JetBrains 多平台 navigation-compose，沿用 Navigation 2 写法；迁移期间不同时换 Navigation 3 | 共享 |
| 自适应布局 | 21 个文件用 `LocalConfiguration`，53 个文件按宽度分支；没有用 `WindowSizeClass` | 换成 `BoxWithConstraints`、`LocalWindowInfo` 等公共 API。桌面复用现有的内屏双栏与检视器布局，再扩展为多栏 | 共享（需改造） |
| 字符串资源 | 285 个文件用 `R.string`；112 个文件调用 `getString(` | 迁到 CMP `composeResources/values*/strings.xml`，格式基本兼容。用脚本批量把 `R.string.x` 改成 `Res.string.x`；非 Composable 的场景用挂起函数 `getString()` | 共享（机械迁移） |
| 字体与光效 | `res/font` 下 3 个 ttf；AGSL `RuntimeShader`（`ComposerIsland`）；另有 5 个文件用 `android.graphics` 的 `BlurMaskFilter`、`RenderEffect` 等实现光效 | 字体放进 `composeResources/font`。着色器源码共享，Android 用 `RuntimeShader`，桌面用 Skia `RuntimeEffect`（5.3） | 共享 + expect/actual |
| BuildConfig | 19 个文件 | 共享接口 `AppBuildInfo`，由 `:app` 和 `:desktopApp` 分别注入 | 接口 |

### 3.4 结构性阻碍

1. **单体模块**：一拆分，构建、测试和静态检查的路径都会跟着变。
2. **源码契约测试按路径读文件**：`native-ui/app/src/test` 下有 67 个文件用 `Path.of("app/src/main/java/...")` 读源码做断言。`scripts/ci/check-text-wrapping.py`、`android-static-checks.sh` 等约 10 个脚本、说明文件和工作流也写死了 `app/src/main`。
3. **`internal` 跨模块不可见**：3247 处 `internal` 声明。代码搬到别的模块后，留在 `:app` 的测试和截图预览就访问不到这些声明。
4. **服务定位器**：`InfinityApplication` 同时负责 Android 生命周期和全部服务组装。
5. **Room 停在 alpha**：`2.7.0-alpha11` 之后 API 变化不少，例如 `@ConstructedBy` + `RoomDatabaseConstructor`；2.8.0 把 Room 自身的 minSdk 提到 23（项目是 31，不受影响）。项目还有双数据库恢复引导 `DualDatabaseRecoveryBootstrap`。

### 3.5 备份现状

- 0.16.0-beta 修复了技能目录内部区导致备份导出失败的问题（提交 `464fb63de`，合并提交 `9a7f2bbe8`）。0.17.0-beta 起，备份按层流式导出：未压缩内容合计上限 512 MiB，单项上限 128 MiB，条目上限 10,000（见 `docs/guide/features.md`）。
- 现有备份都是**全量**的：每次都按所选的层导出完整内容，恢复时整层替换。没有增量备份、变更追踪或合并恢复。增量备份与恢复是新能力，不是修复项。

## 4. CMP/KMP 与 macOS 相关官方现状（核验于 2026-11-07）【事实】

| 项 | 当前状态 | 来源 |
|---|---|---|
| Compose Multiplatform | 稳定版 1.12.2（10-15），预发布 1.13.0-beta02（11-04）。1.12.2 对应 Jetpack Compose 1.12.1 | GitHub Releases；兼容性页 |
| 平台下限 | macOS 13 arm64、Windows 10、Ubuntu 20.04、Android API 21、iOS 14；只支持 64 位 | 兼容性页 |
| Skiko | 支持 macOS（x86_64、arm64）上的 Kotlin/JVM；`org.jetbrains.skia` 包提供 `RuntimeEffect`、`RuntimeShaderBuilder` | Skiko README 与 API 文档 |
| Desktop JDK | 运行需要 JDK 11+；用 jpackage 打原生安装包需要 JDK 17+；JDK 25 需要 ProGuard 7.8.0（CMP 1.12.0 起默认就是这个版本） | 兼容性页；原生分发页 |
| Desktop 窗口 | 1.12.0 在 `androidx.compose.ui.window.v2` 新增实验性的 `WindowState`/`DialogState`，可与旧 API 并存。`MenuBar` 在 macOS 上显示在系统菜单栏，⌘ 快捷键要写 `meta = true` | 窗口管理页；菜单栏页 |
| 托盘与通知 | `Tray()` + `rememberNotification()` + `TrayState.sendNotification()`；macOS 上要打包后才会显示 | 托盘页 |
| 资源系统 | `composeResources/values*/strings.xml`，支持 `string-array`、`plurals` 和限定符目录（如 `values-zh`、`values-b+zh+Hans`）。应用内切换语言暂时要自己写 expect/actual，桌面端调用 `Locale.setDefault` | 资源文档 |
| 原生分发 | macOS 支持 `.dmg`、`.pkg`；安装包自带 jlink 精简运行时；配置块 `macOS { signing / notarization / entitlementsFile / runtimeEntitlementsFile / appStore }`，公证任务为 `notarize<FORMAT>`；dmg/pkg 版本号格式为 `MAJOR[.MINOR][.PATCH]`（非负整数，不带后缀）；附带文件放在 `appResourcesRootDir/<os>[-<arch>]` | 原生分发页 |
| Jetpack KMP 库 | Room 2.8.5（09-09）、SQLite 2.7.1、DataStore 1.3.0（10-21）、Lifecycle/ViewModel 2.11.0、Paging 3.5.3、Collection 1.7.0、Annotation 1.11.0、SavedState 1.5.0 都提供 JVM 构件。Navigation 2.10.2、Navigation3 1.2.1、ViewModel-Compose 的非 Android 构件由 JetBrains 构建 | developer.android.com KMP 页（11-04 更新） |
| Room KMP | JVM 端写法：`Room.databaseBuilder<T>(name = path)` + `BundledSQLiteDriver` + `setQueryCoroutineContext(Dispatchers.IO)`；KSP 按目标分别配置（如 `kspDesktop`）。KMP 下不可用：`setQueryCallback`、`setAutoCloseTimeout`、`createFromAsset`、`enableMultiInstanceInvalidation`、LiveData、`androidx.sqlite.db`。可以用 `room-sqlite-wrapper` 的 `getSupportWrapper()` 过渡 | Room KMP 文档；Room 发布说明 |
| Room 3 | 官方 KMP 文档的示例已改用 `androidx.room3` 3.1.0-alpha03 | Room KMP 文档 |
| AGP 9 + KMP | 从 AGP 9 起，KMP 插件与 `com.android.application`、`com.android.library` 不兼容，应用入口要拆到独立模块。`com.android.kotlin.multiplatform.library` 只有单一变体：不支持 flavor、build type、`externalNativeBuild`、BuildConfig、data/view binding。Android 资源、宿主测试（`src/androidHostTest`）和设备测试要显式开启。`androidLibrary {}` 已弃用，改用 `android {}` | Android-KMP 插件文档；AGP 9 迁移文档 |
| 导航 | 多平台 navigation-compose（CMP 1.12.2 配套版本为 2.10.0-rc01）；Navigation 3 从 CMP 1.10 起支持全部平台 | Kotlin 导航文档；CMP 1.12.2 发布说明 |
| 第三方库 | Ktor 3.6.1（10-30）、SQLDelight 2.4.1、Coil 3.6.3、markdown-renderer 0.46.0（支持 JVM）、quickjs-kt 1.0.17（支持 JVM）、Koin 4.2.2 | 各仓库 Releases |
| macOS 命令沙盒 | `sandbox-exec(1)` 手册页标为 DEPRECATED，建议改用 App Sandbox，但命令仍然存在（本机 Darwin 27.2 已查）。Codex CLI 的 `codex-rs/sandboxing/src/seatbelt.rs` 固定调用 `/usr/bin/sandbox-exec`，基础策略文件为 `seatbelt_base_policy.sbpl`，另有网络策略文件，模式分 `read-only`、`workspace-write`、`danger-full-access`（Apache-2.0）。Claude Code 的沙盒建立在 `@anthropic-ai/sandbox-runtime` 上，macOS 端用 `sandbox-exec` 加动态生成的 Seatbelt 配置；网络只允许连到本机代理端口，由代理按域名白名单放行；该包提供 `srt` 命令行，Apache-2.0，标注为 Beta，最新 v0.0.81 | 本机 `man sandbox-exec`；openai/codex 源码；Claude Code 沙盒文档；sandbox-runtime 仓库 |
| Apple container | 在轻量虚拟机里运行 Linux 容器，用 Swift 编写；要求 Apple 芯片和 macOS 26，不支持更早的系统；最新 1.7.1，Apache-2.0；提供 `container` 命令行和 `container-apiserver` XPC API（大版本内保证兼容） | apple/container 仓库 |
| 桌面 WebView | NucleusFramework/ComposeNativeWebview：macOS 上嵌入原生 WKWebView，Apache-2.0，要求用 `nucleusApplication(backend = NucleusBackend.Tao)` 启动应用，没有发布 Release。compose-webview-multiplatform 2.0.3 的桌面端依赖 KCEF，而 KCEF 已于 2025-10-28 归档，作者注明不推荐使用。JetBrains/jcef 作为 JetBrains Runtime 的一部分分发；JBR 25 Releases 页上可见的 `jbr_jcef` 构建是 Linux 版，macOS aarch64 的 JCEF 构建没有在页面上列出 | 各仓库 README 与 Releases |
| macOS 隐私权限 | 第三方应用通过辅助功能控制 Mac 时，系统会弹出提示，用户必须在"隐私与安全性 → 辅助功能"里授权；屏幕录制在"隐私与安全性 → 屏幕与系统录音"里授权 | Apple 支持文档 |

需要留意的不一致：

- **【待确认】navigation-compose 版本对不上。** CMP 1.12.2 配套的多平台构件是 2.10.0-rc01，而项目的 Android 端已经在用 2.10.2 稳定版。要在 PoC 里用 `dependencyInsight` 确认最终解析到哪个版本。
- **【待确认】Compose 版本对齐。** CMP 1.12.2 在 Android 端解析为 Jetpack Compose 1.12.1，而 `:app` 当前用 BOM `2026.06.00`。PoC 要确认 Gradle 最终选中的版本，以及截图基线会不会因此变化。
- **【建议】迁移期间不采用 Room 3。** Room 3 仍是 alpha，迁移以 Room 2.8.5 稳定版为准，等它稳定后再单独评估。

## 5. 目标架构

### 5.1 模块演进

```text
阶段 P1 之后（整体搬迁完成）
native-ui/
├── app/          # Android 应用壳（com.android.application），名称不变
│   ├── 风味 play/sideload 与风味专属实现（RealPathMountSupport）、BuildConfig → AppBuildInfo 注入
│   ├── Manifest、Application/Activity/Service/Receiver 入口
│   ├── NDK/CMake（llama.cpp、termux pty、file identity）与 jniLibs 生成任务
│   └── testPlay/ testSideload/ androidTest/ screenshotTest/（截图测试位置以 P0 结论为准）
├── shared/       # 单一 KMP 模块（com.android.kotlin.multiplatform.library）
│   ├── src/androidMain/   # 起初承载 :app 搬来的全部业务、数据与界面代码和资源
│   ├── src/androidHostTest/  # 搬来的单元测试（含 Robolectric）
│   └── 之后逐步出现 commonMain、jvmSharedMain、desktopMain
├── desktopApp/   # P3 新增：Compose Desktop 入口
└── mainactivity-e2e/、blackbox-fixture/   # 保持不变

桌面跑通之后（按需细拆，名称与边界以届时测得的构建耗时和依赖图为准）
shared/core   shared/data   shared/ui   shared/viewers ...
```

`:shared` 的源码集层级：

```text
commonMain          纯 Kotlin：domain、模型、设计系统、主题令牌、组件、动画、大部分页面
└── jvmSharedMain   【待确认】android 与 desktop 共用的 JVM 源码集：OkHttp、JSch、POI、java.io 代码
    ├── androidMain
    └── desktopMain (jvm("desktop"))
```

`jvmSharedMain` 能让大量基于 `java.io`、OkHttp、JSch 的数据层代码原样共享，不必为了 macOS 重写成 okio 或 Ktor。手动建立 android 与 jvm 共用的中间源码集，KMP 是支持的层级配置；但 KSP（Room）、IDE 和 Android-KMP 插件在这种层级下能否正常工作，要在 P0 实测。如果不行，退路有两条：把纯 JVM 部分拆成独立的 `kotlin("jvm")` 模块给两端依赖；或者让 `desktopMain` 用 `kotlin.srcDir` 指向同一个目录【推断】。

**命名空间。** 把 `:shared` 的 namespace 设为 `com.promenar.infinity`，`:app` 的 namespace 改成另一个值（如 `com.promenar.infinity.app`），applicationId 保持 `com.promenar.infinity` 不变。这样 283 个文件里的 `import com.promenar.infinity.R` 不用改，Manifest 中的组件类名是全限定名，也不受影响【推断，P0 验证】。

### 5.2 平台边界：接口注入为主，expect/actual 为辅

- 平台能力统一采用**共享接口 + 平台实现 + 手写注入**，延续 `InfinityApplication` 现有的手写依赖注入方式。
- `expect/actual` 只用于无状态的小函数（时间源、平台名、`Locale` 设置）、Room 的 `RoomDatabaseConstructor`、着色器包装（5.3）和查看器插槽（5.6）。
- 拆分 `InfinityApplication`：`:shared` 提供纯 Kotlin 的 `AppGraph(platform: PlatformModule)`；`:app` 提供 `AndroidPlatformModule(context)`；`:desktopApp` 提供 `DesktopPlatformModule(appDirs)`。`InfinityApplication` 只保留 Android 生命周期回调。
- `PlatformModule` 的首批接口：`AppDirectories`、`KeyValueStore`、`SecretStore`、`Notifier`、`Haptics`、`PlatformActions`、`DocumentPicker`、`CommandSandbox`、`NetworkMonitor`、`BackgroundWork`、`AppBuildInfo`、`DocumentParsers`、`DistributionCapabilities`。

### 5.3 共享设计系统与动画

- 主题、颜色与字号令牌、间距、组件、页面转场、点阵与灯珠动画、生成状态指示等纯 Compose 动画放进 `commonMain`，两端共用一套。
- AGSL 着色器：`ComposerIsland` 的 AGSL 源码只用了 `uniform float/float2` 和常规运算。AGSL 与 SkSL 语法都接近 GLSL，并共用 `float2`、`half4` 写法和 `toLinearSrgb` 等内建函数，所以源码可以作为共享字符串常量。平台差异只在包装层，做成 `expect class ShaderEffect(source)`：
  - Android 的 actual 用 `android.graphics.RuntimeShader`；
  - 桌面的 actual 用 `org.jetbrains.skia.RuntimeEffect.makeForShader` + `RuntimeShaderBuilder` 设置 uniform，再交给 Compose 的 `ShaderBrush`【推断：Skiko API 中两个类都存在，方法签名与 `ShaderBrush` 的衔接需要 P0 实测】。
- 其它 `android.graphics` 光效（`BlurMaskFilter`、`RenderEffect`、`ColorMatrix`，5 个文件）优先改写成 Compose 公共 API（`Modifier.blur`、`ColorFilter`、`graphicsLayer` 等）；改不了的再按着色器的方式做 expect/actual。
- 系统关闭动画时的静态表现，两端沿用同一个设置项。

### 5.4 Agent 命令执行与沙盒

- **执行**：`ProcessBuilder` 启动宿主 zsh，交互终端用 PTY（pty4j，版本【待确认】）。工具协议、审批卡、输出折叠、后台任务这些都复用现有的共享逻辑。
- **沙盒**：新增 `CommandSandbox` 接口，在 macOS 上实现为 Seatbelt：
  - 每条命令经 `/usr/bin/sandbox-exec -p <策略>` 启动，只认 `/usr/bin` 下的路径。
  - 策略文件由应用按当前工作区动态生成。写入限定在工作区、会话专用空间和临时目录；读取默认放开，但拒绝 `~/.ssh`、`~/.aws`、钥匙串文件以及应用自己的密钥目录；网络按执行模式决定是否放开。
  - 策略结构参考 Codex CLI 的 `seatbelt_base_policy.sbpl` 和网络策略文件（Apache-2.0，可以引用改写，需要保留许可声明）。
- **模式**：对应 Android 现有的执行模式，分为只读、工作区可写、完全访问三档；完全访问档需要用户明确开启，审批卡显示当前档位。
- **风险与退路**：`sandbox-exec` 已标为 DEPRECATED，但 Codex CLI 和 Claude Code 目前仍依赖它。如果将来某个 macOS 版本移除了它，就退到 Apple `container`（Linux 虚拟机隔离），或者只保留工作区路径白名单加审批。`container` 要求 macOS 26 和 Apple 芯片，作为可选的"Linux 环境"执行位置，在 P5 接入。
- **Tailscale**：桌面不内置 tsnet。Agent 和 SSH 直接使用系统网络，tailnet 地址由系统 Tailscale 解析。

### 5.5 Computer Use（桌面版 PhoneUse）

- **命名**：桌面上这项能力叫 Computer Use；Android 侧载版沿用 PhoneUse。两者共用"截图 + 元素树 + 动作 + 监督与接手"的 Agent 协议。
- **macOS 权限**：读取其它应用的界面和发送输入事件需要"辅助功能"授权，截图需要"屏幕与系统录音"授权。两项都由用户在"隐私与安全性"中手动开启。
- **前提**：应用不能开 App Sandbox。这与 Developer ID 分发、不上 Mac App Store 的方向一致。
- **建议不进首发，放在 P5。** 理由有两个：
  1. 系统授权绑定在应用的签名身份上。开发期的未签名或临时签名构建，每次重装都可能需要重新授权【推断】。要等签名身份稳定后，体验才可控。
  2. 截图（ScreenCaptureKit）和辅助功能 API 都要写原生桥接（JNA 或 JNI），工作量独立。

### 5.6 平台查看器

查看器是平台差异最集中的地方。它们统一通过共享插槽接口（如 `WebContentViewer`、`TerminalViewer`）接入，外围的顶栏、标签页、检视器、加载与错误状态都由共享代码负责。

| 查看器 | Android | macOS 方案 | 首发 |
|---|---|---|---|
| 网页与产物（HTML 产物、画布、多屏原型、Agent 浏览器、Mermaid、ECharts、KaTeX、导出渲染） | 系统 WebView | 暂定（2026-11-08，P1 原型验证）：标准 Compose AWT 窗口 + 系统 WKWebView 原生子视图，自写 JNI 桥。网页挂在 `AWTView` 内、图层排在 Compose 之后，Compose 在网页区域透明打洞，弹层可盖住网页；滚动时原生几何推迟 1 帧同步。实测同页内存约 448 MB，与 ComposeNativeWebview 持平，包体增量约 0.1 MB。会话富格式默认由共享隐藏渲染器渲染成图片，点开后换成实时网页。不采用 ComposeNativeWebview（要求整个应用改用 Nucleus Tao 后端）与 JCEF（包体增加 300 MB 以上）。该方案只覆盖 macOS，Windows/Linux 另写 | 是 |
| 终端 | Termux `TerminalView` | 自绘 Compose 终端渲染器，终端解析核心移植自 Termux `terminal-emulator`，见下方“终端分层” | 是 |
| diff | 差异视图组件 | 共享实现；桌面加并排对比和文件树 | 是 |
| 代码 | markdown-renderer-code 高亮 | 共享实现；桌面加行号、搜索和折叠 | 是 |
| 文档（Markdown、PDF、Office） | 文档页 + PDF 解析 | Markdown 共享；PDF 用 PDFBox 渲染成图后共享展示【推断】；Office 先显示解析出的文本 | 是 |
| 状态 | 运行坞、检视器、会话状态面板 | 共享实现，桌面常驻在侧栏 | 是 |
| 媒体 | Media3 | 系统播放器打开 | 后续 |

网页查看器的 JS 桥（画布把文字写进输入框草稿等）和 Compose 弹层与原生视图的层级关系（重量级视图会盖住 Compose 内容）是 P0 必测项。

**终端分层**（终端只是渲染与手动操作界面，不是 Agent 执行层）：

| 层 | 职责 | 平台关系 |
|---|---|---|
| 执行层 | 启动进程与 PTY（macOS/Linux 用宿主 PTY + Seatbelt；Windows 用 ConPTY；Android 保留现有 PTY；iOS 没有本地 shell，只接 SSH 远端） | 每个平台各自实现，经接口注入 |
| 解析核心 | VT/ANSI 序列解析、屏幕缓冲、滚动历史、宽字符 | `commonMain` 一份 |
| 渲染与输入 | 字符网格绘制、光标、选择复制、键盘与输入法、主题 | `commonMain` 一份 Compose 实现 |

选型结论：**自绘 Compose 终端，不用 JediTerm。**
- JediTerm 是 Swing 组件，只能跑在桌面 JVM（macOS、Windows、Linux 都可以，pty4j 支持 Windows ConPTY），Android 与 iOS 用不了；经 `SwingPanel` 嵌入还会遇到层级遮挡、动画与主题和 Compose 设计系统不一致的问题。选它意味着长期维护两套终端。
- 自绘 Compose 终端在 Android、桌面、iOS 共用一份界面，直接复用设计系统与动画；代价是要自己处理字形缓存与绘制性能、输入法、选择和无障碍。【事实】Termux `terminal-emulator` 0.118.0 的类中，解析核心（`TerminalEmulator`、`TerminalBuffer`、`TerminalRow`、`TextStyle`、`WcWidth`）只引用了 `android.util.Base64`（OSC 52 剪贴板）；`KeyHandler` 引用 `android.view.KeyEvent` 常量；`TerminalSession` 依赖 `Handler`、`android.system.Os` 和 JNI，属于执行层，不复用。项目的 SSH 终端（`RemoteTerminalBuffer`）已经只把 `TerminalEmulator` 当解析器用、不创建本地会话，说明核心可以和执行层分开。
- 【事实】`terminal-emulator` 与 `terminal-view` 是 termux-app 仓库（GPLv3）中按 Apache 2.0 发布的例外，允许移植进本项目源码。
- 复用方式：把解析核心移植成 Kotlin 放进 `commonMain`（桌面 JVM 本可直接用 Java 源码，但 iOS 的 Kotlin/Native 不能运行 Java，所以一次转成 Kotlin），`Base64` 换成 `kotlin.io.encoding.Base64`，按键映射改为平台无关的键码表。`TerminalRenderer`/`TerminalView` 是 Android `View` 与 `Canvas`，不能复用，改写为 Compose `Canvas` 渲染器；Android 端随后也切到这套渲染器，两端统一。
- 【待确认】Compose 自绘在大量输出（如 `cat` 大文件、全屏 TUI 程序）下的帧率，放进 P0-B 测一次。

### 5.7 桌面交互侧重

- **多栏与分屏**：会话列表、会话流、检视器三栏，检视器可以拆成并排的两个查看器（例如左边 diff、右边网页预览）。栏宽可以拖动，并按窗口记住。
- **窗口与标签页**：一个会话或一个查看器可以单独开成新窗口；查看器区域用标签页（文件、产物、网页、终端）。窗口位置和大小在重启后恢复。
- **键盘快捷键**：系统菜单栏覆盖新建会话、切换会话、打开工作区、发送、停止、批准或拒绝、搜索、切换标签页、开关检视器，快捷键统一用 ⌘（`meta = true`）。
- **悬停与右键菜单**：消息、文件、产物和工具行在悬停时显示操作按钮；长按菜单在桌面上改为右键菜单，复用同一套菜单数据。
- **状态面板**：运行状态、计划进度、后台任务、待批准数常驻在侧栏或底部状态栏，不用再点运行坞进入。
- **托盘**：关闭窗口时生成继续进行，托盘显示运行状态，完成或需要审批时发通知。

### 5.8 打包与分发

- **开发期（P3–P5）**：`./gradlew :desktopApp:run` 直接运行；需要安装包时用 `packageDmg` 打本机未签名或临时签名的 DMG，只在本机或可信设备上用。dmg 的 `packageVersion` 只接受 `MAJOR[.MINOR][.PATCH]`，所以 `0.34.0-beta` 要写成 `0.34.0`，构建号用 `packageBuildVersion`。
- **正式分发（P6）**：Developer ID 签名、Hardened Runtime、公证（`notarizeDmg`），通过官网和 GitHub Release 发布 DMG 与 `.sha256`。官方教程是 `tutorials/Signing_and_notarization_on_macOS`。附带的可执行文件（如 python）需要逐个签名【推断】。Intel 架构包是否要做，在 P6 决定。

## 6. 模块路线对比（D8）

| 维度 | 路线 A：先整体搬入单一 KMP 模块，再逐步上浮，桌面跑通后细拆 | 路线 B：直接按 core/data/ui 细拆，边拆边移植 |
|---|---|---|
| 前期设计成本 | 低。只需要处理风味、BuildConfig、NDK、入口这几类留在 `:app` 的边界 | 高。要先理清 29 万行代码的依赖图，并打断 `InfinityApplication` 服务定位器和 `ui/chat` 里混入的业务逻辑造成的循环依赖，才能定出模块边界 |
| 中间态破损次数 | 一次集中的大变更（搬迁提交），之后都是模块内的小步上浮 | 每拆出一个模块就要经历一轮可见性、R 类、测试位置和契约路径的破损，按 3–6 个模块算，就是 3–6 轮 |
| 门禁与脚本返工量 | 67 个路径契约测试、约 10 个脚本、工作流和 `AGENTS.md` 的门禁命令都只改一次（指向 `shared/src/androidMain`） | 每拆一个模块，这些路径和门禁命令都要再改一次 |
| `internal` 可见性 | 测试与代码一起搬进 `:shared/androidHostTest`，3247 处 `internal` 仍在同一模块内，可见性不变 | 跨模块引用的 `internal` 要逐处改成 `public` 或调整测试位置 |
| 增量编译与并行收益 | 前期没有。`:shared` 仍是一个大模块，和现在的 `:app` 相当。不过单元测试从两个风味各跑一遍（9527 + 9517 项）变成单一变体只跑一遍，门禁时间有望缩短【推断】 | 细拆后能并行编译，改一处只重编下游模块；但这些收益要在拆分完成后才能兑现 |
| 单一变体限制 | 风味相关代码只有 `RealPathMountSupport` 一对、19 处 BuildConfig 引用，以及 NDK、Manifest；这些留在 `:app`，经接口注入，影响很小 | 同样的限制会出现在每个 KMP 模块上，而且边界越多，处理越分散 |
| 风险 | 搬迁提交很大，需要一次集中验证；截图测试和 lint 能否覆盖 `:shared` 需要 P0 确认 | 周期长；功能开发与拆分并行时冲突多 |

**推荐路线 A。** 它的破损集中在一次搬迁里，路径和门禁只改一次，`internal` 可见性不受影响。并行编译的收益推迟到桌面跑通以后再拿，那时依赖边界已经由上浮过程自然理清，拆分成本更低。

**P0 必测**：在分支上把 `:app` 整体搬进 `:shared`（`androidMain` + `androidHostTest`），记录这些数据：
- 冷构建、增量构建（改一个 ViewModel、改一个界面文件）和单元测试的耗时，与当前 `:app` 对比；
- `:app` 的 `lintSideloadDebug` 与 `validateSideloadDebugScreenshotTest` 能否覆盖 `:shared`；
- Robolectric 在 `androidHostTest`（`isIncludeAndroidResources = true`）下的通过率；
- 命名空间方案（5.1）能否成立；
- KSP 在单一变体下生成 Room 代码是否正常。

## 7. 分阶段计划

原则：每一波结束时，`main` 上都能跑通 `AGENTS.md` 第 4 节的全部门禁，并能打出可发布的侧载包。后端与数据层改造按惯例经 Codex 派发；桌面界面先出原型给用户确认。P0-A、P0-B、P0-C 三项互不依赖，可以并行。

### P0-A 依赖升级（在 `main` 上，逐项提交）

- **范围**：每项一次提交，每次都跑门禁：
  1. Room `2.7.0-alpha11` → `2.8.5`：改用 `@ConstructedBy`，接入 Room Gradle 插件的 `schemaDirectory`，跑 v18→v36 迁移测试和双数据库恢复回归；
  2. Lifecycle 显式对齐到 `2.11.0`，与 navigation 2.10 传递进来的版本一致；
  3. Coil `3.0.0` → `3.6.3`；
  4. markdown-renderer `0.41.0` → `0.46.0`；
  5. `quickjs-kt-android` → `io.github.dokar3:quickjs-kt` KMP 构件 `1.0.17`；
  6. 视需要把 DataStore 升到 `1.3.0`（目前只声明、没有使用，也可以先删掉声明）。
- **风险**：Room 升级可能影响迁移链和恢复引导；markdown-renderer 升级可能改变渲染效果，导致截图差异。
- **验收**：全量门禁通过；真机覆盖安装 0.34.0-beta 后，会话、密钥、知识库数据完好；截图差异经预览确认后，只更新一次基线。
- **门禁影响**：任务名不变。

### P0-B 技术验证 PoC（独立分支，不合入 main）

- **范围**：
  1. 第 6 节的整体搬迁必测项；
  2. `jvmSharedMain` 能否用于 KSP、IDE 和 Android-KMP 插件；
  3. 加入 `jvm("desktop")` 目标后，P0-A 升级的各库能否解析出 JVM 构件，Compose、navigation、lifecycle 的版本对齐情况（`dependencyInsight`）；
  4. Room 2.8.5 在桌面上用 `BundledSQLiteDriver` 只读打开一份 schema v36 数据库；
  5. 在桌面窗口里渲染一个现有页面（如设置首页），验证 `composeResources` 中英文切换；
  6. AGSL 着色器经 Skia `RuntimeEffect` 在桌面渲染出浮岛光效；
  7. 网页查看器两个候选的对比：KaTeX、Mermaid、ECharts、HTML 产物的渲染效果，JS 桥，与 Compose 弹层的层级，内存与包体；
  8. 用 Seatbelt 策略跑一条写工作区外文件的命令，确认被拒；
  9. 本机未签名的 `packageDmg` 能否安装并启动。
- **验收**：一份 PoC 报告，逐项给出通过或不通过和证据；附桌面窗口截图；该分支上的 Android 门禁仍然通过。
- **门禁影响**：无。

### P0-C 增量备份与恢复专项（在 `main` 上，Android 先受益）

- **范围**：在现有分层全量备份的基础上，增加变更追踪（按表行的版本或更新时间、按文件的内容哈希）、基线加增量的包格式、按增量链恢复，以及恢复前的冲突检查。WebDAV 远端只上传差异。先出方案文档，经用户确认再施工。
- **风险**：包格式兼容（旧的全量包要继续能导入）；密钥层加密与增量链的关系；恢复中断后的回滚。
- **验收**：单测覆盖增量生成、链式恢复、中断回滚；真机上"全量 → 改动 → 增量 → 换机恢复"走通；旧备份包仍能导入。
- **门禁影响**：任务名不变，测试数量增加。

### P1 整体搬迁到 `:shared`（`main`，路线 A）

- **前置**（P1 第一批提交）：`BuildConfig` 收到 `AppBuildInfo`；`RealPathMountSupport` 和 `DistributionCapabilities` 改为注入；引入 `SourcePaths` 解析器，供契约测试使用；静态检查脚本改为扫描多个源码根。
- **范围**：按 P0-B 验证过的方案，把 `:app` 的源码、资源和单元测试整体搬进 `:shared` 的 `androidMain`/`androidHostTest`。`:app` 只保留第 5.1 节列出的部分。同步更新 `AGENTS.md` 第 4 节、CI 工作流（`jvm-test-diagnostics`、`model-catalog-refresh` 等用到 `--tests` 过滤的地方）和 `docs/guide/development.md`。
- **风险**：一次大提交；R8 规则要移到 `:shared` 的 consumer rules；截图测试的位置以 P0 结论为准。
- **验收**：全量门禁通过（测试总数与搬迁前一致）；签名侧载包装到真机，冒烟走完会话、工作区、SSH、Linux 主流程；API 36 minified 黑盒通过；16KB 页对齐检查通过。
- **门禁影响**：门禁命令变为 `:shared:testAndroidHostTest`（以实际任务名为准）+ `:app:testSideloadDebugUnitTest`/`testPlayDebugUnitTest`（只剩风味测试）+ 其余不变的任务，仍然在同一次 Gradle 调用里完成。

### P2 去耦与上浮（`main`，逐包推进）

- **范围**：
  - 引入 `PlatformModule` 接口：SharedPreferences 收到 `KeyValueStore` 后面，`Context` 只在平台实现里出现，Intent 收到 `PlatformActions` 后面；
  - ViewModel 去掉对 `Application` 的依赖；拆分 `InfinityApplication`；
  - 数据库迁移和事务改用驱动 API；
  - `strings.xml` 用脚本转进 `composeResources`，批量改写 `R.string`；
  - `LocalConfiguration` 换成公共 API；
  - 逐包把代码从 `androidMain` 上浮到 `commonMain` 或 `jvmSharedMain`，先 `domain`、模型，再数据层，最后界面。
- **风险**：SharedPreferences 的键名和文件名必须保持不变，覆盖安装后数据才在；字符串迁移量大，要用脚本加编译验证；截图基线可能因为资源加载方式产生像素差。
- **验收**：每批都全量门禁通过；字符串迁移后截图差异经确认只更新一次基线；`commonMain` 里的文件数随批次增长（作为进度指标）。
- **门禁影响**：任务名不变。

### P3 桌面目标与共享界面

- **范围**：`:shared` 加上 `jvm("desktop")`，补齐 `desktopMain` 平台实现的骨架；新建 `:desktopApp`，包含窗口、菜单栏、托盘和平台模块；设计系统、动画和着色器包装按 5.3 落地；纯逻辑测试逐步移到 `commonTest`。
- **风险**：桌面端缺失的平台实现要先用占位实现顶上，并写清楚是占位，避免被误认为功能已完成。
- **验收**：全量门禁通过；`:desktopApp:run` 能进入会话页并完成一轮云端对话；`desktopTest` 通过。
- **门禁影响**：加入 `:shared:desktopTest`。

### P4 macOS 首发功能

- **范围**：结论 6 的功能集，包括 5.4 的 Seatbelt 沙盒执行、5.6 的查看器、5.7 的桌面交互；数据库放在 Application Support；基于 P0-C 的备份与手机互导。桌面交互先出原型给用户确认。
- **风险**：沙盒策略过严会让常见开发命令失败（如写 `~/.cache`、访问 Homebrew 目录），过松则失去隔离意义，需要拿真实开发工作流调整；网页查看器与 Compose 弹层的层级问题。
- **验收**：在本机 Mac 上走完主流程：配置提供商 → 新建会话 → 挂载文件夹 → Agent 在沙盒里执行命令并审批 → 查看 diff、产物和网页 → SSH 经系统 Tailscale 连到 tailnet 主机 → 知识库检索 → 与手机互导备份。附截图与日志证据。
- **门禁影响**：桌面冒烟作为新增的手工验收项；Android 门禁不变。

### P5 能力补齐与细拆

- Computer Use（5.5）；Apple `container` 作为可选的 Linux 执行环境；本地推理（llama.cpp Metal）；Python 运行时；拖放与"打开方式"导入；Media3 替代方案。
- 按构建耗时和依赖图把 `:shared` 拆成 core、data、ui、viewers 等模块。

### P6 正式分发

- Developer ID 签名、Hardened Runtime、公证；官网与 GitHub Release 分发；应用内更新检查；把桌面版本号规则写进 `AGENTS.md` 第 6 节；更新 `docs/distribution-builds.md`、README 和安装指南。
- 验收：在另一台干净的 Mac 上，从浏览器下载 DMG，首次启动不被 Gatekeeper 拦截，`spctl --assess` 与 `stapler validate` 通过。

## 8. 对现有门禁与发行流水线的影响

| 对象 | P0-A / P0-C | P0-B | P1 | P2 | P3–P5 | P6 |
|---|---|---|---|---|---|---|
| `:app:testSideloadDebugUnitTest` / `testPlayDebugUnitTest` | 测试增加 | 无 | 只剩风味测试 | 不变 | 不变 | 不变 |
| `:shared` Android 宿主测试 | — | PoC 验证 | 新增，承载搬来的测试 | 测试逐步移到 `commonTest` | 同左 | 不变 |
| `:app:lintSideloadDebug` | 不变 | PoC 验证 | 需要覆盖 `:shared` | 同左 | 同左 | 不变 |
| `assembleSideloadDebug` / `assemblePlayDebug` | 不变 | 无 | 不变 | 不变 | 不变 | 不变 |
| `validateSideloadDebugScreenshotTest` | markdown-renderer 升级后可能要更新一次基线 | PoC 验证 | 位置以 PoC 结论为准 | 字符串迁移后可能要更新一次基线 | 不变 | 不变 |
| `check-distribution-manifests.py` / `check-native-page-alignment.py` | 不变 | 无 | 不变 | 不变 | 不变 | 不变 |
| 源码契约测试、`check-text-wrapping.py` 等路径脚本 | 不变 | 无 | 只改一次（`SourcePaths`） | 加入 `composeResources` 与 `commonMain` | 不变 | 不变 |
| 新增门禁 | — | — | — | — | `:shared:desktopTest`、桌面手工冒烟 | `packageDmg` + 公证 |
| `release.yml` | 不变 | 无 | R8 consumer rules 回归（API 36 minified 黑盒） | 同左 | 不变 | 新增 macOS 资产 |

## 9. 风险清单

| 风险 | 等级 | 缓解 |
|---|---|---|
| 整体搬迁后截图测试或 lint 无法覆盖 `:shared` | 高 | P0-B 必测；退路是把截图测试移到 `:shared`，或者 lint 改用 `checkDependencies` |
| `jvmSharedMain` 在 KSP、IDE、AGP KMP 插件下不可用 | 高 | P0-B 验证；退路见 5.1 |
| Room 从 alpha 升到 2.8.5，破坏迁移链或双数据库恢复 | 高 | P0-A 单独一次提交，跑 v18→v36 迁移测试，真机覆盖安装回归 |
| 宿主 shell 的 Seatbelt 策略过严或过松 | 高 | 默认工作区可写、拒读密钥目录；用真实开发工作流调策略；完全访问档要用户明确开启 |
| `sandbox-exec` 已被标为 DEPRECATED，将来可能被移除 | 中 | 关注 Codex CLI 与 sandbox-runtime 的动向；退路是 Apple `container` 或只保留路径白名单加审批 |
| 自写 WKWebView 桥接的维护成本与时序风险：原生加 Kotlin 约 1.1–1.6 千行；系统负载高时滚动同步对齐率下降 | 中 | 正式开发早期在常规负载下复测滚动同步；评估 `presentsWithTransaction` 让 Compose 帧与原生几何同一事务提交；会话列表默认显示快照 |
| 增量备份格式与现有全量包、密钥层不兼容 | 中 | 先出方案文档；旧包继续能导入；增量链要能校验和回滚 |
| 搬迁提交过大，导致 Release 包的 R8 运行时问题 | 中 | 搬迁当轮跑 minified 黑盒，consumer rules 随代码一起迁移 |
| 截图基线因依赖升级或资源迁移大面积漂移 | 中 | 预览确认后一次更新；视觉改动与迁移分开提交 |
| Compose、navigation 版本解析冲突 | 中 | P0-B `dependencyInsight` 确认，必要时用 constraints 锁定版本 |
| 开发期构建没有稳定签名，钥匙串和 TCC 授权反复失效 | 低 | 开发期接受重新授权；依赖权限的 Computer Use 放到 P5，正式签名放到 P6 |
| 迁移周期内与功能开发冲突 | 中 | P1 搬迁当轮冻结功能合入；P2 按包分批、每批短周期合入 |

## 10. 待确认与 PoC 清单

1. 【待确认】`:app` 整体搬入 `:shared` 后的编译与测试耗时、截图测试与 lint 的覆盖情况、命名空间方案（P0-B-1）。
2. 【待确认】`jvmSharedMain` 方案是否可行（P0-B-2）。
3. 【待确认】navigation-compose、Compose 在 `:shared` 与 `:app` 之间最终解析的版本（P0-B-3）。
4. 【待确认】Skia `RuntimeEffect` 与 Compose `ShaderBrush` 怎样衔接（P0-B-6）。
5. 【待确认】网页查看器（暂定自写 WKWebView 桥接）在常规负载下的滚动同步对齐率；Tab 焦点交接、网页卡片上的滚轮归属、隐藏渲染器按阈值回收的实现方式（P1 原型遗留）。
6. 【待确认】Compose 自绘终端在大量输出与全屏 TUI 下的帧率（P0-B）；Termux 解析核心转 Kotlin 后的回归用例覆盖（P3 前）；桌面 PTY 方案（pty4j 或自写 JNI/FFM 调用 `forkpty`）的当前版本与 macOS arm64 支持（P4 前）。
7. 【待确认】Seatbelt 策略能否覆盖 git、Homebrew、npm、gradle 这类常用工具链（P0-B-8 之后，在 P4 用真实工作流调整）。
8. 【待确认】开发期构建在 TCC 和钥匙串授权上的实际表现（P4 实测）。
9. 【待确认】附带可执行文件的签名方式与 Hardened Runtime entitlements（P6，官方分发页没有写）。

## 11. 来源

- Compose Multiplatform Releases：https://github.com/JetBrains/compose-multiplatform/releases
- 兼容性与版本：https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- 原生分发（打包、签名、公证、版本号、附带资源）：https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html
- macOS 签名与公证教程：https://github.com/JetBrains/compose-multiplatform/blob/master/tutorials/Signing_and_notarization_on_macOS/README.md
- 桌面组件：https://kotlinlang.org/docs/multiplatform/compose-desktop-components.html ；托盘：https://kotlinlang.org/docs/multiplatform/compose-desktop-tray.html ；菜单栏：https://kotlinlang.org/docs/multiplatform/compose-desktop-menu-bar.html ；窗口：https://kotlinlang.org/docs/multiplatform/compose-desktop-top-level-windows-management.html
- 资源系统：https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-setup.html ；https://kotlinlang.org/docs/multiplatform/compose-resource-environment.html
- 导航：https://kotlinlang.org/docs/multiplatform/compose-navigation.html ；https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html
- Jetpack KMP 库列表：https://developer.android.com/kotlin/multiplatform
- Room 发布说明：https://developer.android.com/jetpack/androidx/releases/room ；Room KMP：https://developer.android.com/kotlin/multiplatform/room
- DataStore 发布说明：https://developer.android.com/jetpack/androidx/releases/datastore
- Android-KMP 库插件：https://developer.android.com/kotlin/multiplatform/plugin ；AGP 9 迁移：https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html
- Skiko：https://github.com/JetBrains/skiko ；Skiko API：https://jetbrains.github.io/skiko/skiko/org.jetbrains.skia/index.html ；SkSL：https://skia.org/docs/user/sksl/
- AGSL：https://developer.android.com/develop/ui/views/graphics/agsl ；https://developer.android.com/develop/ui/views/graphics/agsl/agsl-vs-glsl
- Codex CLI 沙盒源码：https://github.com/openai/codex/tree/main/codex-rs/sandboxing/src
- Claude Code 沙盒：https://code.claude.com/docs/en/sandboxing ；sandbox-runtime：https://github.com/anthropic-experimental/sandbox-runtime
- Apple container：https://github.com/apple/container
- Apple 支持文档（辅助功能）：https://support.apple.com/guide/mac-help/allow-accessibility-apps-to-access-your-mac-mh43185/mac ；（屏幕录制）：https://support.apple.com/guide/mac-help/control-access-screen-recording-mac-mchld6aa7d23/mac
- ComposeNativeWebview：https://github.com/NucleusFramework/ComposeNativeWebview ；compose-webview-multiplatform：https://github.com/KevinnZou/compose-webview-multiplatform ；KCEF（已归档）：https://github.com/DatL4g/KCEF ；JetBrains JCEF：https://github.com/JetBrains/jcef ；JetBrains Runtime：https://github.com/JetBrains/JetBrainsRuntime/releases
- Ktor：https://ktor.io/docs/releases.html ；SQLDelight：https://github.com/sqldelight/sqldelight/releases ；Coil：https://github.com/coil-kt/coil ；markdown-renderer：https://github.com/mikepenz/multiplatform-markdown-renderer ；quickjs-kt：https://github.com/dokar3/quickjs-kt ；Koin：https://github.com/InsertKoinIO/koin
- java-keyring：https://github.com/javakeyring/java-keyring ；JediTerm：https://github.com/JetBrains/jediterm
