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

六波的代码，以及契约 v0.3 的阶段 1 与阶段 2 的栖息点部分（见下方“v0.3 实现状态”），都已写入本分支，**全部未经编译和运行**。主会话回收后需要先编译，再跑测试，再真机验收。

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
- 数据通道目前提供 `session.title`、`model.name`、`output.chars`、`elapsed.s`、`ask.text`、`idle.s`、`daypart`；`tool.target`、`error.kind` 未接入。工具名取自最新助手消息的最后一个工具调用，以 `task.tool.<工具名>` 进入信号回退链；`tool.name` 没有作为台词数据提供。
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

## v0.3 实现状态（2026-11-10）

按 [契约 v0.3 增补](20261110-pet-base-contract-v0.3-draft.md) 实现了阶段 1 全部内容和阶段 2 的栖息点，与前六波一样未经编译和运行。

| 部分 | 位置 | 说明 |
|---|---|---|
| 契约 0.3 | `domain/pet/` | 映射键限定（`@perch`、`@long`、`@verylong`、`@mood.high`、`@mood.low`）、事件回退（`touch.poke.*` → `touch.poke` → `touch.tap`）、工具类别、`zones`、`props.perch`、`haptic`、`PetMood`；0.2 的包照常加载 |
| 物理 | `ui/pet/PetEngine.kt` | 松手速度三档（下落或落座、抛出反弹、甩出收起）、唤回、倾斜打滑、摇晃、连点不耐烦、键盘弹出、栖息点（提起时显示提示、落座、戳破、吹散、恢复） |
| 运行时与图层 | `ui/pet/PetRuntime.kt`、`PetOverlay.kt`、`PetPerchPainter.kt`、`PetSensors.kt` | 触感、心情及其保存、栖息点位置保存、收起提示气泡（最多 3 次）、加速度计（倾斜与摇晃）、麦克风音量（吹气）、抚摸与分区点按手势 |
| 偏好 | `data/pet/PetPreferenceStore.kt` | 甩出收起与摇晃唤回、倾斜与摇晃、触感、栖息点样式、吹气五项随备份；收起状态、提示次数、栖息点位置、心情只留本机 |
| 设置 | `ui/settings/PetSettingsScreen.kt` | 收起时的“唤回宠物”、互动三个开关、栖息点样式、吹气开关（打开时申请麦克风权限，被拒绝则保持关闭） |
| Agent 工具 | `ui/chat/manager/skills/PetSkill.kt` | `pet`：`summon`、`dismiss`、`status`；不改总开关 |
| 内置 Skill | `assets/builtin_skills/pet-pack-authoring/SKILL.md` | 契约 0.3 字段与“玩法与操作”一章 |
| 权限 | `AndroidManifest.xml` | 新增 `RECORD_AUDIO`（两个渠道都有），麦克风硬件声明为非必需 |

本阶段没有实现的部分：

- 宠物包 `props.perch` 的 `appear`、`vanish` 片段没有播放，栖息点的出现和消失只用基座的默认动画；包提供的栖息点图片会显示。
- 映射键的 `@message`、`@stage` 能通过校验，但消息上沿落座和空会话页舞台还没有做。
- 小分身、击掌交接、台词记忆、作息、亲密度、应用外形态没有做。
- 映射里的 `haptic` 只对交互与物理事件生效（点按、抚摸、抛掷、落地、摇晃、栖息点等）；`task.*`、`role.enter` 等状态映射上声明的 `haptic` 不触发。
- `touch.bounce`、`place.perch.pop`、`place.perch.blown` 只触发触感和台词，映射里的 `clip` 不播放。
- 麦克风在键盘弹出时释放；外接键盘下输入框获得焦点时不释放。
- `pet` 工具的 `status` 返回默认宠物的名字，按模型关联切换的角色不体现；`summon`、`dismiss` 只改收起状态，风险级别为免审批。
- 宠物触感直接调用视图触感反馈的三种常量（轻点、闷响、长振），没有新增 `InfinityHapticEvent`；外观里关闭触感反馈时宠物也不振动。

需要真机确认的风险点（在上面六条之外）：

1. 抛掷三档的速度阈值（900、2600 dp/s）手感是否合适，是否容易误收起；阈值在 `PetEngine` 的常量里。
2. 轻摇与用力摇晃的识别阈值，走路、放进口袋时是否误触发；ColorOS 上页面在前台时加速度计是否正常回调。
3. 点按、长按提起、头部来回滑动（抚摸）三种手势的区分是否可靠。
4. 栖息点：提起时提示是否明显，落座位置、重新进入会话后的恢复位置是否正确；七彩泡泡能否点破；横竖屏和折叠屏展开后位置是否合理。
5. 吹气：识别阈值、状态栏麦克风指示、键盘弹出或页面切走后是否释放麦克风、实际耗电。
6. 触感三种强度在真机上的区别是否明显（ColorOS 振动取证方法见记忆）。
7. `pet` 工具唤回或收起后，会话页是否立即反映。

