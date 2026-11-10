# 会话宠物技术选型调研

> 日期：2026-11-10
> 范围：Infinity Deck 会话宠物（第一阶段：会话页输入框浮岛上的宠物；后续阶段：全局悬浮窗宠物）
> 状态：调研稿，未改产品代码。3D 路线经实测否决（2026-11-10，见第 4.4 节），方向回到 2D；2D 动作与表情方案见第 4.5 节，待用户看原型确认
> 形象：Infinity Deck 不内置任何宠物形象，形象全部以社区宠物包导入；首个测试形象为 DeepSeek 大肥鱼（蓝色鲸鱼女仆 Q 版），设定图在本机 `artifacts/pet-reference/deepseek-whale-maid-sheet.webp`（不入库）

## 1. 结论

**方向决定（2026-11-10）：** 按设定图复刻鲸鱼女仆 3D 模型的实测（第 4.4 节）气质接近，但一眼能看出不是设定图里的形象，用户决定放弃 3D 路线，回到 2D。本节以下的 3D 推荐保留为调研记录，不再作为实施方向。

**2D 方案（待确认）：** 素材全部由 GPT Image 2.6 以设定图为参考重绘，运行时组合四种手段：每个状态一张姿势图加程序化运动（网格变形、弹簧物理、跳跃挤压），空闲姿势按脸部区域替换表情并自动眨眼，标志性动作用逐帧图，需要更流畅时用 H3 生成的视频帧。实测与取舍见第 4.5 节。

原推荐方案：**3D 卡通渲染（3 渲 2）二次元角色**。运行时用 Filament（Apache-2.0），素材用 VRM 格式：VRoid Studio 起模，Blender 补鲸鱼尾、鲸鳍耳、女仆头饰；自研一层 VRM 支持，包括 SpringBone 物理、Expression 表情、LookAt 视线和卡通着色。状态机与程序化运动层与渲染无关，直接沿用浏览器原型的设计。

理由：

1. **动作差异大。** 宠物的 9 个状态是全身动作：坐着吃饭、站着干活、跳起欢呼、趴着睡觉。2D 骨骼在同一视角内做微动很自然，可大幅换姿势就要为每个姿势另画部件、另做绑定。3D 一套模型就能覆盖全部动作，靠动画混合连续过渡，转身、低头吃饭、手拿筷子送到嘴边都不用额外画图。
2. **表情和物理有标准件。** VRoid 模型自带眨眼、口型（a/i/u/e/o）和喜怒哀惊等标准表情，头发 SpringBone 也会自动生成。这些正好是 Codex 宠物没有、单图原型也做不到的部分。
3. **形象系列化成本低。** 后续要出其它“模型拟人”角色，VRoid 加 Blender 的单角色成本远低于每个角色重新分层、重新绑定 2D 骨骼。
4. **授权干净。** Filament 是 Apache-2.0，与仓库的 GPLv3 兼容；Spine 运行时和 Live2D Core 是专有许可，与 GPLv3 公开仓库有冲突（见 4.1）。
5. **悬浮窗可用。** Filament 不依赖 Activity，可以在 Service 的 `TYPE_APPLICATION_OVERLAY` 窗口里用透明 `TextureView` 渲染（合理推断，待真机验证）。
6. **天然适合社区宠物包。** VRM 是为跨应用携带虚拟形象设计的开放格式，模型里自带作者、许可、是否允许商用和再分发等元数据，应用可以直接展示并遵守。所有动作都写在通用人形骨骼上，应用自带一套动作库，社区作者只需提供模型就能用全部状态动作（3D 原型已验证这一点）。

主要风险与对应的验证：

