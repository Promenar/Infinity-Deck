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

映射支持持续 `clip`、进入动作 `enter` 和 `enter_ms`、周期插入 `sometimes`（`clip`、`every_s`、`for_ms`）、空闲 `behaviors`（`clip`、`weight`、`move`、`min_s`、`max_s`）、`expression`、`say` 和 `sequence`（`clip`、`launch`、`into_island`）。至少提供不带限定的 `idle`；状态信号回退到所属大类再到 `idle`。每个映射键最多带一个限定后缀：位置 `@perch`、`@message`、`@stage`，时长 `@long`（60 秒起）、`@verylong`（300 秒起），心情 `@mood.high`、`@mood.low`。每级按位置、时长、心情、无后缀查找，`@verylong` 可回退到 `@long`。工具状态按工具名、类别、`task.tool`、`task.working`、`idle` 回退；类别为 `read`、`write`、`search`、`web`、`shell`、`image`、`schedule`、`subagent`、`other`。

`zones` 支持 `head`、`belly`、`tail`，每个 `center` 和 `radius` 是两个 0 到 1 的数。`props.perch` 支持 `cloud`、`bubble`；可选 `file` 为包内 PNG/WebP 图片，`seat` 是两个 0 到 1 的数，`width_dp` 为 60 至 200，`appear` 和 `vanish` 引用已有片段。映射可选 `haptic`：`tick`、`thud`、`buzz`。

## 信号与数据

状态：`idle`、`idle.long`、`sleep`；`task.waiting`、`task.working`、`task.needs_user`、`task.queued`、`task.thinking`、`task.generating`、`task.tool`（可加工具名后缀）、`task.approval`、`task.ask`、`task.finishing`、`task.done`、`task.error`、`task.cancelled`；`env.reduced_motion`、`env.power_save`、`env.theme.dark`、`env.theme.light`。

事件：`island.fx.wait`、`island.fx.stream`、`island.fx.tool`、`island.fx.approval`、`island.fx.retry`、`island.wider`、`island.narrower`、`island.focus`、`island.blur`、`island.send`、`device.unfold`、`device.fold`、`push.left`、`push.right`、`touch.tap`、`touch.held`、`touch.fall`、`touch.land.soft`、`touch.land.hard`、`fall.over`、`get.up`、`role.enter`、`role.exit`、`session.other.active`。

台词模板可使用 `{session.title}`、`{model.name}`、`{tool.name}`、`{tool.target}`、`{output.chars}`、`{elapsed.s}`、`{error.kind}`、`{ask.text}`、`{idle.s}`、`{daypart}`。

新增事件：`touch.throw`、`touch.bounce`、`touch.pet`、`touch.poke`、`touch.poke.head`、`touch.poke.belly`、`touch.poke.tail`、`touch.annoyed`、`tilt.slide.left`、`tilt.slide.right`、`shake.dizzy`、`role.dismiss`、`role.summon`、`keyboard.up`、`keyboard.down`、`place.perch.enter`、`place.perch.leave`、`place.perch.pop`、`place.perch.blown`、`place.message.enter`、`place.message.leave`、`place.stage.enter`、`place.stage.leave`。分区戳按回退到 `touch.poke` 再到 `touch.tap`；抚摸和生气回退到 `touch.tap`。数据通道 `mood` 为 `high`、`normal` 或 `low`。

## 玩法与操作

在“设置 → 宠物”打开宠物总开关，选择默认宠物并管理宠物包；台词、视频、甩出收起与摇晃唤回、倾斜与摇晃反应、触感、栖息点样式及吹气开关也在此页。

按住宠物可以提起；轻甩会落回平台，中等力度会撞边反弹，用力甩出屏幕会收起。宠物不见时，可摇晃手机唤回，在“设置 → 宠物”点“唤回”，或请 Agent 调用 `pet` 工具的 `summon`；总开关须开启。把宠物拖到屏幕上部松手可落在云朵或七彩泡泡上；点破泡泡可让它落下，开启吹气功能后可吹散云朵。点头部可抚摸，点身体可戳，连续戳会生气；倾斜手机时宠物会打滑。

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
