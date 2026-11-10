---
name: pet-pack-authoring
description: 当用户需要设计、制作、校验或打包 Infinity Deck 会话宠物素材和 .idpet 宠物包时使用。
---

# 会话宠物包制作

`.idpet` 是 ZIP 包，根目录包含必需的 `manifest.json`、`thumbnail.webp`、`clips/` 素材目录，可包含 `persona.json` 与 `LICENSE.txt`。素材是声明式文件，角色只响应基座发出的信号。

## 清单

`id` 使用 1 至 64 位小写字母、数字、点、连字符或下划线，首字符不能是点。`license.redistribute` 控制包能否在应用内导出分享；`commercial` 声明商业使用许可。显示高度 `height_dp` 为 80 至 160，`facing` 为 `left` 或 `right`。

```json
{
  "contract": "0.2", "id": "example.pet", "name": "小鱼", "version": "1.0.0", "author": "作者",
  "license": {"name": "CC BY 4.0", "redistribute": true, "commercial": true},
  "display": {"height_dp": 125, "facing": "right"},
  "clips": {
    "stand": {"type": "pose", "file": "clips/stand.webp", "anchor": [0.5, 0.95]},
    "wave": {"type": "frames", "file": "clips/wave.webp", "anchor": [0.5, 0.95], "cols": 4, "rows": 2, "count": 8, "fps": 8, "loop": true},
    "dance": {"type": "video", "file": "clips/dance.mp4", "anchor": [0.5, 0.95], "alpha": "side_by_side", "loop": true, "fallback": "stand"}
  },
  "expressions": {"base_clip": "stand", "face": {"center": [0.5, 0.4], "radius": [0.2, 0.13]}, "set": {"neutral": "clips/face-neutral.webp", "blink": "clips/face-blink.webp"}},
  "map": {"idle": {"clip": "stand"}, "task.generating": {"clip": "wave", "say": true}}
}
```

`pose` 是单张透明图；`frames` 是网格图集；`video` 使用 MP4，透明方式为 `side_by_side`（左 RGB、右 Alpha）或 `chroma_green`，`fallback` 指向同包 `pose` 或 `frames`。共同字段 `anchor` 是平台接触点的归一化坐标，`scale` 默认 1；可选 `motion` 为 `breathe`、`nod`、`hop`、`shake`、`bounce`、`none`。可选 `rig` 包含 `neck`、`hair_top`、`tail`（`root`、`tip`、`radius`）、`sitting`、`lying`。表情图与基底图逐像素对齐，预留名称为 `neutral`、`blink`、`happy`、`laugh`、`surprised`、`confused`、`sad`、`angry`、`sleepy`。

映射支持持续 `clip`、进入动作 `enter` 和 `enter_ms`、周期插入 `sometimes`（`clip`、`every_s`、`for_ms`）、空闲 `behaviors`（`clip`、`weight`、`move`、`min_s`、`max_s`）、`expression`、`say` 和 `sequence`（`clip`、`launch`、`into_island`）。至少提供 `idle`；未声明的信号回退到所属大类再到 `idle`。

## 信号与数据

状态：`idle`、`idle.long`、`sleep`；`task.waiting`、`task.working`、`task.needs_user`、`task.queued`、`task.thinking`、`task.generating`、`task.tool`（可加工具名后缀）、`task.approval`、`task.ask`、`task.finishing`、`task.done`、`task.error`、`task.cancelled`；`env.reduced_motion`、`env.power_save`、`env.theme.dark`、`env.theme.light`。

事件：`island.fx.wait`、`island.fx.stream`、`island.fx.tool`、`island.fx.approval`、`island.fx.retry`、`island.wider`、`island.narrower`、`island.focus`、`island.blur`、`island.send`、`device.unfold`、`device.fold`、`push.left`、`push.right`、`touch.tap`、`touch.held`、`touch.fall`、`touch.land.soft`、`touch.land.hard`、`fall.over`、`get.up`、`role.enter`、`role.exit`、`session.other.active`。

台词模板可使用 `{session.title}`、`{model.name}`、`{tool.name}`、`{tool.target}`、`{output.chars}`、`{elapsed.s}`、`{error.kind}`、`{ask.text}`、`{idle.s}`、`{daypart}`。

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
