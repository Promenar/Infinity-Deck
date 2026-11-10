# 宠物基座契约规格（草案 v0.2）

> 日期：2026-11-10
> 状态：草案 v0.2，用户已确认，按此实现
> 依据：[会话宠物技术选型调研](20261110-pet-tech-stack-research.md) 第 4.5、9 节与浏览器原型 v4
> 约定：字段名、枚举值为草案命名；标注“待核验”的内核数据来源要在实现前读代码确认

## 1. 范围与角色

契约定义三方之间的接口：

- **内核**：Infinity Deck 应用。向基座发布状态与事件。
- **基座**：应用内的宠物运行时。接收内核信号，按宠物包的声明播放片段、做物理与气泡。
- **宠物包**：`.idpet` 文件。只含素材和声明，不含可执行代码。

契约只描述“发生了什么”和“包可以声明什么”，不规定具体表现。包可以只使用一部分信号。

**单向原则：只有宠物响应界面，没有界面响应宠物。** 信号只从内核流向基座。宠物包不能改变浮岛或其它界面元素的外观与行为；与浮岛互动的视觉效果（例如戳浮岛时的涟漪、落地的水花）由包自己的片段在宠物图层内绘制。

契约版本号为 `contract`，采用 `主.次`。次版本只新增信号和可选字段；包声明的主版本高于基座时拒绝加载，次版本高于基座时忽略不认识的字段。

## 2. 宠物包结构

`.idpet` 是 zip，根目录：

| 路径 | 必需 | 内容 |
|---|---|---|
| `manifest.json` | 是 | 包信息、片段表、信号映射（第 3 节） |
| `persona.json` | 否 | 角色配置单（第 7 节）；缺省时宠物不说话 |
| `clips/` | 是 | 片段素材：WebP、PNG、MP4 |
| `thumbnail.webp` | 是 | 管理页缩略图 |
| `LICENSE.txt` | 否 | 许可全文 |

## 3. manifest.json

```json
{
  "contract": "0.1",
  "id": "community.whale-maid",
  "name": "大肥鱼",
  "version": "1.0.0",
  "author": "作者名",
  "license": {"name": "CC BY-NC 4.0", "redistribute": true, "commercial": false},
  "display": {"height_dp": 125, "facing": "right"},
  "clips": { },
  "expressions": { },
  "map": { }
}
```

- `display.height_dp`：片段图整幅（逐帧图集为单格，视频为可见画面）的显示高度，基座限制在 80 到 160 之间；片段的 `scale` 在此基础上修正。
- `display.facing`：侧向片段默认朝向，基座按行走方向水平翻转。
- `license.redistribute` 为 `false` 时，应用禁用该包的导出与分享。

### 3.1 片段（clips）

每个片段有一个名字和一种类型：

| 类型 | 字段 | 说明 |
|---|---|---|
| `pose` | `file` | 单张 RGBA 图，基座叠加呼吸、摆动等程序化运动 |
| `frames` | `file`、`cols`、`rows`、`count`、`fps`、`loop` | 网格排列的逐帧图集 |
| `video` | `file`、`alpha`、`loop`、`fallback` | MP4；`alpha` 为 `side_by_side`（左 RGB 右 Alpha）或 `chroma_green`；`fallback` 指向同包内一个 `pose` 或 `frames` 片段 |

所有片段的公共字段：

- `anchor`：`[u, v]`，落在平台上的接触点，归一化坐标。
- `scale`：相对 `display.height_dp` 的缩放修正，默认 1。
- `rig`：可选，程序化运动参数：`neck`（头部转动支点）、`hair_top`（头发摆动起点）、`tail`（尾根、尾尖、半径）、`sitting`、`lying`。缺省时只做整体呼吸和挤压。
- `motion`：可选，预设名：`breathe`、`nod`、`hop`、`shake`、`bounce`、`none`。

视频片段在用户关闭视频、省电模式或解码失败时改播 `fallback`。

### 3.2 表情（expressions）

```json
"expressions": {
  "base_clip": "stand",
  "face": {"center": [0.505, 0.415], "radius": [0.2, 0.13]},
  "set": {"neutral": "clips/face-neutral.webp", "blink": "clips/face-blink.webp", "happy": "..."}
}
```