- **画风还原度。** 3D 卡通渲染能否达到设定图那种插画的可爱度，是最大的未知数。3D 浏览器原型（第 3.2 节）用通用样例模型改出了 Q 版比例和鲸鱼部件，动作与表情已经成立；形象的可爱度取决于正式模型的建模质量，需要用 VRoid 按设定图建出第一版模型后再评估。
- **自研工作量。** Kotlin + Filament 没有现成的 VRM 实现，VRM 解析、表情映射、SpringBone、LookAt、卡通材质估计 3 到 6 人周（推断）。可参考 flutter_vrm（MIT）和 three-vrm 的实现。
- **包体。** filament-android 与 gltfio-android 的 AAR 合计约 11 MB（含全部 ABI，已核验 1.77.3 版），按 ABI 拆分后单个 APK 增量更小（各 ABI 实际大小未核实）。
- **功耗。** 空闲时降到 15 到 30 fps，宠物静止或不可见时停帧，真机测功耗后定档。

备选方案：**Rive**。运行时 MIT，有官方 Compose 组件（Beta），内置状态机与数据绑定，arm64 包体约 2.4 MB。头发、尾巴的物理要用 Luau 脚本自写。适合 3D 原型对比后觉得 2D 插画质感更重要的情况，素材用 AI 分层部件。

序列帧作为第二种宠物包类型保留：GPT-Image 2.6（2026-11-04 发布）可以从提示词或图片直接生成 GIF 动图并逐帧编辑，社区作者做一套精灵图的门槛很低。精灵图包的格式兼容 Codex 宠物（`spritesheet.webp` + `pet.json`），叠加应用自己的程序化运动层（呼吸缩放、弹跳、过渡淡化），观感也能好于 Codex 原版。

不推荐：Spine 与 Live2D（GPL 兼容性与开源协作者授权问题；Live2D 对“可加载任意模型的拓展性应用”另有审查签约要求，与社区导入模式冲突）、Godot 嵌入（AAR 103 MB，单实例）、Unity 嵌入（只能全屏，不能用于悬浮窗）、DragonBones（停维）。

宠物包设计见第 7 节，第一阶段 MVP 与素材清单见第 8 节。

## 2. 对标对象：Codex 宠物