## v0.3 补充（2026-11-10，0.44.0-beta 真机反馈之后）

0.44.0-beta 已合并 v0.3 并装真机，抛掷、摇晃、吹气的阈值手感通过。真机反馈的问题和这一轮的处理，同样未经编译和运行：

| 问题 | 处理 |
|---|---|
| 倾斜、连点、发晕、唤回等新信号没有专属动作 | 原因是测试包的 `map` 没有配置 v0.3 的信号。测试包升到 0.2.0（契约 0.3）：新增 9 个姿势（打滑、不耐烦、被摸头、被戳头、怕痒、尾巴被碰、被抛飞、头晕、飞走），补齐全部玩法信号的映射、身体分区、`idle@perch`、心情与长任务限定的示例和兜底台词 |
| 撞边、戳破、吹散、唤回下落没有地方播放片段 | `PetEngine`：飞行中撞边后显示 `touch.bounce` 片段约 0.2 秒；戳破、吹散后的下落途中显示对应信号的片段；唤回下落途中显示 `touch.throw` 的片段 |
| 从云朵慢慢拖到屏幕下方松手，宠物回到云朵上 | 我和独立审阅者读代码都没有找到确定的成因：只要有一次拖动事件把位置带到下方，松手只会下落。剩下三种可能，都要求最后的云朵就是原来那朵：提起后没有拖动事件到达、手势被系统或上层取消（原先不会松手，宠物停在被提起的状态，原云朵保持高亮）、模拟按下没有点中宠物（触摸区域只覆盖头顶到落座线，点在云朵下半部不算）。这一轮让手势被取消时也执行松手，松手速度改用触摸事件自身的时间计算；现象本身需要真机带日志复测 |
| 重建宠物时任务停在完成或失败，心情重复加减 | 运行时第一次看到的任务状态若已是终态，不再表现也不再记心情 |

这一轮新增：

- **空会话页舞台**：会话没有消息时映射优先使用 `@stage` 限定，进入和离开时发出 `place.stage.enter`、`place.stage.leave`。平台仍是浮岛上沿。
- **台词记忆**（契约第 8 节）：`domain/pet/PetMemory.kt`、`data/pet/PetMemoryStore.kt`。进入会话时记一次到访，一轮任务结束时记下结果；数据通道新增 `memory.session.last`、`memory.session.visits`、`memory.last_result`、`memory.streak.days`。只存会话标识、时间和结果，保存在本机独立的偏好文件 `infinity_pet_memory`。
- 内置 Skill 的玩法信号改成配置表：触发条件、片段怎么用、未配置时的表现、建议姿势。

仍未实现，原因如下：

- **消息上沿落座**：需要从会话列表取最后一条消息的位置并处理滚动，跳上跳下的动画也没有定稿，按约定先出原型确认。
- **小分身、击掌交接**：基座目前只支持一个实例，交接动画需要新的素材约定，先出原型。
- **作息限定、亲密度**：契约里写明细节在实现该阶段时定，需要用户先定规则。
- **应用外形态**：下一阶段的核心，悬浮窗等需要新增权限，单独确认。
- 宠物包 `props.perch` 的 `appear`、`vanish` 片段仍未播放。

这一轮需要真机确认：

1. 新姿势在各信号下的落点是否正确（脚底是否贴住浮岛）、飞行中旋转的观感、打滑时镜像方向。
2. 头、肚子、尾巴三个分区是否点得准；头部来回滑动是否稳定触发抚摸。
3. 从云朵拖到下方松手是否落回浮岛；拖动中系统手势打断后宠物是否正常落下。
4. 松手速度改用触摸事件时间后，三档抛掷的手感是否仍与 0.44.0 一致。
5. 空会话进入、发出第一条消息时宠物的反应；`memory.*` 是否进入模型台词。

测试包：共享笔记目录 `pet-research/pack/whale-maid.idpet`（0.2.0，约 4.4 MB，49 个文件），旧版保留为 `whale-maid-0.1.0.idpet`；新姿势素材在 `pet-research/2d/gen/e7-play/`，构建脚本 `pet-research/pack/build_pack.py`。导入前先删除应用里的旧包或直接覆盖导入。

## 后续阶段（2026-11-10 用户确认的顺序）

接口见 [契约 v0.3 增补](20261110-pet-base-contract-v0.3-draft.md)。阶段 1 和阶段 2 的栖息点已按用户要求提前写完，等阶段 0 一并验收；其余阶段先出浏览器原型给用户确认，再实现。