表情图与 `base_clip` 逐像素对齐，基座只在 `face` 椭圆内替换并羽化边缘。保留名：`neutral`、`blink`、`happy`、`laugh`、`surprised`、`confused`、`sad`、`angry`、`sleepy`。有 `blink` 时基座自动眨眼。

### 3.3 信号映射（map）

键是信号名（第 4、5 节），值是表现：

```json
"map": {
  "task.generating": {"clip": "eat", "say": true},
  "task.approval":   {"enter": "startle", "enter_ms": 700, "clip": "raise-hand"},
  "task.thinking":   {"clip": "think", "sometimes": [{"clip": "poke", "every_s": 5, "for_ms": 1400}]},
  "idle":            {"behaviors": [{"clip": "walk", "weight": 3, "move": true}, {"clip": "edge-sit", "weight": 2, "min_s": 7, "max_s": 11}]},
  "touch.held":      {"clip": "held"},
  "role.exit":       {"sequence": [{"clip": "startle", "launch": 430}, {"clip": "dive", "into_island": true}]}
}
```

- `clip`：持续播放的片段。
- `enter`、`enter_ms`：进入该信号时先播的反应片段。
- `sometimes`：持续期间偶发的插入动作。
- `behaviors`：空闲时随机调度的行为；`move` 表示沿平台移动。
- `expression`：切换到指定表情（仅当当前片段是 `expressions.base_clip`）。
- `say`：该信号是否请求一句台词（第 7 节）。
- `sequence`：入场、离场的步骤。每步有 `clip`、可选的 `launch`（起跳速度，dp 每秒）和 `into_island`（钻进或钻出浮岛，平台以下的部分被裁掉）。离场时上升阶段播第一步的片段、下落阶段播最后一步的片段；入场播第一步的片段。没有 `sequence` 时淡入淡出。

事件信号（第 5 节）的映射只用 `clip`、`enter_ms` 和 `say`：片段播放 `enter_ms` 毫秒（缺省 1200 到 1600，视事件而定）后回到当前状态。事件信号不回退到 `idle`；包没有声明时用基座默认表现。`push.left` 与 `push.right` 只声明一个时，另一个方向水平翻转使用。

回退：包没有声明某个信号时，依次尝试 `信号 → 所属大类 → idle`。例如 `task.tool.read_file` → `task.tool` → `task.working` → `idle`。包至少声明 `idle`。

## 4. 状态信号

状态在一段时间内持续，同一时刻每个分组只有一个值。

### 4.1 任务状态（`task.*`）

| 信号 | 含义 | 大类 | 内核来源 |
|---|---|---|---|
| `task.queued` | 排队或准备中 | `task.waiting` | `GenerationPhase.QUEUED / PREPARING / BUILDING_CONTEXT / CONNECTING` |
| `task.thinking` | 思考 | `task.working` | `THINKING`，或只有 reasoning 在增长 |
| `task.generating` | 正文生成中 | `task.working` | `STREAMING` 且 content 在增长 |
| `task.tool` | 调用工具；可带工具名后缀，如 `task.tool.read_file` | `task.working` | `EXECUTING_TOOLS` |
| `task.approval` | 等待审批 | `task.needs_user` | `WAITING_APPROVAL` 与子代理审批 |
| `task.ask` | 等用户回答 | `task.needs_user` | `WAITING_INPUT` |
| `task.finishing` | 后处理 | `task.working` | `POST_PROCESSING` |
| `task.done` | 完成（瞬时后回到 `idle`） | — | `COMPLETED` |
| `task.error` | 失败 | — | `FAILED`、`PERSISTENCE_FAILED` |
| `task.cancelled` | 取消 | — | `CANCELLED` |

来源已核验：`domain/generation/GenerationModels.kt`、`ui/common/motion/GenerationMotion.kt`。宠物订阅 `generationPhase`，不订阅 `ChatViewModel` 的粗粒度状态。

### 4.2 空闲（`idle`、`idle.long`、`sleep`）

没有任务时为 `idle`；空闲超过 `idle_long_s`（默认 120 秒）为 `idle.long`；超过 `sleep_s`（默认 600 秒）为 `sleep`。阈值由基座设置，包不能改。

