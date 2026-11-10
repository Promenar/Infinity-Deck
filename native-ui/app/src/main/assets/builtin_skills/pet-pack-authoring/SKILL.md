---
name: pet-pack-authoring
description: 当用户询问 Infinity Deck 宠物的使用、操作，或需要设计、制作、校验、打包 .idpet 宠物包时使用。
---

# 会话宠物包制作

`.idpet` 是 ZIP 包，根目录包含必需的 `manifest.json`、`thumbnail.webp`、`clips/` 素材目录，可包含 `persona.json` 与 `LICENSE.txt`。素材是声明式文件，角色只响应基座发出的信号。

## 清单

`id` 使用 1 至 64 位小写字母、数字、点、连字符或下划线，首字符不能是点。`license.redistribute` 控制包能否在应用内导出分享；`commercial` 声明商业使用许可。显示高度 `height_dp` 为 80 至 160，`facing` 为 `left` 或 `right`。

```json
{
  "contract": "0.3", "id": "example.pet", "name": "小鱼", "version": "1.0.0", "author": "作者",
  "license": {"name": "CC BY 4.0", "redistribute": true, "commercial": true},
  "display": {"height_dp": 125, "facing": "right"},
  "clips": {
    "stand": {"type": "pose", "file": "clips/stand.webp", "anchor": [0.5, 0.95]},
    "wave": {"type": "frames", "file": "clips/wave.webp", "anchor": [0.5, 0.95], "cols": 4, "rows": 2, "count": 8, "fps": 8, "loop": true},
    "dance": {"type": "video", "file": "clips/dance.mp4", "anchor": [0.5, 0.95], "alpha": "side_by_side", "loop": true, "fallback": "stand"}
  },
  "expressions": {"base_clip": "stand", "face": {"center": [0.5, 0.4], "radius": [0.2, 0.13]}, "set": {"neutral": "clips/face-neutral.webp", "blink": "clips/face-blink.webp"}},
  "zones": {"head": {"center": [0.5, 0.3], "radius": [0.26, 0.2]}},
  "props": {"perch": {"cloud": {"file": "clips/cloud.webp", "seat": [0.5, 0.42], "width_dp": 120, "appear": "stand", "vanish": "wave"}}},
  "map": {"idle": {"clip": "stand"}, "task.generating@long": {"clip": "wave", "say": true}, "touch.poke": {"clip": "stand", "haptic": "tick"}}
}
```

`pose` 是单张透明图；`frames` 是网格图集；`video` 使用 MP4，透明方式为 `side_by_side`（左 RGB、右 Alpha）或 `chroma_green`，`fallback` 指向同包 `pose` 或 `frames`。共同字段 `anchor` 是平台接触点的归一化坐标，`scale` 默认 1；可选 `motion` 为 `breathe`、`nod`、`hop`、`shake`、`bounce`、`none`。可选 `rig` 包含 `neck`、`hair_top`、`tail`（`root`、`tip`、`radius`）、`sitting`、`lying`。表情图与基底图逐像素对齐，预留名称为 `neutral`、`blink`、`happy`、`laugh`、`surprised`、`confused`、`sad`、`angry`、`sleepy`。

映射支持持续 `clip`、进入动作 `enter` 和 `enter_ms`、周期插入 `sometimes`（`clip`、`every_s`、`for_ms`）、空闲 `behaviors`（`clip`、`weight`、`move`、`min_s`、`max_s`）、`expression`、`say` 和 `sequence`（`clip`、`launch`、`into_island`）。至少提供不带限定的 `idle`；状态信号回退到所属大类再到 `idle`。每个映射键最多带一个限定后缀：位置 `@perch`、`@message`、`@stage`，时长 `@long`（60 秒起）、`@verylong`（300 秒起），心情 `@mood.high`、`@mood.low`，作息 `@morning`（6–11 点）、`@day`（11–18 点）、`@evening`（18–23 点）、`@night`（23–6 点，手机本地时间）。每级按位置、时长、心情、作息、无后缀查找，`@verylong` 可回退到 `@long`。工具状态按工具名、类别、`task.tool`、`task.working`、`idle` 回退；类别为 `read`、`write`、`search`、`web`、`shell`、`image`、`schedule`、`subagent`、`other`。

`zones` 支持 `head`、`belly`、`tail`，每个 `center` 和 `radius` 是两个 0 到 1 的数。`props.perch` 支持 `cloud`、`bubble`；可选 `file` 为包内 PNG/WebP 图片，`seat` 是两个 0 到 1 的数，`width_dp` 为 60 至 200，`appear` 和 `vanish` 引用已有片段。映射可选 `haptic`：`tick`、`thud`、`buzz`。

## 信号与数据

状态：`idle`、`idle.long`、`sleep`；`task.waiting`、`task.working`、`task.needs_user`、`task.queued`、`task.thinking`、`task.generating`、`task.tool`（可加工具名后缀）、`task.approval`、`task.ask`、`task.finishing`、`task.done`、`task.error`、`task.cancelled`；`env.reduced_motion`、`env.power_save`、`env.theme.dark`、`env.theme.light`。