| 阶段 | 内容 | 依赖 |
|---|---|---|
| 0 | 主会话编译、测试、真机验收现有基座 | — |
| 1 | 物理玩法：抛掷与反弹、甩出收起与摇晃唤回、倾斜与摇晃、抚摸与分区点按、触感、键盘弹出；心情；工具专属表演与长任务递进；Agent 宠物工具；内置 Skill 的“玩法与操作”一章 | 阶段 0 |
| 2 | 多平台：上方栖息点（云朵、七彩泡泡）、生成结束后的消息上沿落座、空会话页舞台 | 阶段 1 的抛掷落点判定 |
| 3 | 多角色同屏：小分身、击掌交接 | 基座支持多个实例 |
| 4 | 台词记忆、作息、亲密度 | 阶段 1 的心情 |
| 5 | 应用外：桌面小组件、通知头像、悬浮窗 | 悬浮窗需新增权限，单独确认 |

备忘：竞技同台等竞技场功能定型后再设计。

### 2026-11-10 用户决策后的范围

| 项 | 决定 | 状态 |
|---|---|---|
| 空会话舞台扩展、消息上沿落座 | 先不做，保留现有 `@stage` 与 `place.stage.*` | — |
| 小分身 | 做：派出时身旁分出小号的自己，沿浮岛跑出屏幕，结束时跑回；不常驻、不可点按 | 契约 7.2 已改写，原型待确认，代码未写 |
| 角色交接 | 做：新角色在烟雾里出现，两个角色隔空挥手，旧角色在烟雾里消失（用户看过原型后否决了击掌） | 暂缓：用户 2026-11-11 决定先搁置，等第二角色素材做完整后再议；契约 7.1 保留为设计记录 |
| 作息 | 做：早晨 6–11、白天 11–18、晚上 18–23、深夜 23–6；限定后缀与 `{daypart}` | 已实现（`PetDaypart`、解析、校验、运行时），未编译 |
| 亲密度、应用外形态、栖息点出现与消失片段 | 先不做 | — |
| 删除会话时清理台词记忆 | 做 | 已接入 `InfinityApplication.deleteSessionRecoverably`，未编译 |

子代理的开始与结束可以从 `LocalSubagentRuns`（`SubagentRunsAccess.observe(stepId)` 返回的 `SubagentBatch`，各 `SubagentRun.status`）得到；挥手交接需要 `PetOverlay` 在切换角色时同时持有新旧两个角色的素材。

已确认：“吹气吹散云朵”新增 `RECORD_AUDIO` 权限，默认关闭，宠物在云朵上且会话页在前台期间保持监听；耗电和误触发在真机实测后再定是否收窄监听时段。

## 需要主会话运行的测试

- `:app:compileSideloadDebugKotlin :app:compilePlayDebugKotlin :app:compileSideloadDebugUnitTestKotlin`
- `:app:testSideloadDebugUnitTest --tests 'com.promenar.infinity.domain.pet.*' --tests 'com.promenar.infinity.data.pet.*' --tests 'com.promenar.infinity.ui.pet.*'`
- 补充一轮新增的测试：`--tests '*PetMemoryTest' --tests '*PetMemoryStoreTest'`，并重跑 `*PetEngineTest`、`*PetFrameSourceContractTest`、`*BackupPreferenceInventoryTest`。
- v0.3 新增或改动的测试：`--tests '*PetEngineTest' --tests '*PetSensorsTest' --tests '*PetV03Test' --tests '*PetV03PreferenceTest' --tests '*PetSkillTest' --tests '*ToolInvocationPresentationTest' --tests '*BuiltinToolPresentationTest' --tests '*SettingsViewModelTest'`；新增权限后再跑 `python3 scripts/ci/check-distribution-manifests.py`。
- `:app:testSideloadDebugUnitTest --tests '*UserSettingsHomeScreenContractTest' --tests '*SettingsPaneTest' --tests '*SettingsNavigationContractTest' --tests '*BackupPreferenceInventoryTest' --tests '*BuiltinSkillCatalogTest' --tests '*SkillSourceGroupsTest' --tests '*KgTokenStatsRepositoryTest' --tests '*RolledBackSummaryUsageStoreTest'`
- 设置首页多了一个条目，`SettingsPagesBatch1ScreenshotTest`、`ReleasePreviewScreenshotTest` 的参考图可能需要更新。
- 作息与清理记忆：`--tests '*PetV03Test' --tests '*PetEngineTest' --tests '*PetMemoryStoreTest'`。
- 功能验收通过后补 `CHANGELOG.md` 的 `[Unreleased]`、README 的功能说明和 `docs/ARCHITECTURE.md`。