### 4.3 环境状态（`env.*`）

| 信号 | 含义 | 来源 |
|---|---|---|
| `env.reduced_motion` | 系统开启“移除动画” | 待核验 |
| `env.power_save` | 省电模式 | 待核验 |
| `env.theme.dark` / `env.theme.light` | 深浅色 | 待核验 |

`env.reduced_motion` 下基座只显示静态姿势，不播放逐帧、视频和物理；这是基座行为，包不能覆盖。

## 5. 事件信号

事件是瞬时的，触发一次反应后回到当前状态。

| 信号 | 触发 | 来源 |
|---|---|---|
| `island.fx.wait` / `stream` / `tool` / `approval` / `retry` | 浮岛光效阶段变化 | `GenerationMotionPhase`（已核验） |
| `island.wider` / `island.narrower` | 浮岛宽度稳定后变化超过 40 dp | 基座测量 |
| `island.focus` / `island.blur` | 输入框获得或失去焦点 | 待核验 |
| `island.send` | 用户发送消息 | 待核验 |
| `device.unfold` / `device.fold` | 折叠屏展开、合上 | 待核验 |
| `push.left` / `push.right` | 浮岛边缘或侧边栏推到角色 | 基座碰撞 |
| `touch.tap` | 点按 | 基座 |
| `touch.held` | 被提起（持续到松手） | 基座 |
| `touch.fall` | 松手后下落 | 基座 |
| `touch.land.soft` / `touch.land.hard` | 落地；落地速度超过阈值为 `hard` | 基座 |
| `fall.over` / `get.up` | 被推后摔倒、起身 | 基座 |
| `role.enter` / `role.exit` | 宠物被打开或切入、被关闭或切走 | 基座 |
| `session.other.active` | 其它会话或子代理有任务在进行 | `generationCoordinator.sessions`、`SubagentLauncher.activeRuns`（已核验） |

没有声明物理事件的包使用基座默认表现：提起和下落时沿用当前片段并随拖动摆动，落地时挤压回弹，被推时平移，进出场淡入淡出。

## 6. 数据通道

数据随信号提供，供台词模板和台词模型使用，不直接驱动表现。

| 键 | 含义 | 来源 |
|---|---|---|
| `session.title` | 会话标题 | 待核验 |
| `model.name` | 当前会话模型的显示名 | 待核验 |
| `tool.name` | 当前工具名 | `GenerationSnapshot.toolCalls`（已核验存在，字段待核验） |
| `tool.target` | 工具目标的文件名（只取文件名，不含内容和完整路径） | 待核验 |
| `output.chars` | 本轮已生成字数 | `GenerationSnapshot.content` 长度 |
| `elapsed.s` | 本轮耗时 | `GenerationSnapshot.timing`（已核验） |
| `error.kind` | 错误类别 | `GenerationSnapshot.failure`（已核验存在，字段待核验） |
| `ask.text` | 待回答的问题，截断到 60 字 | 待核验 |
| `idle.s` | 空闲时长 | 基座 |
| `daypart` | 时段：早、午、晚、深夜 | 基座 |

数据通道不提供消息正文、文件内容和工具输出。

## 7. 角色配置单与台词

### 7.1 persona.json

```json
{
  "name": "大肥鱼",
  "self": "我",
  "user": "主人",
  "personality": "活泼、贪吃、做事认真",
  "style": "短句，口语，偶尔用“～”收尾",
  "catchphrases": ["嘿嘿"],
  "avoid": ["评价用户的代码质量"],
  "examples": ["边吃边写，快好了～"],
  "languages": ["zh-CN"],
  "fallback": {
    "task.generating": ["嚼嚼…在写了", "已经写了 {output.chars} 字"],
    "task.error": ["出错了（{error.kind}），等会儿再试"],
    "touch.held": ["哇啊！放我下来～"]
  }
}
```

长度上限：`personality`、`style` 各 200 字；`catchphrases`、`avoid`、`examples` 各 10 条、每条 40 字；`fallback` 每个信号 8 条、每条 40 字。超长时校验失败。`fallback` 里的 `{键}` 用第 6 节的数据替换。