已核验（来源：[openai/skills hatch-pet SKILL.md](https://github.com/openai/skills/blob/main/skills/.curated/hatch-pet/SKILL.md)）：

- 形式是精灵图集：`spritesheet.webp` 为 8 列 × 9 行，每格 192×208，配 `pet.json`。
- 9 个状态（idle、running-right/left、waving、jumping、failed、waiting、running、review），每个状态 4 到 8 帧，逐帧时长。
- 生成方式：生图模型先出基准形象，再逐行生成动作条，在色键背景上抠像后拼图集。

这意味着 Codex 宠物的观感上限由“低帧数逐帧 + 状态硬切”决定：没有骨骼过渡，没有物理次级运动，动作之间直接跳帧。要明显超过它，至少需要三样东西：连续的程序化运动（呼吸、头部、身体）、跟随运动滞后摆动的头发与尾巴、状态之间的平滑过渡。

## 3. 浏览器原型的结论

### 3.1 2D 原型

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

### 3.2 3D 原型

原型：[鲸鱼女仆 3D 原型](https://claude.ai/artifact/UdSutASLmetCQAmF71eV89)（claude.ai 私有页面；本地副本在共享笔记目录 `pet-research/pet-3d.html`）。技术栈为 three.js 0.185 + three-vrm 3.5.5，模型用 VRM 官方样例 `VRM1_Constraint_Twist_Sample`（pixiv 用 VRoid 制作，VRM Public License 1.0，允许再分发）。原型在样例模型上做了这些改造：头部放大 1.9 倍、腿和手臂缩短（Q 版比例），头发与瞳孔按亮度重映射成蓝色渐变，上衣染成藏青；程序补上 6 节鲸鱼尾（水平尾鳍）、鲸鳍耳、荷叶边头饰和蝴蝶结。

已验证（本地浏览器，逐个状态截图）：

- 同一套模型做出 8 个状态的全身动作：坐在浮岛边晃腿、手托下巴思考、左手端碗右手持筷吃饭（咀嚼口型）、站起打字（前方浮现工具面板）、举手挥动等审批、双手举起跳跃欢呼、低头摇头委屈、坐着打瞌睡（周期性点头惊醒）。
- 姿势之间用骨骼四元数平滑过渡，坐下、站起是连续动作，不是交叉淡化。
- 眨眼、开心、惊讶、委屈、放松、口型全部来自模型自带的 VRM 表情；视线按状态看向不同方向。
- 头发由模型自带的 57 个弹簧骨骼驱动；鲸鱼尾和鲸鳍耳用自写弹簧驱动，随跳跃和摇头滞后摆动。
- 按住拖动可以转身看侧面和背面。
- 所有动作只引用人形骨骼名，不引用模型自身的任何节点，换别的 VRM 模型可以直接复用。
- 样例模型约 4.4 万三角面，浏览器内 120 fps。

原型发现的工程要点：

- 不能通过缩放整个模型来控制显示大小，弹簧骨骼的碰撞半径不随缩放变化，头发会被撑开；应保持模型原始尺寸，调整相机距离。
- 补部件要先按世界坐标摆好再挂到骨骼上，不能依赖原始骨骼的局部坐标轴。
- Q 版缩放后脚底高度会变，站立与坐下的落点要按实测的脚底、髋部高度计算。

样例模型是正常比例的长直发少女，原型只能近似 Q 版和女仆造型；卷发、女仆裙、围裙这些要在正式模型里建。

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

来源均为官方页面，详见第 9 节。

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
- **AI 图生 3D：** 输出为高面数三角网格加烘焙贴图，不带骨骼和表情 morph。Pixal3D Multi-View（权重 MIT）用四视图能重建出可用的角色本体，再经脚本减面、绑定、挂弹簧骨骼和贴花表情可转成 VRM（实测见第 4.4 节）。各工具的授权：
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

### 4.4 3D 复刻实测（2026-11-10）

目标是把设定图里的鲸鱼女仆做成能跑全部状态的 VRM，同时保留 2D 插画质感。

- **手工建模：** Blender 脚本徒手建模的还原度远低于图生 3D，已停止。
- **图生 3D：** 在 MAIN（RTX 4090，ComfyUI）上对比 Pixal3D 单图、Pixal3D Multi-View 和 TRELLIS.2，Multi-View 最好。原始设定图双手比心的姿势导致手、胸口和头发粘连，并出现两条尾巴；改用 GPT Image 2.6 单次生成的 A 字姿势四视图重建后，手臂分离、尾巴唯一。
- **转 VRM：** 脚本（分支 `claude/pet-whale-replica-02387f` 的 `scripts/pet-replica/`）把约 21.8 万面的网格转成约 2 万面的 VRM 1.0：91 根骨骼、17 条弹簧链、12 个贴图切换表情、MToon 材质，8 个状态在浏览器原型里跑通。
- **结果：** 气质接近、细节比前几版好，但仍一眼能看出不是设定图里的形象。GPT Image 2.6 重绘的四视图本身与设定图一致性很好，走样发生在 3D 重建和减面烘焙：脸、眼和发色经网格重建、贴图烘焙后失去插画的精度，另画的眼嘴图集贴回三维脸上也与原脸比例不符。把设定图的脸直接裁下贴成面片、发色取设定图色值后明显更像，但只剩一种表情。
- **对 2D 的启示：**
  - GPT Image 2.6 能在保持人物一致的前提下重绘新姿势，可作为 2D 姿势和表情素材的来源。
  - 宠物在屏幕上约 236 像素高，设定图里单个姿势的像素已经够用。
  - 2D 原型与 7 张抠图仍可作为起点。

产出（VRM、对比图、报告）只保存在本机共享笔记目录的 `pet-research/replica/`，形象资产不入库。

### 4.5 2D 动作与表情实测（2026-11-10）

浏览器原型 v2（本机共享笔记目录 `pet-research/2d/proto/pet-2d-v2.html`）在 2D 原型的网格变形、弹簧物理和状态机上接入以下素材。素材都以设定图为参考，经 Codex CLI 单次生成，背景为纯绿，抠图后使用。

| 手段 | 素材 | 实测结果 | 代价与限制 |
|---|---|---|---|
| 姿势图 + 程序化运动 | 一张 3×3 状态姿势表（思考、吃饭、干活、请求确认、提问、完成、出错、睡觉、被摸头） | 九个姿势与设定图是同一人物，比例一致；呼吸、点头、跳跃、头发与尾巴摆动由程序生成 | 每个状态只有一个静态造型，大动作靠变形，幅度有限 |
| 脸部区域换表情 | 同一站姿的 3×3 表情表（平静、闭眼笑、大笑、惊讶、疑惑、委屈、生气、犯困、咀嚼） | 九格轮廓逐像素对齐，脸外只有线稿级抖动；用羽化椭圆只替换脸部，接缝不可见；空闲时用闭眼格做眨眼 | 表情只适用于与表情表同姿势的底图；其它姿势要各出一张表情表 |
| 逐帧动画 | 一张 3×2 的吃饭 6 帧表 | 六帧连贯，可首尾循环，帧间偏移不超过 2 像素；每秒 7 帧有手绘动画感 | 帧数受单张图容量限制，一次约 6 到 9 帧；单帧约 512 像素 |
| 视频帧 | MAIN 本地 MiniMax H3，FL2VA 首尾同帧，640×640、124 帧、24 fps | 待机（眨眼、发尾和尾巴摆动、微笑）和吃饭（送饭、咀嚼、点头）两条人物都保持住，首尾帧衔接；绿幕抠像无绿边 | 画面比静态图略软；每条 5 秒 MP4 约 1.6 到 1.9 MB；角色位置会轻微漂移；需要透明通道方案 |

素材体量：25 张 418 到 512 像素的 WebP 合计约 0.85 MB；两条 H3 视频在 MAIN 上合计约 2 分钟生成完毕。

技术取舍（运行时与许可的一手核验见对应来源）：

- **运行时自研，不引入 Rive 作为基础格式。** Rive Android 运行时为 MIT，支持位图网格、骨骼和状态机，并能从字节加载 `.riv`；但免费计划导出的文件会播放 Rive 启动画面，去除需每月 9 美元，编辑器闭源，社区作者门槛高。可作为后续可选的高级宠物包类型。
- **网格变形用 Compose `Canvas.drawVertices`。** 平台 `drawVertices` 自 API 29 起支持硬件加速，项目 minSdk 31 满足。
- **逐帧用精灵图集或 WebP 动图。** `AnimatedImageDrawable`（API 28+）可播 WebP 动图但不能跳帧；精灵图集可控帧但需常驻内存。按 2 倍密度、每秒 12 帧估算，1 秒约 9 MB 内存（推断）。
- **视频帧需要透明通道方案。** Android 官方格式表未列出带 alpha 的视频解码；可行做法是 RGB 与 Alpha 左右拼接后用着色器合成，或像原型一样按绿幕在着色器里实时抠像（只适合不含绿色的形象）。视频也可以先抽帧再转精灵图集。
- **Inochi2D 暂不考虑。** 格式与编辑器为 BSD-2，但 Rust 运行时 Inox2D 自述处于原型阶段。
- **分层部件拆分（Qwen-Image-Layered，Apache-2.0）** 留作需要独立摆动部件时的选项；当前整图网格变形已能做出头发和尾巴的摆动。

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

已核验（来源见第 9 节）：

- `SYSTEM_ALERT_WINDOW` 在 Play 属于特殊权限，没有专门的声明表单；要求只为没有替代方案的核心功能申请、引导用户到系统设置授权、拒绝后有替代方案。
- Android 14 起前台服务必须声明 `foregroundServiceType`；`specialUse` 要在 manifest 写 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，并在 Play Console 声明、接受审核。
- Android 15（targetSdk 35+）：只持有悬浮窗权限已经不能从后台启动前台服务，必须先有可见的 `TYPE_APPLICATION_OVERLAY` 窗口；`PendingIntent` 默认不带后台启动 Activity 的权限。

现状（读代码）：主 manifest 只有 `FOREGROUND_SERVICE_DATA_SYNC`，生成服务是 dataSync 类型，在等待审批和终态时停止；没有 `SYSTEM_ALERT_WINDOW`。侧载版 manifest 已有 `FOREGROUND_SERVICE_SPECIAL_USE`。

建议：悬浮窗先只做侧载版（权限放进 `src/sideload/AndroidManifest.xml`），Play 版用通知加系统气泡（Bubbles）承载同样的状态与跳转。悬浮窗的渲染方案要能放进 `WindowManager` 窗口：Compose 原生、Rive、Spine（Canvas）都没有问题；GL/3D 方案要用 `TextureView` 或 `SurfaceView` 并验证透明背景与功耗。

## 7. 宠物包：导入、导出与管理

Infinity Deck 本身不附带宠物形象，所有形象以宠物包形式由社区制作、分享和导入。这样具体形象及其商标、版权都留在宠物包作者那里，与本项目分离。

### 7.1 Android 能否在运行时导入动画素材

可以。宠物包是纯数据，不是可执行代码：

- VRM / glTF 模型、VRMA 动作、图片、JSON 都在运行时由渲染器解析。Filament 的 gltfio 可以直接从内存缓冲区加载 glb。
- 导入走系统文件选择器（SAF，`ACTION_OPEN_DOCUMENT`）或分享菜单接收，复制到应用私有目录后加载，不需要存储权限。
- Google Play 的“设备和网络滥用”政策禁止从 Play 以外下载可执行代码（dex、JAR、.so）；在虚拟机或解释器中运行、只能间接访问 Android API 的代码不受这条限制，但运行时加载的解释型代码不得造成违反 Play 政策的行为（[政策原文](https://support.google.com/googleplay/android-developer/answer/16559646)）。模型、贴图、动作数据不是可执行代码；宠物包格式不包含脚本，保持在这条政策之外。

### 7.2 包格式草案

扩展名暂定 `.idpet`，本质是 zip：

- `manifest.json`：
  - 包 ID、名称、版本、作者、许可证。VRM 包的许可直接取模型元数据（是否允许商用、再分发、修改，署名要求）。
  - 渲染类型：`vrm` 或 `sprite`。
  - 入口文件。
  - 状态映射：每个宠物状态对应的动作片段名或精灵图行号，缺省时用应用内置动作。
  - 显示参数：显示高度、落点、朝向。
- `model.vrm`：VRM 1.0 模型；VRM 0.x 也要支持，社区存量模型大量是 0.x。
- `animations/*.vrma`：可选，覆盖内置动作。
- `props/*.glb`：可选，角色专属道具，例如鲸鱼女仆的饭碗和筷子。
- `spritesheet.webp` + `pet.json`：`sprite` 类型使用，兼容 Codex 宠物格式。
- `thumbnail.webp`：管理页缩略图。

应用内置、随应用发布的部分：

- 状态机。
- 一套通用人形动作库（VRMA，项目自有或获得许可）。
- 程序化运动层。
- 通用特效（问号、zZ、审批气泡）。

### 7.3 安全与限制

宠物包来自不可信来源，解析器在原生代码里运行：

- **先校验再加载：**
  - 解压时防目录穿越（zip slip）。
  - 限制总大小（例如 30 MB）、贴图尺寸（例如 2048²）、三角面数、骨骼数、表情数和动作时长。
  - `manifest.json` 按 schema 校验。
  - glb 头部和 JSON 块先在 Kotlin 侧解析检查，再交给原生解析器。
- **加载失败隔离：** 解析失败只禁用该宠物包，不影响会话页。
- **导出与分享：**
  - 导出已安装的宠物包时保留原作者元数据。
  - 模型元数据不允许再分发时，禁用导出与分享按钮。

### 7.4 管理界面

- 宠物列表：缩略图、名称、作者、许可。
- 操作：启用、导入、导出、删除。
- 单个宠物的设置：显示大小、是否在会话页显示、是否用于悬浮窗。

## 8. 第一阶段 MVP、素材清单与制作管线

### 8.1 MVP 范围

- 会话页浮岛上的宠物。
- 宠物包导入、启用、删除（第 7 节）；应用不内置形象，开发期用鲸鱼女仆测试包验收，测试包不进仓库。
- 状态：空闲、准备、思考、生成中（吃白饭）、调用工具、等待审批、等你回答、完成、出错、睡觉，按第 5 节映射。
- 交互：
  - 点击摸头，有反应动作。
  - 长按收起。
  - 设置里可以关闭宠物。
- 跟随系统“移除动画”设置降级为静态姿势。
- 不可见（页面切走、应用进后台、浮岛收起）时停帧。
- 不在 MVP：全局悬浮窗、宠物包导出与分享、精灵图包类型、宠物换装。

### 8.2 素材清单（3D 路线）

- **角色模型：** 一套 VRM，约 1 万到 2 万三角面，贴图合计 2048² 以内。
  - 部件：Q 版人形本体、长卷发（分 6 到 10 束挂 SpringBone）、鲸鳍耳（左右）、女仆头饰与蝴蝶结、呆毛、女仆裙与围裙（裙摆挂 SpringBone）、鲸鱼尾（3 到 4 节骨骼挂 SpringBone）。
- **表情：** 眨眼（左、右、双眼）、口型 a/i/u/e/o、开心、惊讶、委屈、困倦、咀嚼。
- **动作片段：**
  - 循环动作：坐姿呼吸、坐姿吃饭、站立干活、等待审批举手、趴睡。
  - 一次性动作：欢呼跳跃、出错抖动、被摸头。
  - 过渡动作：坐下、起身、躺下。
- **道具：** 白饭碗、筷子、鲸鱼玩偶、枕头、工具图标、审批气泡、zZ 与问号特效。

### 8.3 制作管线

1. **定稿形象：** 以设定图为准，用 GPT Image 2.5 补齐正、侧、背三视图和细节特写（头饰、尾巴、鞋）。GPT Image 2.5 支持 `background: "transparent"`。
2. **起模：** VRoid Studio 做本体、头发、服装，贴图可以用生图模型绘制后再手修。
3. **补部件：** 在 Blender 里建鲸鱼尾、鲸鳍耳、头饰，挂骨骼与 SpringBone，补齐表情 morph，导出 VRM。
4. **做动作：** 手 K 关键动作，或用视频动作捕捉打底再修，导出 VRMA 或 glTF 动画。
5. **出道具：** 用 AI 图生 3D 做道具（TRELLIS.2 本地或 Meshy Pro），重拓扑后贴卡通材质。
6. **真机调参：** 调节动画混合时长、SpringBone 参数、帧率档位和功耗。

### 8.4 AI 工具的授权提醒

| 工具 | 许可证 | 能否用于打包进应用的素材 |
|---|---|---|
| GPT Image 2.5 / 2.6（OpenAI） | OpenAI 服务条款，输出归用户 | 可以 |
| Qwen-Image-Layered | Apache-2.0 | 可以 |
| Qwen Image 2.1 | Qwen Research License，只允许“研究或评估”用途，商用需另行授权（已核验许可证原文） | 只用于构思和评估；正式素材改用其它工具，或先取得授权 |
| TRELLIS.2 | MIT | 可以 |
| Meshy | 免费层输出 CC BY 4.0，Pro 输出私有 | 用 Pro，或在应用里署名 |
| Hunyuan3D 2.1 | 社区许可，排除欧盟、英国、韩国 | 不建议，应用全球分发 |
| RMBG-2.0 | CC BY-NC 4.0 | 不可以；抠图用 BiRefNet（代码 MIT，权重许可未标明，待复核）或生成时直接出透明通道 |

GPT-Image 2.6（2026-11-04 随 ChatGPT Images 2.6 发布，API 有 Flare 与 Sunburst 两个变体）可以从提示词或图片直接生成 GIF 动图并逐帧编辑，适合做精灵图包和动作参考；授权按 OpenAI 服务条款处理。API 是否直接支持 GIF 输出只见于第三方资料，使用前以官方 API 文档为准。

形象涉及的第三方商标与版权由宠物包作者负责，项目仓库与安装包不包含任何具体形象。

### 8.5 下一步验证

第 8.2、8.3 节是 3D 路线的清单，已随 3D 路线一并停用。下一轮研究 2D 路线：

1. 用户看 2D 原型 v2，确定四种手段的取舍，以及视频帧是否进入第一版。
2. 按选定的 2D 方案重定宠物包格式与素材清单（第 7、8 节）：每个状态的动作片段可以是单张姿势图、逐帧序列或视频，另带与底图对齐的表情层。
3. Android 技术验证：选定的运行时叠在 Compose 浮岛上，测帧率、功耗、内存与包体增量。

## 9. 来源

- Codex 宠物：https://github.com/openai/skills/blob/main/skills/.curated/hatch-pet/SKILL.md
- Spine：https://esotericsoftware.com/spine-android 、https://esotericsoftware.com/spine-runtimes-license 、https://esotericsoftware.com/spine-editor-license 、https://esotericsoftware.com/spine-purchase 、https://esotericsoftware.com/spine-physics-constraints
- Live2D：https://www.live2d.com/sdk/license/ 、https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html 、https://www.live2d.com/en/cubism/comparison/ 、https://github.com/Live2D/CubismJavaSamples
- Rive：https://rive.app/docs/runtimes/android/android 、https://rive.app/docs/runtimes/android/data-binding 、https://rive.app/docs/runtimes/runtime-sizes 、https://rive.app/docs/editor/constraints/constraints-overview 、https://rive.app/blog/scripting-is-live-in-rive 、https://rive.app/pricing
- Lottie：https://github.com/airbnb/lottie/blob/master/android-compose.md 、https://github.com/LottieFiles/dotlottie-android
- DragonBones：https://github.com/DragonBones/DragonBonesJava
- 分层工具：https://github.com/QwenLM/Qwen-Image-Layered 、https://github.com/shitagaki-lab/see-through
- 透明视频：https://huggingface.co/htdong/Wan-Alpha
- Play 可执行代码政策：https://support.google.com/googleplay/android-developer/answer/16559646
- GPT-Image 2.6：https://community.openai.com/t/images-upgraded-introducing-chatgpt-images-2-6/1412901 、https://the-decoder.com/chatgpt-can-now-turn-text-prompts-and-photos-into-animated-gifs-with-openais-images-2-6-model/
- VRM 样例模型：https://github.com/vrm-c/vrm-specification/tree/master/samples/VRM1_Constraint_Twist_Sample 、https://vrm.dev/licenses/1.0/
- three-vrm：https://github.com/pixiv/three-vrm
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
- 2D 运行时补充（第 4.5 节）：https://github.com/rive-app/rive-android/releases 、https://rive.app/docs/editor/manipulating-shapes/meshes 、https://rive.app/docs/editor/exporting/exporting-for-runtime 、https://github.com/Inochi2D/inox2d 、https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Canvas 、https://developer.android.com/develop/ui/views/graphics/hardware-accel 、https://developer.android.com/reference/android/graphics/drawable/AnimatedImageDrawable 、https://developer.android.com/media/platform/supported-formats 、https://github.com/hzwer/Practical-RIFE
