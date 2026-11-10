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

## 第 1 波需要主会话运行的测试

- `:app:testSideloadDebugUnitTest --tests 'com.promenar.infinity.domain.pet.*'`
- `:app:testSideloadDebugUnitTest --tests '*UserSettingsHomeScreenContractTest' --tests '*SettingsPaneTest' --tests '*SettingsNavigationContractTest' --tests '*BackupPreferenceInventoryTest' --tests '*PetPreferenceStoreTest'`
- `:app:compileSideloadDebugKotlin :app:compilePlayDebugKotlin`
