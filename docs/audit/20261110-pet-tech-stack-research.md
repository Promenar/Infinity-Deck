# 会话宠物技术选型调研

> 日期：2026-11-10
> 范围：Infinity Deck 会话宠物（第一阶段：会话页输入框浮岛上的宠物；后续阶段：全局悬浮窗宠物）
> 状态：调研稿，方向待用户确认，未改产品代码
> 首个形象：DeepSeek 大肥鱼（蓝色鲸鱼女仆 Q 版），设定图在本机 `artifacts/pet-reference/deepseek-whale-maid-sheet.webp`（不入库）

## 1. 结论

推荐方案：**3D 卡通渲染角色**。运行时用 Filament（Apache-2.0），素材用 VRM 格式：VRoid Studio 起模，Blender 补鲸鱼尾、鲸鳍耳、女仆头饰；自研一层 VRM 支持，包括 SpringBone 物理、Expression 表情、LookAt 视线和卡通着色。状态机与程序化运动层与渲染无关，直接沿用浏览器原型的设计。

理由：

1. **动作差异大。** 宠物的 9 个状态是全身动作：坐着吃饭、站着干活、跳起欢呼、趴着睡觉。2D 骨骼在同一视角内做微动很自然，可大幅换姿势就要为每个姿势另画部件、另做绑定。3D 一套模型就能覆盖全部动作，靠动画混合连续过渡，转身、低头吃饭、手拿筷子送到嘴边都不用额外画图。
2. **表情和物理有标准件。** VRoid 模型自带眨眼、口型（a/i/u/e/o）和喜怒哀惊等标准表情，头发 SpringBone 也会自动生成。这些正好是 Codex 宠物没有、单图原型也做不到的部分。
3. **形象系列化成本低。** 后续要出其它“模型拟人”角色，VRoid 加 Blender 的单角色成本远低于每个角色重新分层、重新绑定 2D 骨骼。
4. **授权干净。** Filament 是 Apache-2.0，与仓库的 GPLv3 兼容；Spine 运行时和 Live2D Core 是专有许可，与 GPLv3 公开仓库有冲突（见 4.1）。
5. **悬浮窗可用。** Filament 不依赖 Activity，可以在 Service 的 `TYPE_APPLICATION_OVERLAY` 窗口里用透明 `TextureView` 渲染（合理推断，待真机验证）。

主要风险与对应的验证：

- **画风还原度。** 3D 卡通渲染能否达到设定图那种插画的可爱度，是最大的未知数。先用浏览器做 3D 原型（three.js + three-vrm），和 2D 原型放在同一个浮岛场景里对比，再决定。
- **自研工作量。** Kotlin + Filament 没有现成的 VRM 实现，VRM 解析、表情映射、SpringBone、LookAt、卡通材质估计 3 到 6 人周（推断）。可参考 flutter_vrm（MIT）和 three-vrm 的实现。
- **包体。** filament-android 与 gltfio-android 的 AAR 合计约 11 MB（含全部 ABI，已核验 1.77.3 版），按 ABI 拆分后单个 APK 增量更小（各 ABI 实际大小未核实）。
- **功耗。** 空闲时降到 15 到 30 fps，宠物静止或不可见时停帧，真机测功耗后定档。

备选方案：**Rive**。运行时 MIT，有官方 Compose 组件（Beta），内置状态机与数据绑定，arm64 包体约 2.4 MB。头发、尾巴的物理要用 Luau 脚本自写。适合 3D 原型对比后觉得 2D 插画质感更重要的情况，素材用 AI 分层部件。

不推荐：Spine 与 Live2D（GPL 兼容性与开源协作者授权问题）、Godot 嵌入（AAR 103 MB，单实例）、Unity 嵌入（只能全屏，不能用于悬浮窗）、DragonBones（停维）、纯序列帧（就是 Codex 的做法，过渡与物理都做不到）。

第一阶段 MVP 与素材清单见第 7 节。

## 2. 对标对象：Codex 宠物

