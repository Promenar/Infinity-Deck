# 离线 3D 白模分镜预演规范（Previs DSL v1）

本规范由按需读取加载，适用于在制作项目中构建 3D 素模场景、人物走位与机位运动预演，并在制作面板「剪辑 → 分镜预演」实时播放、全屏自由审片、检查 180° 对话轴线，或一键导出白模构图帧 / 线性深度图 / H.264 预演视频。

## 1. 适用场景与制作工作流

1. 先通过 `production_update` 建好集与分镜（`shot_code` 及每镜 `duration_seconds`）。
2. 调用 `production_previs(action="set", episode_id="S1E01", scene={...})`（或先写文件再用 `path` 绑定，或在 `upsert_episode` 传入 `previs_path`）提交 3D 预演 DSL。工具会严格校验几何坐标、枚举值与 `shot_code` 对齐关系，写入 `<rootPath>/previs/<episodeId>.previs.json`。
3. 用户在「制作面板 → 剪辑 → 分镜预演」查看实时 3D 渲染：
   - 切换 **机位视角 / 自由视角**（自由视角显示全部机位视锥、角色路径虚线与 180° 轴线安全半圆）；
   - 切换 **3D 白模 / 线性深度图**（近白远黑，直接兼容 I2V / ControlNet Depth）；
   - 一键 **抓构图 / 抓深度 (PNG)** 保存至 `<rootPath>/previs/frames/` 并挂载到镜头 `preview_path` 或 `inputs` 参考图；
   - 一键 **导出预演视频 (H.264 MP4)** 保存至 `<rootPath>/previs/<episodeId>-animatic.mp4`。

## 2. 工具用法：`production_previs`

| `action` | 必填字段 | 可选字段 | 作用 |
|---|---|---|---|
| `get` | `episode_id` | — | 读取该集当前 `previs_path`、镜头数、总时长与完整 DSL |
| `set` | `episode_id` | `scene`, `path`（至少提供其一） | 校验并保存 3D 场景到 `<rootPath>/previs/<episodeId>.previs.json` 或指定 `path`（须位于 `rootPath` 内），更新绑定 |
| `patch_shot` | `episode_id`, `shot_code`, `shot` | — | 原地替换或追加单个镜头的 3D 机位与走位，自动校验并通知面板热重载 |
| `clear` | `episode_id` | — | 解除该集的 `previs_path` 绑定（保留磁盘 JSON 文件） |

## 3. 空间坐标系与朝向约定

- **坐标系**：右手 **Y-Up**，单位固定为**米 (`"m"`)**，`y = 0` 为地面。
- **锚点**：`props[].pos` 与 `actors[].from` / `to` 均为**物体底面中心**坐标（站立人物 `y = 0` 即双脚落地，无需手动加半高；桌面物品可设桌面高度为 `y`）。
- **朝向 `facing`（度数，绕 Y 轴）**：
  - `0` → 面向 `+Z`
  - `90` → 面向 `+X`
  - `180` / `-180` → 面向 `-Z`
  - `-90` → 面向 `-X`
  - 也可直接用 `lookAt: [x, y, z]` 或 `lookAtActor: "<actorId>"`（推荐对话戏直接用 `lookAtActor`），无需心算三角函数。

## 4. 完整 DSL 字段规范

