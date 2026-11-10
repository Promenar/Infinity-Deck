# 会话宠物实施计划

> 日期：2026-11-10
> 依据：[宠物基座契约规格（草案 v0.2）](20261110-pet-base-contract-draft.md)
> 分支：`claude/pet-research-036e59`；本分支只写代码与测试，不运行 Gradle，由主会话回收后统一编译、测试和真机验收

## 原则

- 宠物功能默认关闭，在设置首页“通用”分组的“外观”之后新增入口“宠物”。
- 只有宠物响应界面，没有界面响应宠物：宠物、气泡和效果画在浮岛上方的独立图层，不改浮岛。
- 应用不内置形象；开发期用鲸鱼女仆测试包验收，测试包不进仓库。
- 不新增第三方依赖。视频片段用已接入的 media3。

## 分波

| 波次 | 内容 | 执行者 | 主要文件 |
|---|---|---|---|
| 1A | 契约领域层：manifest 与 persona 模型和解析、校验、信号回退解析、生成阶段到信号的映射、台词模板与限流 | Codex | `domain/pet/**`、`test/.../domain/pet/**` |
| 1B | 设置入口“宠物”、总开关（默认关）、存储、导航、备份白名单、契约测试更新 | Claude | `ui/settings/PetSettingsScreen.kt`、`data/pet/PetPreferenceStore.kt`、`NavGraph.kt`、`SettingsPane.kt`、`UserSettingsHomeScreen.kt`、`res/values*/pet_strings.xml` |
| 2 | 宠物包存储：SAF 导入 zip、暂存、校验、启用、删除、导出；设置页的包管理 | Codex（存储）、Claude（界面） | `data/pet/PetPackageStore.kt`、`PetSettingsScreen.kt` |
| 3 | 基座渲染：浮岛上方图层、姿势图与逐帧播放、表情替换、网格变形与弹簧、状态机与空闲行为调度、气泡与兜底台词 | Claude | `ui/pet/**`、`ChatScreen.kt`（一处插入） |
| 4 | 交互与物理：提起与下落、推挤、浮岛宽度变化、入场离场、折叠屏 | Claude | `ui/pet/**` |
| 5 | 模型台词与模型关联：台词模型设置、无工具短请求、用量账本、按会话模型切换角色 | Codex（请求与账本）、Claude（设置界面） | `data/pet/PetLineGenerator.kt`、`TokenStatsRepository.kt`、`PetSettingsScreen.kt` |
| 6 | 视频片段与降级：透明视频播放、省电与“移除动画”降级、内置制作 Skill、文档与 CHANGELOG | Claude、Codex | `ui/pet/PetVideoClip.kt`、内置 Skill 资源 |

每一波结束后提交推送本分支，并列出需要主会话运行的测试。第 3 波起需要真机验收帧率、内存与功耗。

## 接入点（已读代码核对）

- 设置入口：`ui/hub/UserSettingsHomeScreen.kt` “通用”分组，外观条目之后；路由在 `navigation/NavGraph.kt` 的 `NavDestinations` 与 `settingsDestinations`，并登记到 `navigation/SettingsPane.kt` 的 `SettingsPaneRoots`。
- 设置存储：SharedPreferences `infinity_settings`；键需登记到 `data/backup/BackupPreferencePolicy.kt`。
- 浮岛：`ui/chat/ChatScreen.kt` 中包裹 `ChatInputBar` 的 `Box`，宠物图层作为同级元素叠在其上。
- 任务状态：`ChatViewModel.generationPhase`；等待用户由审批请求、子代理审批、待回答提问、待输入密钥四项合成。
- 无工具短请求：`data/generation/RoutedToolResultSummarizer.summarizePrompt`；用量统计需新增分类。
- 导入范例：`data/skills/AgentSkillStore.installZip`。

## 实现状态（2026-11-10）

六波的代码都已写入本分支，**全部未经编译和运行**。主会话回收后需要先编译，再跑测试，再真机验收。

| 部分 | 位置 | 说明 |
|---|---|---|
| 契约领域层 | `domain/pet/` | 解析、校验、信号回退、任务状态映射、台词模板与限流 |
| 数据层 | `data/pet/` | `PetPackageStore`、`PetPreferenceStore`、`PetLineGenerator`；用量计入 `pet` 分类 |
| 基座 | `ui/pet/` | `PetEngine`（状态机与平台物理，纯 Kotlin）、`PetMesh`（网格变形）、`PetAssets`、`PetRuntime`、`PetOverlay`、`PetVideoLayer` |
| 会话页接入 | `ui/chat/ChatScreen.kt` | 记录浮岛范围；在聊天区域上叠加 `PetOverlay` |
| 设置 | `ui/settings/PetSettingsScreen.kt` | 总开关、宠物包导入/设为默认/导出/删除、模型关联、台词开关与模型、视频片段开关 |
| 内置 Skill | `assets/builtin_skills/pet-pack-authoring/` | 宠物包制作规范 |