事件：`island.fx.wait`、`island.fx.stream`、`island.fx.tool`、`island.fx.approval`、`island.fx.retry`、`island.wider`、`island.narrower`、`island.focus`、`island.blur`、`island.send`、`device.unfold`、`device.fold`、`push.left`、`push.right`、`touch.tap`、`touch.held`、`touch.fall`、`touch.land.soft`、`touch.land.hard`、`fall.over`、`get.up`、`role.enter`、`role.exit`、`session.other.active`。

台词模板可使用 `{session.title}`、`{model.name}`、`{tool.name}`、`{tool.target}`、`{output.chars}`、`{elapsed.s}`、`{error.kind}`、`{ask.text}`、`{idle.s}`、`{daypart}`，以及记忆数据 `{memory.session.last}`（上次在这个会话的时间，如“昨天”）、`{memory.session.visits}`（来过的天数）、`{memory.last_result}`（上一轮结果）、`{memory.streak.days}`（连续使用天数）；没有值的模板会被跳过。

### 玩法信号（契约 0.3）

下表的信号都要在 `map` 里配了才有专属动作；没配时按“未配置时”一列表现。给每个信号准备一张姿势图即可，不需要逐帧。

| 信号 | 触发 | `clip` 怎么用 | 未配置时 | 建议姿势 |
|---|---|---|---|---|
| `touch.pet` | 在头部分区横向来回滑动 | 播放 `enter_ms`（默认 1.6 秒） | 用 `touch.tap` | 闭眼享受 |
| `touch.poke.head` / `.belly` / `.tail` | 点按对应分区（需声明 `zones`） | 播放 `enter_ms` | 用 `touch.poke`，再用 `touch.tap` | 捂头、怕痒、护尾巴 |
| `touch.annoyed` | 3 秒内点按 5 次 | 播放 `enter_ms` | 用 `touch.tap` | 抱臂生闷气 |
| `touch.throw` | 较快松手被抛出 | 飞行全程显示，基座让它旋转 | 用 `touch.fall` | 缩成一团翻滚 |
| `touch.bounce` | 飞行中撞到屏幕边缘 | 撞边后显示约 0.2 秒 | 保持飞行片段 | 被压扁 |
| `tilt.slide.left` / `.right` | 手机左右倾斜，沿浮岛打滑 | 打滑期间显示；只配一侧时另一侧自动镜像 | 保持站姿平移 | 侧身后仰滑行 |
| `shake.dizzy` | 轻摇手机 | 播放 `enter_ms`，基座同时让它左右摇摆 | 只有摇摆 | 螺旋眼、站不稳 |
| `keyboard.up` / `.down` | 键盘弹出、收起 | 播放 `enter_ms`（默认 1.3 秒） | 只有弹跳 | 吓一跳、张望 |
| `role.dismiss` | 用力甩出屏幕被收起 | 飞出途中显示，基座让它旋转并缩小 | 用 `touch.throw`，再用 `touch.fall` | 回头挥手飞走 |
| `role.summon` | 被收起后摇晃手机唤回 | 落地后播放 `enter_ms`（默认 2 秒）；下落途中显示 `touch.throw` 的片段 | 落地后用 `fall.over` 再 `get.up` | 摔坐、晕乎乎 |
| `place.perch.enter` | 落到云朵或泡泡上 | 播放 `enter_ms`（默认 1.2 秒） | 直接进入 `idle@perch` | 伸懒腰、张望 |
| `place.perch.pop` / `.blown` | 泡泡被戳破、云朵被吹散 | 落回浮岛途中显示 | 用 `touch.fall` | 吓一跳、被吹翻 |
| `place.perch.leave` | 离开栖息点 | 不播放片段，可配 `haptic` | — | — |
| `place.stage.enter` / `.leave` | 进入没有消息的会话、出现第一条消息 | 播放 `enter_ms`（默认 1.5 秒） | 没有反应 | 挥手、张望 |
| `sub.spawn` | 一个子代理开始运行 | 播放 `enter_ms`，基座让角色面向分身跑走的一侧；同时身旁分出一个小分身沿浮岛跑出屏幕 | 只有分身跑出 | 侧身比大拇指、挥手送行 |
| `sub.return` | 一个子代理结束 | 分身跑回身旁并回身上时播放 `enter_ms` | 只有分身跑回 | 把小东西搂进怀里 |
| `sub.clone` | 分身跑动时的画面，显示高度为角色的 45% | 跑动全程循环显示，按 `display.facing` 绘制 | 用 `idle` 里带 `move` 的行走片段，再用 `idle` 的片段 | 行走或奔跑循环 |

在栖息点上的表现用位置限定配置，例如 `idle@perch` 写一组不带 `move` 的 `behaviors`（趴着、坐在边缘、打哈欠）；没配时用 `idle` 里不走动的行为。心情用 `@mood.high`、`@mood.low` 配置，例如 `touch.tap@mood.low`。长任务用 `@long`、`@verylong`，例如 `task.tool@long`。