- 顶层对象 `PrevisScene`（JSON 体积上限 512 KiB）：
  - `version`（必填）：固定整数 `1`
  - `unit`（可选，默认 `"m"`）：固定 `"m"`
  - `skyColor`（可选）：`#RRGGBB` 十六进制背景色，默认 `#121721`
  - `props`（最多 80 项）：`[{id, label?, shape?, pos:[x,y,z], size:[w,h,d], rotY?, rot?:[rx,ry,rz], color?}]`
    - `shape` 仅支持：`box`、`cylinder`（`w` 为底面直径、`h` 为高度）、`sphere`、`plane`、`ramp`（三角斜坡，高端在 `+Z`）
  - `actors`（最多 16 项）：`[{id, name?, height?, color?, accent?}]`
    - `height` 范围 `0.3 ~ 4.0` 米（默认 `1.7`）
  - `shots`（1 ~ 120 项）：`[{code, duration, label?, camera, actors?, axis?, depthRange?}]`
    - `code` 必须精确匹配该集分镜中的 `shot.code`
    - `duration` 单位秒（`0 < duration <= 600`），应与分镜 `duration_seconds` 对齐
    - `camera`: `{from:[x,y,z], to?:[x,y,z], lookAtFrom:[x,y,z], lookAtTo?:[x,y,z], fov?, focalLengthMm?, roll?, easing?}`
      - 优先填真实电影焦段 `focalLengthMm`（`12 ~ 200`，按 24mm 传感高换算垂直视角；如 `24` 广角、`35` 叙事、`50` 标准、`85` 特写），未填时取 `fov`（`15 ~ 120`，默认 `40`）
      - `easing` 仅支持：`linear`、`easeInOut`（默认）、`easeIn`、`easeOut`
    - `actors`: `[{id, from:[x,y,z], to?:[x,y,z], facing?, lookAt?, lookAtActor?, pose?}]`
      - `pose` 仅支持：`stand`、`walk`、`sit`、`wave`、`talk`、`point`、`lean`
    - `axis`（可选）：`{actorA, actorB, side?: 1 | -1}`，用于在自由视角绘制 180° 关系轴线与安全/越轴半圆（未填时自动取前两名出场角色）
    - `depthRange`（可选）：`[near, far]` 米（默认 `[0.5, 22.0]`），用于控制线性深度图的明暗范围；人物特写可收紧为 `[0.8, 8.0]` 以拉开面部和前景立体层次

## 5. 导演布景经验

- 常用尺度参考：成人身高 `1.7m`、眼高约 `1.55m`、柜台高 `0.95m`、餐桌高 `0.75m`、单人通道宽 `1.2m`、两人面对面交谈站距 `1.3m ~ 2.2m`。
- 摄影机安全距离：特写/近景（85mm）相机距人物脸部 `1.2m ~ 1.8m`、`lookAtFrom.y = 1.52`；中景（35mm/50mm）距 `2.2m ~ 3.5m`、`y = 1.45`；全景建立镜头（24mm）距 `5m ~ 9m`、`y = 2.0 ~ 3.2` 略俯视。
- 多人对话连续镜头保持 `camera` 始终处于 `actorA` 与 `actorB` 连线的同一侧，避免 180° 越轴。

## 6. 最小完整示例

```json
{
  "version": 1,
  "unit": "m",
  "props": [
    {"id": "counter", "label": "包子铺柜台", "shape": "box", "pos": [1.8, 0.0, -1.2], "size": [2.8, 0.95, 0.9]},
    {"id": "steamer", "label": "蒸笼", "shape": "cylinder", "pos": [1.2, 0.95, -1.2], "size": [0.55, 0.6, 0.55], "color": "#cbd5e1"},
    {"id": "lamp", "label": "路灯", "shape": "cylinder", "pos": [-2.6, 0.0, 1.0], "size": [0.16, 4.2, 0.16], "color": "#64748b"}
  ],
  "actors": [
    {"id": "luzhao", "name": "陆昭", "height": 1.76, "accent": "#60a5fa"},
    {"id": "vendor", "name": "掌柜", "height": 1.68, "accent": "#f59e0b"}
  ],
  "shots": [
    {
      "code": "SC01-01",
      "duration": 4.5,
      "label": "经过街口",
      "camera": {"from": [-3.4, 1.7, 3.6], "to": [-1.8, 1.55, 2.5], "lookAtFrom": [0.2, 1.35, -0.4], "focalLengthMm": 35, "easing": "easeInOut"},
      "actors": [
        {"id": "luzhao", "from": [-2.2, 0.0, 0.2], "to": [-0.3, 0.0, -0.2], "pose": "walk"},
        {"id": "vendor", "from": [1.8, 0.0, -2.0], "lookAtActor": "luzhao", "pose": "stand"}
      ],
      "axis": {"actorA": "luzhao", "actorB": "vendor", "side": 1}
    },
    {
      "code": "SC01-02",
      "duration": 5.0,
      "label": "摊前招呼",
      "camera": {"from": [-1.4, 1.52, 1.8], "lookAtFrom": [1.6, 1.48, -1.7], "focalLengthMm": 50},
      "actors": [
        {"id": "luzhao", "from": [-0.2, 0.0, -0.2], "lookAtActor": "vendor", "pose": "wave"},
        {"id": "vendor", "from": [1.8, 0.0, -2.0], "lookAtActor": "luzhao", "pose": "talk"}
      ],
      "axis": {"actorA": "luzhao", "actorB": "vendor", "side": 1},
      "depthRange": [1.0, 10.0]
    }
  ]
}
```