与规格的出入和已知缺口：

- 宠物包内容不随备份导出，只有偏好随备份走；恢复到新设备后需要重新导入宠物包。
- 数据通道目前提供 `session.title`、`model.name`、`output.chars`、`elapsed.s`、`ask.text`、`idle.s`、`daypart`；`tool.name`、`tool.target`、`error.kind` 未接入，`task.tool.<工具名>` 也只给到 `task.tool`。
- 事件信号 `island.fx.*`、`island.focus`、`island.blur`、`island.send`、`device.fold`、`device.unfold`、`session.other.active`、`env.theme.*` 未接入；折叠屏展开与侧边栏通过浮岛宽度和边缘位置的变化体现（`island.wider`、`island.narrower`、`push.*`）。
- 视频片段只在 Android 13 及以上播放（用着色器换出透明通道），更低版本播放替补片段。视频不做网格变形。
- 位图按需解码后常驻内存，直到切换角色；没有淘汰策略。

需要真机确认的风险点：

1. `Canvas.drawVertices` 带位图着色器的绘制是否正确、是否硬件加速。
2. 宠物触摸区域在图层内按帧移动后，点按、长按提起、拖动、松手是否都能收到；宠物以外的区域是否仍能滚动聊天列表、点按消息。
3. `TextureView` 上的 `RenderEffect` 着色器能否抠出透明；ExoPlayer 循环播放是否有卡顿。
4. 帧率、内存（位图缓存）、功耗；页面切走后是否停帧。
5. 键盘弹出、检视器固定、内屏侧边栏展开时浮岛范围是否正确，推挤是否触发。
6. 台词模型的真实输出与 256 token 上限是否合适，用量是否记入“宠物台词”。

开发验收用的鲸鱼女仆测试包在本机共享笔记目录 `pet-research/pack/whale-maid.idpet`（约 4.1 MB，含 26 个姿势、2 个逐帧片段、1 个视频片段、8 个表情和角色配置单），不进仓库。验收步骤：设置 → 宠物 → 导入宠物包 → 打开“显示宠物” → 进入会话页。

## 后续阶段（2026-11-10 用户确认的顺序）

接口见 [契约 v0.3 增补](20261110-pet-base-contract-v0.3-draft.md)。阶段 0 通过后再开始写代码；每个阶段先出浏览器原型给用户确认，再实现。

| 阶段 | 内容 | 依赖 |
|---|---|---|
| 0 | 主会话编译、测试、真机验收现有基座 | — |
| 1 | 物理玩法：抛掷与反弹、甩出收起与摇晃唤回、倾斜与摇晃、抚摸与分区点按、触感、键盘弹出；心情；工具专属表演与长任务递进；Agent 宠物工具；内置 Skill 的“玩法与操作”一章 | 阶段 0 |
| 2 | 多平台：上方栖息点（云朵、七彩泡泡）、生成结束后的消息上沿落座、空会话页舞台 | 阶段 1 的抛掷落点判定 |
| 3 | 多角色同屏：小分身、击掌交接 | 基座支持多个实例 |
| 4 | 台词记忆、作息、亲密度 | 阶段 1 的心情 |
| 5 | 应用外：桌面小组件、通知头像、悬浮窗 | 悬浮窗需新增权限，单独确认 |

备忘：竞技同台等竞技场功能定型后再设计。

待用户确认：

- **麦克风权限**。“吹气吹散云朵”需要 `RECORD_AUDIO`，应用目前没有声明。Android 在麦克风使用期间会在状态栏显示绿色指示。如果宠物在云上时一直监听，指示会一直亮着；建议改成点一下云朵后只监听几秒。

## 需要主会话运行的测试

- `:app:compileSideloadDebugKotlin :app:compilePlayDebugKotlin :app:compileSideloadDebugUnitTestKotlin`
- `:app:testSideloadDebugUnitTest --tests 'com.promenar.infinity.domain.pet.*' --tests 'com.promenar.infinity.data.pet.*' --tests 'com.promenar.infinity.ui.pet.*'`
- `:app:testSideloadDebugUnitTest --tests '*UserSettingsHomeScreenContractTest' --tests '*SettingsPaneTest' --tests '*SettingsNavigationContractTest' --tests '*BackupPreferenceInventoryTest' --tests '*BuiltinSkillCatalogTest' --tests '*SkillSourceGroupsTest' --tests '*KgTokenStatsRepositoryTest' --tests '*RolledBackSummaryUsageStoreTest'`
- 设置首页多了一个条目，`SettingsPagesBatch1ScreenshotTest`、`ReleasePreviewScreenshotTest` 的参考图可能需要更新。
- 功能验收通过后补 `CHANGELOG.md` 的 `[Unreleased]`、README 的功能说明和 `docs/ARCHITECTURE.md`。