### 7.2 台词生成

- 模型：用户在设置里单独指定，不跟随当前会话的模型。未指定时只用 `fallback`。
- 请求：独立的一次短请求，不带工具，不读取主会话上下文。输入为角色配置单、触发信号和第 6 节的数据；输出为一句不超过 30 字的纯文本，不写回主会话。
- 触发：映射里 `say` 为 `true` 的信号进入时；状态信号持续期间每 25 到 45 秒再触发一次。
- 限流：两句之间至少间隔 20 秒；每小时最多 30 次请求；超限或失败时用 `fallback`，没有 `fallback` 就不说。具体数值在真机体验后调整。
- 费用：计入用量专区。
- 显示：气泡按纯文本显示，不渲染链接和 Markdown；显示约 3 秒。
- 用户可以单独关闭台词，或只用 `fallback`。

## 8. 模型关联与角色切换

- 关联可选，在设置里手动选择，不自动匹配。
- 一个模型只关联一个宠物；一个宠物可以关联多个模型。
- 设置了关联时，宠物随当前会话的模型切换：旧角色收到 `role.exit` 并播完离场表现，新角色收到 `role.enter`。离场表现上限 2 秒，超时由基座强制结束。
- 没有关联的模型显示用户选定的默认宠物。
- 宠物总开关关闭时，当前角色播放离场表现后不再显示。

### 8.1 设置入口

- 宠物功能默认关闭，用户在设置里手动启用。
- 设置首页新增独立入口“宠物”，位置在“通用”“外观”之后，不放进“外观”页内。
- “宠物”页包含：总开关、宠物包管理（导入、启用、删除、导出）、默认宠物、模型关联、台词开关与台词模型、视频片段开关。

## 9. 基座职责

- **图层**：宠物、气泡和包自带的效果都画在浮岛上方的独立图层里，不修改浮岛本身。
- **平台**：输入框浮岛上沿是活动平台；角色的行走范围在浮岛左右边缘内各留 26 dp。
- **物理**：提起时跟随指针并按速度摆动；松手后受重力下落并被引回平台范围；浮岛边缘移动碰到角色时推着走。
- **调度**：任务状态优先于空闲行为；交互与物理事件优先于任务状态；事件结束后回到当前状态。
- **降级**：不可见（页面切走、应用进后台、浮岛收起）时停帧；`env.reduced_motion` 下静态显示；省电模式下不播视频、帧率减半。
- **隔离**：单个包解析或播放失败只禁用该包，不影响会话页。

## 10. 校验规则

导入时逐项校验，任何一项失败则拒绝导入并说明原因：

- 解压防目录穿越；包总大小上限 30 MB；文件数上限 200。
- 只允许 `.json`、`.webp`、`.png`、`.mp4`、`.txt`。
- 单张图最长边上限 4096 像素；视频最长边上限 1024 像素、时长上限 15 秒、编码为 H.264 或 HEVC。
- `manifest.json`、`persona.json` 按 schema 校验；`map` 引用的片段都存在；`video` 片段的 `fallback` 存在且不是视频。
- 至少有 `idle` 的映射。

数值上限为草案值，真机测内存和解码开销后确定。

## 11. 内置 Skill

应用内置一个宠物包制作 Skill，内容为本规格的信号清单、包格式、角色配置单写法和校验规则，以及素材制作流程：

1. 准备角色设定图。
2. 用生图模型按素材表生成姿势、表情、逐帧图，背景为纯色；每张图一次生成，由制作者审图。
3. 抠图、按网格切格、测量各片段的 `anchor`。
4. 可选：用视频模型以同一张图作首尾帧生成循环视频。
5. 写 `manifest.json` 和 `persona.json`，运行校验，打包成 `.idpet`。

## 12. 待定与待核验

- 第 4.3、5、6 节标注“待核验”的内核数据来源。
- Android 上视频透明通道的实现方式与性能（`side_by_side` 与 `chroma_green`）。
- 逐帧图集与视频的内存、功耗实测，以及由此确定的尺寸和时长上限。
- 台词限流数值与提示词模板。
- 全局悬浮窗阶段是否沿用同一契约。