`haptic` 只对点按、抚摸、抛掷、落地、摇晃、栖息点这些交互事件生效。兜底台词 `fallback` 按信号名精确查找（工具状态除外），`touch.poke.head`、`tilt.slide.left` 这类信号要说话就各写一条。数据通道 `mood` 为 `high`、`normal` 或 `low`。

作息用 `@morning`、`@day`、`@evening`、`@night` 配置，例如 `idle@night` 写一组抱枕头、打哈欠的 `behaviors`；台词里的 `{daypart}` 取值为“早晨”“白天”“晚上”“深夜”。

会话还没有消息时映射优先使用 `@stage` 限定，例如 `idle@stage`。`@message` 与 `place.message.*` 为后续版本预留，可以写进清单，当前基座不触发。

### 击掌交接（契约 0.3）

切换角色时新角色在一团烟雾里出现，两个角色击掌，旧角色在一团烟雾里消失。每个包只声明自己的一个击掌片段，基座负责摆位、翻转和对时，所以任意两个包都能配对。没有声明的包按先离场、后入场切换。

```json
"handoff": {"clip": "highfive", "contact": [0.85, 0.46], "contact_ms": 500}
```

- `clip`：击掌片段，不循环，总长建议不超过 1.6 秒。按 `display.facing` 的方向画：角色面向前方站直，伸出一只手掌。起始帧和结束帧都是站姿，脚的位置与 `idle` 一致。
- `contact`：击掌那一帧手掌中心在单帧画面里的位置，与 `anchor` 同一套坐标（左上角 `[0, 0]`，右下角 `[1, 1]`）。精灵表按单格量。
- `contact_ms`：片段开始后手掌到达击掌点的时刻，200 至 1200。击掌那一帧至少停留 80 毫秒。
- 手掌是这一帧里最靠前的部位，身体不越过手掌所在的竖线；高度建议在身高的 55%–75%。
- 画面里只有角色本人，不画对方、不画星花和烟雾，这些由基座绘制。
- 不用画反方向的版本：需要面向另一侧时基座水平翻转。对方的片段长短和个子高矮也不用考虑，基座会对齐击掌时刻，并让击掌点较低的一方跳起来够。

三张姿势图就够：预备（手臂后引）、击掌（手掌前伸）、收势。按停留时间排进精灵表，例如 10 帧每秒时预备 5 格、击掌 3 格、收势 6 格，`contact_ms` 为 500。

## 玩法与操作

在“设置 → 宠物”打开宠物总开关，选择默认宠物并管理宠物包；台词、视频、甩出收起与摇晃唤回、倾斜与摇晃反应、触感、栖息点样式及吹气开关也在此页。

按住宠物可以提起；轻甩会落回平台，中等力度会撞边反弹，用力甩出屏幕会收起。宠物不见时，可摇晃手机唤回，在“设置 → 宠物”点“唤回”，或请 Agent 调用 `pet` 工具的 `summon`；总开关须开启。把宠物拖到屏幕上部松手可落在云朵或七彩泡泡上；点破泡泡可让它落下，开启吹气功能后可吹散云朵。在头部左右来回滑动是抚摸，点头、肚子、尾巴各有反应，3 秒内连点 5 次会生气；倾斜手机时宠物会打滑，轻摇手机它会发晕。这些动作需要宠物包配置了对应信号才有专属姿势，否则只有通用反应。

## 角色配置

```json
{
  "name": "小鱼", "self": "我", "user": "主人", "personality": "活泼认真", "style": "简短口语",
  "catchphrases": ["嘿嘿"], "avoid": ["评价用户的代码质量"], "examples": ["快好了～"],
  "languages": ["zh-CN"], "fallback": {"task.generating": ["已写了 {output.chars} 字"]}
}
```

`personality` 与 `style` 各最多 200 字；`catchphrases`、`avoid`、`examples` 各最多 10 条、每条 40 字；每个信号的 `fallback` 最多 8 条、每条 40 字。未提供角色配置时角色不说话。

## 素材制作和验收

1. 明确角色形象、授权及素材清单，先制作角色设定图，固定服饰、比例、色彩和正侧面特征。
2. 按素材表一次生成一张姿势图、表情图或逐帧图，使用纯色背景并保持镜头、比例与画布一致。
3. 抠出透明背景，切分与排列帧格，检查帧间位置；测量角色落在平台上的接触点并换算成 `anchor`。表情与基底逐像素对齐。
4. 如需循环视频，制作首尾同帧的循环，编码为 H.264 或 HEVC，准备静态或逐帧后备片段。
5. 编写清单、可选角色配置与许可，制作缩略图，将上述文件以根目录结构打包为 ZIP 并命名为 `.idpet`。在应用中导入检查动作、表情、落点与许可。

包解压后最多 30 MiB、200 个文件；扩展名只允许 `.json`、`.webp`、`.png`、`.mp4`、`.txt`。图片最长边不超过 4096 像素；视频最长边不超过 1024 像素、时长不超过 15 秒。路径使用包内相对路径。