已核验（来源：[openai/skills hatch-pet SKILL.md](https://github.com/openai/skills/blob/main/skills/.curated/hatch-pet/SKILL.md)）：

- 形式是精灵图集：`spritesheet.webp` 为 8 列 × 9 行，每格 192×208，配 `pet.json`。
- 9 个状态（idle、running-right/left、waving、jumping、failed、waiting、running、review），每个状态 4 到 8 帧，逐帧时长。
- 生成方式：生图模型先出基准形象，再逐行生成动作条，在色键背景上抠像后拼图集。

这意味着 Codex 宠物的观感上限由“低帧数逐帧 + 状态硬切”决定：没有骨骼过渡，没有物理次级运动，动作之间直接跳帧。要明显超过它，至少需要三样东西：连续的程序化运动（呼吸、头部、身体）、跟随运动滞后摆动的头发与尾巴、状态之间的平滑过渡。

## 3. 浏览器原型的结论

原型：[鲸鱼女仆宠物原型](https://claude.ai/artifact/YFji8kFE5JkEyxrk5aK81h)（claude.ai 私有页面；本地副本在共享笔记目录 `pet-research/pet-proto.html`）。做法是从设定图直接抠出 7 张单图，每张套 29×29 顶点网格，用程序化运动、4 组弹簧（左右头发、尾巴、呆毛）和状态机驱动，切换状态时做交叉淡化与弹簧回弹。

已验证：

- 单图网格变形加弹簧，已经能做出呼吸、歪头、点头、跳跃、抖动，以及头发、尾巴、呆毛的滞后摆动；关闭“物理摆动”后的僵硬感对比明显。
- 关闭“弹簧过渡”即为逐状态硬切，可以直接对比 Codex 式切换的观感。
- 每帧变形 841 个顶点，浏览器内 JS 耗时约 0.14 ms，运动层本身的开销可以忽略。

单图方案做不到的（这些决定了正式方案必须用分层素材）：

- 眨眼、口型、表情渐变：眼睛和嘴没有独立图层。
- 头发和身体的遮挡关系：后发、侧发、前发压在同一张图上，摆幅一大就会把身体一起拉扯变形。
- 姿势之间只能交叉淡化，无法连续地从“抱枕”过渡到“吃饭”。
- 分辨率：素材约 190 像素高，放到手机 3 倍密度会发虚。正式素材需要按显示尺寸的 3 倍以上出图。

原型里的运动层（弹簧、程序化呼吸与头部运动、状态到动作的映射、过渡策略）与渲染方案无关，正式实现时可以原样迁移到任何方案。

## 4. 方案比较

### 4.1 授权前提：仓库是 GPLv3 公开仓库

已核验：仓库根 `LICENSE` 为 GPLv3，GitHub 仓库 `Promenar/Infinity-Deck` 为公开；提交历史中有多位作者。

这一条会直接筛掉部分方案。GPLv3 要求随二进制提供完整对应源码，专有闭源库与 GPLv3 代码链接分发存在兼容性问题；作者可以为自己的代码加“附加许可”（GPLv3 第 7 条）放行特定库，但需要全部版权人同意。以下判断属于合理推断，不是法律意见，正式采用专有运行时前需要单独确认。

| 运行时 | 许可证 | 与 GPLv3 仓库 |
|---|---|---|
| Rive（rive-android） | MIT | 兼容 |
| Lottie / dotLottie | Apache-2.0 / MIT | 兼容 |
| Filament / SceneView | Apache-2.0 | 兼容 |
| Godot | MIT | 兼容 |
| Spine 运行时 | Spine Runtimes License（专有） | 需附加许可，且每个修改集成的人需持有编辑器授权 |
| Live2D Cubism Core | Live2D Proprietary Software License | Core 不得进入开源仓库，不得套用开源许可；需附加许可 |
| Unity as a Library | Unity 专有条款 | 需附加许可，包体也过大 |

### 4.2 2D 方案

来源均为官方页面，详见第 8 节。

| 方案 | 自然度上限 | 状态机 | Compose 集成 | 包体 | 费用 | 主要风险 |
|---|---|---|---|---|---|---|
| Live2D Cubism | 最高，二次元角色的业界标准；眨眼、呼吸、物理都是标准参数 | 需自写 | GL 视图经 `AndroidView` 嵌入，需自己封装 | 原生 Core 库 | SDK：年营收低于 1000 万日元免签发布授权；编辑器 PRO 约 90 美元/年（免费版限 100 个 ArtMesh、30 个参数） | Core 不能进仓库，CI 与他人构建要手动下载；GPL 兼容性 |
| Spine 4.2+ | 高；物理约束可做头发、衣物次级运动（仅 Pro） | 需自写，AnimationState 负责动画混合 | `SpineView` 经 `AndroidView` 嵌入，Canvas 渲染 | 小 | Pro 379 美元一次（Essential 不能用网格和物理） | 专有运行时许可；GPL 兼容性；运行时版本须与编辑器一致 |
| Rive | 中高；骨骼、网格加权、IK 等约束；无内置物理，可用 Luau 脚本写弹簧 | 内置状态机 + 数据绑定（数字、布尔、触发器、枚举等） | 官方 `Rive` composable（Beta，官方称可用于生产） | arm64 下载增加约 2.4 MB | 运行时 MIT；编辑器免费版导出带启动画面，Cadet 9 美元/月去掉 | 位图角色的网格绑定能力弱于 Spine/Live2D；物理要自己写 |
| dotLottie | 低；矢量动画为主 | 有 | 官方 composable | 小 | MIT | 不适合位图骨骼角色 |
| lottie-compose | 低 | 无，只能按片段播放 | 原生 | 小 | Apache-2.0 | 同上 |
| DragonBones | — | — | Java 运行时 2017 年后无更新 | — | — | 实际停维，排除 |
| Compose 原生分层网格（自研） | 中高；分层素材 + `Canvas.drawVertices` 网格变形 + 自写弹簧，上限取决于自研绑定工具 | 自写，可直接复用浏览器原型的运动层 | 原生，零依赖，悬浮窗同样可用 | 零 | 无 | 没有编辑器，绑定参数靠 JSON 和调参界面；工作量集中在工具链 |
| 序列帧（Codex 式） | 低到中；帧数受内存限制，动作间硬切 | 自写 | 原生 | 取决于帧数与分辨率 | 生图成本 | 角色一致性；内存；无物理 |

### 4.3 3D 方案

| 方案 | 自然度上限 | 状态机/过渡 | Compose 集成 | 悬浮窗 | 包体增量 | 费用 | 主要风险 |
|---|---|---|---|---|---|---|---|
| Filament + VRM（自研 VRM 层） | 高 | gltfio Animator 支持交叉淡化；状态机自写 | 透明 `TextureView` 经 `AndroidView` 嵌入 | 可行（推断） | AAR 约 11 MB（全部 ABI） | 无 | VRM 层和卡通着色需自研；新版本有透明背景回归报告（[#7220](https://github.com/google/filament/discussions/7220)） |
| SceneView（Filament 的 Compose 封装） | 同上 | 同上 | `SceneView { }` composable | 同上 | 额外约 4 MB | 无 | 透明背景支持未核实；封装层更新频繁 |
| Godot 4 嵌入 | 高 | 引擎自带 | GodotFragment，每进程单实例 | 不宜（需 Activity 宿主） | AAR 103 MB | 无（MIT） | 包体；3D 透明背景仍有问题报告 |
| Unity as a Library | 最高 | 引擎自带 | 只能全屏 | 不可 | 30 MB 以上 | 年收入 20 万美元以下免费 | 全屏限制、内存占用、专有许可 |

已核验的要点：

- Filament：glTF 蒙皮、morph target（含 sparse accessor）、关节动画都支持。卡通光照可以用材质的 `customSurfaceShading` 写二值光照，描边用反向外壳法（`culling: front` 加顶点外扩）。`UiHelper.setOpaque(false)` 对 `TextureView` 设置透明。嵌在 Compose 层级中间应选 `TextureView`，因为置顶的 `SurfaceView` 会盖住上层 UI（推断）。
- VRM 1.0 分为 VRMC_vrm（人形骨骼、Expression、LookAt）、VRMC_springBone、MToon 材质三部分；VRMA 动画格式已正式发布，可携带骨骼、表情和视线动画。
- Kotlin + Filament 没有现成的 VRM 实现。flutter_vrm（MIT，早期版本）实现了 MToon、SpringBone、表情、视线和 VRMA，可作为参考。

3D 素材管线：

- **VRoid Studio：** 免费，二次元风格人形起模；官方指南说明自建模型导出后默认可商用（原页面返回 403，依据检索摘要，待复核）。v1.27 起有猫、狐、兔尾巴配件。鲸鱼尾和鲸鳍耳在 Blender 里建模，用 VRM Add-on 挂 SpringBone。
- **AI 图生 3D：** 输出多为高面数三角网格，不带表情 morph，要重拓扑后才能做面部表情（推断），适合生成道具（饭碗、筷子、鲸鱼玩偶、枕头），不适合直接做角色本体。各工具的授权：
  - Meshy：免费层输出为 CC BY 4.0，Pro 每月 20 美元输出私有。
  - TRELLIS.2：MIT，可本地跑，约需 24 GB 显存。
  - Hunyuan3D 2.1：社区许可排除欧盟、英国、韩国，全球分发的应用不宜采用。
  - Tripo：免费层许可未核实。
- **绑定与动作：**
  - AccuRIG 2.0 免费。
  - Mixamo 动作免版税，但中国区账号不可用。
  - VRMA 动作可以直接用。
  - 视频动作捕捉可用 Rokoko Vision 免费层。
  - Q 版大头比例重定向后手臂容易穿过头部，需要手动修（推断）。

## 5. 状态源与状态映射

已核验（读代码，路径相对 `native-ui/app/src/main/java/com/promenar/infinity/`）：

- 权威阶段是 `domain/generation/GenerationModels.kt` 的 `GenerationPhase`（QUEUED、PREPARING、BUILDING_CONTEXT、CONNECTING、THINKING、STREAMING、EXECUTING_TOOLS、WAITING_APPROVAL、WAITING_INPUT、POST_PROCESSING、COMPLETED、FAILED、CANCELLED、PERSISTENCE_FAILED）。
- 浮岛光效已有一层动效阶段：`ui/common/motion/GenerationMotion.kt` 的 `GenerationMotionPhase`（WAIT、STREAM、TOOL、APPROVAL、RETRY）和 `generationMotionPhase(phase, awaitingUser)`，并且已经合并了待审批、待回答提问与子代理审批。宠物状态应在这一层旁边扩展，保持与浮岛光效同源。
- `ChatViewModel` 的粗粒度 `GenerationStatus` 把 EXECUTING_TOOLS 也映射成 THINKING，宠物不应订阅它，应订阅 `generationPhase`。
- 思考与正文的区分：THINKING 在流开始时进入，第一个正文块到达时切到 STREAMING；只带 reasoning 的块也会切到 STREAMING，所以“思考”与“生成中”要再看 `GenerationSnapshot.reasoning` 与 `content` 哪个在增长。
- 跨会话全局状态：`generationCoordinator.sessions`（StateFlow，按会话的任务快照），子代理在 `SubagentLauncher.activeRuns`；`ui/hub/AgentHubScreen.kt` 的 `hubSessionActivities()` 已经是多会话投影，悬浮窗可直接复用。
- 跳转：`navigation/AndroidSessionOpenIntentFactory.kt` 生成打开指定会话的 PendingIntent（带一次性令牌），悬浮窗跳转会话走它。

宠物状态映射草案：

| 宠物状态 | 动作（第一款形象） | 数据源 |
|---|---|---|
| 空闲 | 抱鲸鱼玩偶，呼吸、轻微摇头 | 无进行中任务，或 CANCELLED |
| 准备 | 坐好、看向输入框 | QUEUED 到 CONNECTING |
| 思考 | 抱枕歪头，头顶问号与思考点 | THINKING，且 reasoning 在增长或尚无内容 |
| 生成中 | 吃白饭，咀嚼节奏跟随出字速率 | STREAMING 且正文在增长；POST_PROCESSING 复用 |
| 调用工具 | 站起来干活，头顶工具名标签 | EXECUTING_TOOLS，可叠加 `ToolLiveOutputStore.activeKeys` 取工具名 |
| 等待审批 | 惊讶举手，审批气泡闪烁 | WAITING_APPROVAL、`Session.approvalRequest`、子代理审批 |
| 等你回答 | 同上，气泡换成问号 | WAITING_INPUT、`pendingQuestion` |
| 完成 | 欢呼跳跃，持续约 2 秒后回空闲 | COMPLETED |
| 出错 | 委屈、抖动、汗滴 | FAILED、PERSISTENCE_FAILED；按 `GenerationFailureCode` 可细分限流、网络、鉴权 |
| 睡觉 | 趴睡，zZ | 现有代码没有字段；空闲计时（输入框最后交互、`Session.updatedAt`） |

挂载点：`ui/chat/ChatScreen.kt` 中浮岛所在的贴底 Column（带 `imePadding`、`widthIn(max = 720dp)`），放在 `ChatComposerStatusBar` 之前，宠物会自动跟随键盘与宽度变化。

## 6. 全局悬浮窗（后续阶段）

已核验（来源见第 8 节）：

- `SYSTEM_ALERT_WINDOW` 在 Play 属于特殊权限，没有专门的声明表单；要求只为没有替代方案的核心功能申请、引导用户到系统设置授权、拒绝后有替代方案。
- Android 14 起前台服务必须声明 `foregroundServiceType`；`specialUse` 要在 manifest 写 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，并在 Play Console 声明、接受审核。
- Android 15（targetSdk 35+）：只持有悬浮窗权限已经不能从后台启动前台服务，必须先有可见的 `TYPE_APPLICATION_OVERLAY` 窗口；`PendingIntent` 默认不带后台启动 Activity 的权限。

现状（读代码）：主 manifest 只有 `FOREGROUND_SERVICE_DATA_SYNC`，生成服务是 dataSync 类型，在等待审批和终态时停止；没有 `SYSTEM_ALERT_WINDOW`。侧载版 manifest 已有 `FOREGROUND_SERVICE_SPECIAL_USE`。

建议：悬浮窗先只做侧载版（权限放进 `src/sideload/AndroidManifest.xml`），Play 版用通知加系统气泡（Bubbles）承载同样的状态与跳转。悬浮窗的渲染方案要能放进 `WindowManager` 窗口：Compose 原生、Rive、Spine（Canvas）都没有问题；GL/3D 方案要用 `TextureView` 或 `SurfaceView` 并验证透明背景与功耗。

## 7. 第一阶段 MVP、素材清单与制作管线

### 7.1 MVP 范围

- 会话页浮岛上的宠物，一个形象（鲸鱼女仆）。
- 状态：空闲、准备、思考、生成中（吃白饭）、调用工具、等待审批、等你回答、完成、出错、睡觉，按第 5 节映射。
- 交互：
  - 点击摸头，有反应动作。
  - 长按收起。
  - 设置里可以关闭宠物。
- 跟随系统“移除动画”设置降级为静态姿势。
- 不可见（页面切走、应用进后台、浮岛收起）时停帧。
- 不在 MVP：全局悬浮窗、多形象、用户导入模型、宠物换装。

### 7.2 素材清单（3D 路线）

- **角色模型：** 一套 VRM，约 1 万到 2 万三角面，贴图合计 2048² 以内。
  - 部件：Q 版人形本体、长卷发（分 6 到 10 束挂 SpringBone）、鲸鳍耳（左右）、女仆头饰与蝴蝶结、呆毛、女仆裙与围裙（裙摆挂 SpringBone）、鲸鱼尾（3 到 4 节骨骼挂 SpringBone）。
- **表情：** 眨眼（左、右、双眼）、口型 a/i/u/e/o、开心、惊讶、委屈、困倦、咀嚼。
- **动作片段：**
  - 循环动作：坐姿呼吸、坐姿吃饭、站立干活、等待审批举手、趴睡。
  - 一次性动作：欢呼跳跃、出错抖动、被摸头。
  - 过渡动作：坐下、起身、躺下。
- **道具：** 白饭碗、筷子、鲸鱼玩偶、枕头、工具图标、审批气泡、zZ 与问号特效。

### 7.3 制作管线

1. **定稿形象：** 以设定图为准，用 GPT Image 2.5 补齐正、侧、背三视图和细节特写（头饰、尾巴、鞋）。GPT Image 2.5 支持 `background: "transparent"`。
2. **起模：** VRoid Studio 做本体、头发、服装，贴图可以用生图模型绘制后再手修。
3. **补部件：** 在 Blender 里建鲸鱼尾、鲸鳍耳、头饰，挂骨骼与 SpringBone，补齐表情 morph，导出 VRM。
4. **做动作：** 手 K 关键动作，或用视频动作捕捉打底再修，导出 VRMA 或 glTF 动画。
5. **出道具：** 用 AI 图生 3D 做道具（TRELLIS.2 本地或 Meshy Pro），重拓扑后贴卡通材质。
6. **真机调参：** 调节动画混合时长、SpringBone 参数、帧率档位和功耗。

### 7.4 AI 工具的授权提醒

| 工具 | 许可证 | 能否用于打包进应用的素材 |
|---|---|---|
| GPT Image 2.5（OpenAI） | OpenAI 服务条款，输出归用户 | 可以 |
| Qwen-Image-Layered | Apache-2.0 | 可以 |
| Qwen Image 2.1 | Qwen Research License，只允许“研究或评估”用途，商用需另行授权（已核验许可证原文） | 只用于构思和评估；正式素材改用其它工具，或先取得授权 |
| TRELLIS.2 | MIT | 可以 |
| Meshy | 免费层输出 CC BY 4.0，Pro 输出私有 | 用 Pro，或在应用里署名 |
| Hunyuan3D 2.1 | 社区许可，排除欧盟、英国、韩国 | 不建议，应用全球分发 |
| RMBG-2.0 | CC BY-NC 4.0 | 不可以；抠图用 BiRefNet（代码 MIT，权重许可未标明，待复核）或生成时直接出透明通道 |

“Codex Image 2.6”没有查到官方资料，未核实；如果它属于 OpenAI 图像模型系列，授权按 OpenAI 服务条款处理。

另外，形象以 DeepSeek 为原型，名称和鲸鱼标志涉及第三方商标，对外发布前需要确认商标使用方式。

### 7.5 下一步验证

1. 做 3D 浏览器原型：three.js + three-vrm，加载一个可用于测试的 VRM 样例模型，放进与 2D 原型相同的浮岛场景，对比卡通渲染质感、SpringBone 摆动、表情和动作过渡。
2. 用户在两个原型之间定方向。
3. 定方向后再做 Android 技术验证：
   - 3D 路线：Filament 透明 `TextureView` 叠在 Compose 上的帧率与功耗。
   - Rive 路线：用官方示例文件测数据绑定驱动状态机。

## 8. 来源

- Codex 宠物：https://github.com/openai/skills/blob/main/skills/.curated/hatch-pet/SKILL.md
- Spine：https://esotericsoftware.com/spine-android 、https://esotericsoftware.com/spine-runtimes-license 、https://esotericsoftware.com/spine-editor-license 、https://esotericsoftware.com/spine-purchase 、https://esotericsoftware.com/spine-physics-constraints
- Live2D：https://www.live2d.com/sdk/license/ 、https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html 、https://www.live2d.com/en/cubism/comparison/ 、https://github.com/Live2D/CubismJavaSamples
- Rive：https://rive.app/docs/runtimes/android/android 、https://rive.app/docs/runtimes/android/data-binding 、https://rive.app/docs/runtimes/runtime-sizes 、https://rive.app/docs/editor/constraints/constraints-overview 、https://rive.app/blog/scripting-is-live-in-rive 、https://rive.app/pricing
- Lottie：https://github.com/airbnb/lottie/blob/master/android-compose.md 、https://github.com/LottieFiles/dotlottie-android
- DragonBones：https://github.com/DragonBones/DragonBonesJava
- 分层工具：https://github.com/QwenLM/Qwen-Image-Layered 、https://github.com/shitagaki-lab/see-through
- 透明视频：https://huggingface.co/htdong/Wan-Alpha
- 悬浮窗与前台服务：https://support.google.com/googleplay/android-developer/answer/16558241 、https://developer.android.com/develop/background-work/services/fgs/service-types 、https://support.google.com/googleplay/android-developer/answer/13392821 、https://developer.android.com/about/versions/15/behavior-changes-15
- Filament：https://github.com/google/filament 、https://google.github.io/filament/main/materials.html 、https://github.com/google/filament/blob/main/android/filament-android/src/main/java/com/google/android/filament/android/UiHelper.java 、https://repo1.maven.org/maven2/com/google/android/filament/
- SceneView：https://github.com/sceneview/sceneview-android 、https://repo1.maven.org/maven2/io/github/sceneview/sceneview/
- VRM：https://vrm.dev/vrm1/ 、https://vrm.dev/en/vrma/ 、https://pub.dev/packages/flutter_vrm
- Godot：https://docs.godotengine.org/en/stable/tutorials/platform/android/android_library.html 、https://github.com/godotengine/godot/pull/106709
- Unity：https://docs.unity3d.com/6000.2/Documentation/Manual/UnityasaLibrary.html 、https://unity.com/pricing-updates
- VRoid：https://vroid.com/en/studio/guidelines
- AI 3D：https://www.meshy.ai/pricing 、https://huggingface.co/tencent/Hunyuan3D-2.1/blob/main/LICENSE 、https://comfyui-wiki.com/en/models/trellis/trellis-2
- 绑定与动作：https://helpx.adobe.com/creative-cloud/faq/mixamo-faq.html 、https://www.rokoko.com/pricing
- 2D 生图与抠图：https://github.com/QwenLM/Qwen-Image-2.1 、https://developers.openai.com/api/docs/guides/image-generation 、https://github.com/Bria-AI/RMBG-2.0 、https://github.com/ZhengPeng7/BiRefNet 、https://www.live2d.com/en/cubism/update/update_5_1_00/
- 视频生成：https://github.com/Wan-Video/Wan2.2 、https://arxiv.org/html/2509.24979v3
