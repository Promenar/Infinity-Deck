# 影视制作工具与执行路线

## 建项与读取

1. 已有清单时调用 `production_attach(manifest_path="productions/demo/production.json")`。清单路径须与根目录对应。同 id 项目保留数据库内容，外部差异会报告同步冲突。
2. 从零开始时调用 `production_define(title="雾港", kind="film", root_path="productions/demo")`，按依赖顺序用 `production_update` 成批建季、集、场、镜头与资产。kind 为 `film` 或 `series`，root_path 默认 `productions/标题 slug`。可选 logline（≤1000 字）、aspect_ratio（≤120 字）。
3. 已有小说或原剧本时先用文件工具读原文，拆成季、集、场、镜头、对白与资产。用 `production_define` 建项并分批录入。先写入集剧本文档，再用 `upsert_episode.script_path` 登记。同路径正文更新后也须重新登记路径。

`production_get(scope="overview")` 读取修订号、清单同步状态、阶段与成本提醒。`episode` 需要 `episode_id`，`shot` 还需 `shot_code`，`assets` 列资产与生成次数，`changes` 查审核与未结事项。只有 `changes` 的 `runs`、`chains`、`unregistered_files`、`open_items` 使用 `limit`（1～50，默认 10）与 `offset`（默认 0，非负）分页。

`production_cut` 用 camelCase 参数。list 可传 `episodeId`，create 必填 `episodeId`（每集最多 20 版），export 和 status 必填 `versionId`，可用 `episodeId` 核对归属。create 或 export 可传布尔 `burnSubtitles`。export 返回 accepted 后用 status 取进度与产物。

## 批量更新

`production_update({"operations":[...],"reason":"..."})` 一次接受 1～100 项并原子提交，可选 `reason`（最多 300 字）用于修改已锁定项时向用户说明原因。每项用 `op` 指定动作。静态格式错误按操作序号汇总，纠正后可整批重试。结构通过后按顺序核对父级、媒体与锁定状态。修订冲突先调用 `production_get`。待审内容由用户在制作面板处理；当直接修改已锁定剧本、视觉资产、镜头或制作阶段时，系统会弹出审批卡交由用户决定（涉及视觉参考图、剧本文件或增删重排镜头在批准后自动退回待审，锁定场允许的内容修改仍产生改动请求）。用户改写的提示词受保护。审核回合逐条处理，用 `agent_note`、`change_note` 或资产 `description` 记录结果。镜头或资产达到 6 次生成提醒时，继续生成前向用户说明。

| op | 必填字段 | 可选字段 |
| --- | --- | --- |
| upsert_season | id, number | title |
| upsert_episode | season_id, id, number, title | script_path |
| upsert_scene | episode_id, code, number, heading | |
| upsert_shot | episode_id, scene_code, code，新建还需 title, duration_seconds | title, duration_seconds, size, camera, camera_movement, visual, performance, dialogue, sound, transition, continuity, appearances, location_asset_id, prop_asset_ids, channel_id, mode, prompt, inputs, preview, preview_path, agent_note, change_note, position |
| set_shot_status | episode_id, shot_code, status | |
| add_take | episode_id, shot_code, path, service，有 task_id 时须提供实际 plan_snapshot | model, mode, task_id, usage_ref, plan_snapshot, note |
| upsert_asset | id, kind, name | description, looks, reference_voice, reference_views |
| add_asset_version | asset_id, path | note, task_id |
| upsert_look | asset_id, look_id, name | note, sheet_path, sheet_note, slots |
| set_look_slot | asset_id, look_id, slot_id | name, path, note |
| set_asset_view | asset_id, view_id | name, path, note, remove |
| set_asset_status | asset_id, status | |
| set_stage | stage, status | note |
| set_bible | documents | |
| set_video_settings | （至少任选一可选字段） | default_video_channel_id, reference_input_mode |
| set_experimental | enabled | |
| set_preview_enabled | enabled | |
| remove | target, ids，scene、shot 还需 episode_id | |

- 季 `S[0-9]+`（S1），集 `S[0-9]+E[0-9]{2,}`（S1E01，须属该季），场 `SC[0-9]+`（SC01），镜头 `SC[0-9]+-[0-9]+`（SC01-01，须属该场）。资产 `[a-z][a-z0-9-]*`，最多 48 字符。number 为正整数，duration_seconds 为有限正数，position 从 0 起算。
- status 取值按操作区分。镜头用 todo、generating、review，资产用 draft、review，阶段用 not_started、in_progress、skipped。stage 为 script、visual_dev、storyboard、animatic、generation、sound、edit。
- kind 为 character、location、prop、style、sound。mode 为 text、first_frame、first_last、reference。preview 为 keyframe、draft_clip、text_card 或 null。remove 的 target 为 season、episode、scene、shot、asset，ids 是按目标格式填写的非空无重复字符串数组。enabled 与 remove 为布尔值。
- **双模参考资产与版本分工红线**：
  - `versions`（`add_asset_version`）：仅用于同一定妆或原画的**纵向迭代替换**（`v1 → v2` 在面板中互斥审批，同时只生效一版）或**单图多视图整卡主图**；**禁止**用 `v1/v2/v3` 存放同一套造型的正/侧/背，也**禁止**因拆分三视图而另外新建重复角色资产。
  - **角色双模（`looks` / `upsert_look` / `set_look_slot`）**：同一造型同时支持单图多视图整卡（`sheet_path`/`sheet_note`，适合 Seedance 等直接接收整卡的模型）和分张单视图组（`slots` 里的 `front`/`side`/`back` 及自定义位，适合 H3 等分张多参模型）；支持二选一登记或双模并存，提交后即可由用户在造型区独立批准定稿。
  - **场景 / 道具 / 画风双模（`versions` + `reference_views` / `set_asset_view`）**：同样支持单图整卡（`add_asset_version`）和自由命名单视图组（`set_asset_view`，如全景、门头、细节），按项目设置的 `reference_input_mode`（`sheet` 或 `separate`）自动适配规划与提交。
- 外层字段用表中的 snake_case，嵌套字段使用下列精确拼写。问号表示可选。
  - dialogue 为 `[{characterAssetId, text, tone?, audioSource?}]`，audioSource 取 native（默认）或 dubbed。
  - appearances 为 `[{characterAssetId, lookId, referenceSlotIds?}]`，referenceSlotIds 为字符串数组（省略时自动选取该造型已备齐的默认视角，显式传空数组表示暂不引用）。prop_asset_ids 为资产 ID 字符串数组。
  - looks 为 `[{id, name, note?, sheetPath?, sheetNote?, slots?: [{id, name, path?, version?, note?}]}]`。`upsert_asset` 显式传 `slots` 须包含 `front`/`side`/`back`；推荐使用增量操作 `upsert_look` / `set_look_slot`。
  - reference_views 为 `[{id, name, path?, version?, note?}]`，用于 location、prop、style 资产的自由多视角单图组。
  - reference_voice 为 `{path?, transcript?, voiceSetting?, tone?}` 或 null。looks 和参考语音用于 character。配乐等音频成品登记到 sound 资产。
  - inputs 为 `[{role, path, label?, assetId?, atSeconds?}]`，最多 12 项。role 为 first_frame、last_frame、anchor、reference。非负 atSeconds 仅用于 anchor。
  - plan_snapshot 为 `{mode, inputs, prompt, channelId?, mediaInputs?, specs?, generationMode?, templateFingerprint?, params?}`。mediaInputs 每项用 location、kind、role。specs 可含 resolution、durationSeconds、width、height、frames、seed、durationSecondsExact。documents 为 `[{title, path}]`。
- 优先使用工作区相对路径，如 `productions/demo/ep1.md`。普通会话的 `/productions/demo/ep1.md`，以及 Linux 当前工作区下的 `/workspace/...`、登记的工作区目录或 `/home/session/...`，会归一后写入清单。独立私有目录保留归属。制作相对路径也可直接用于参考图。禁止 URI、点路径、空段、反斜杠、Linux 系统根和不属于项目的外部挂载。媒体须已保存到工作区。调用 `generate_image` 时参考图放在数组 `images`，例如 `{"prompt":"调整光线","images":["productions/demo/assets/hero.png"]}`。

### 批次一 建季、集、场、视频设置与双模资产

```json
{
  "operations": [
    {"op":"set_video_settings","default_video_channel_id":"comfy:h3-ref2va","reference_input_mode":"separate"},
    {"op":"upsert_season","id":"S1","number":1},
    {"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"归港","script_path":"productions/demo/ep1.md"},
    {"op":"upsert_scene","episode_id":"S1E01","code":"SC01","number":1,"heading":"外景 港口 黄昏"},
    {"op":"upsert_asset","id":"lin-wan","kind":"character","name":"林晚","description":"28岁，深色风衣。","reference_voice":{"path":"productions/demo/assets/lin-wan.wav"}},
    {"op":"upsert_look","asset_id":"lin-wan","look_id":"coat","name":"风衣","sheet_path":"productions/demo/assets/lin-wan-sheet.png","sheet_note":"三视图定稿整卡","slots":[
      {"id":"front","name":"正面全身","path":"productions/demo/assets/lin-wan-front.png","note":"正面单图 2x"},
      {"id":"side","name":"侧面全身","path":"productions/demo/assets/lin-wan-side.png","note":"侧面单图 2x"},
      {"id":"back","name":"背面全身","path":"productions/demo/assets/lin-wan-back.png","note":"背面单图 2x"}
    ]},
    {"op":"upsert_asset","id":"harbor-pier","kind":"location","name":"雾港老码头"},
    {"op":"set_asset_view","asset_id":"harbor-pier","view_id":"wide","name":"全景主视角","path":"productions/demo/assets/pier-wide.png"},
    {"op":"upsert_asset","id":"harbor-score","kind":"sound","name":"港口配乐"}
  ]
}
```

### 批次二 建镜头并登记已有候选

```json
{
  "operations": [
    {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","title":"林晚回望","duration_seconds":4,"mode":"reference","dialogue":[{"characterAssetId":"lin-wan","text":"船到了。","audioSource":"native"}],"appearances":[{"characterAssetId":"lin-wan","lookId":"coat","referenceSlotIds":["front","side","back"]}],"location_asset_id":"harbor-pier"},
    {"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"productions/demo/S1E01/takes/harbor.mp4","service":"已完成的生成任务"}
  ]
}
```

把示例中的路径改为当前会话的实际文件。尚未生成候选时，先建镜头，生成完成后再登记。

## 生成、派生、尾帧衔接与外部编排

制作面板负责审核、建议、批准与取消；智能体通过制作工具执行工作。先用 `production_get` 读取项目、镜头、资产和用户决定，再用 `production_operations(episode_id, shot_code)` 查询当前可执行的尾帧衔接、派生模板及参数表。请求不可用时按错误返回的可用项调整规划。

- `production_plan` 预览当前镜头输入和通道；`production_generate(episode_id, shot_code, params?)` 提交一次生成。`params` 为模板参数 key 对应的字符串、数值或布尔值对象，省略时取模板缺省值。
- `production_derive(episode_id, shot_code, take_id, operation_id, params?)` 把候选视频交给 DERIVE 模板；`operation_id` 是模板 id。结果作为新候选登记，并保存直接来源、操作、实际参数、模板指纹和文件实际规格；派生候选可作为新的来源。
- `production_score(asset_id, template_id?, prompt?, duration_seconds?, versions?)` 生成声音资产版本。模板须为 AUDIO/GENERATE、支持 MUSIC 且声明 DURATION、SEED 与音频输出；版本数缺省 4，各版本种子不同。省略时长时取项目最新剪辑版本的成片时长。已批准资产保留当前版本，新版本进入版本列表待审。
- `production_extend(action, episode_id, shot_code, ...)` 管理尾帧衔接链。`start` 可带 `start_take_id`、`segments`（默认 3，含起点段，最多 8）、`segment_seconds`（时长槽位可控的模板或通道）、`segment_prompts`（与段数相同）。起点候选直接作为段 0；后段只以上一段尾帧为首帧。`status`、`retry`、`cancel` 需要 `chain_id`；重试保留成功段并重做失败段及后段。应用在拼接时裁掉每个后续段开头一帧，成片登记为一个候选。
- `production_generation_tasks` 查询媒体任务。以上生成提交直接入队；模板定义和变更沿用 `comfy_define_template` 的审批机制。

一次模板提交与按序尾帧串联由应用执行。依赖工作流内部状态、多阶段控制或远端脚本的编排，由智能体通过已授权的 SSH/命令路线执行，并通过 `production_run` 登记进度和来源：

1. `start` 提供 `label`、可选正整数 `steps`，目标为 `episode_id` 与 `shot_code`，或单独 `asset_id`；保存返回的 `run_id`。
2. `progress` 提供 `run_id`、`completed_steps` 与一句 `message`；完成步数不能倒退或超过总步数。
3. `finish` 提供 `run_id` 与工作区项目根目录内的产物 `path`；可同时提供 `source_take_id` 与 `operation`、参数对象 `params`，以及按成片顺序排列的分段路径数组 `segments`。分段路径须对应实际参与成片的段文件，时间线从零连续累加实测时长。镜头产物登记为候选，资产产物登记为版本。
4. `fail` 提供 `run_id` 与 `reason`。完成、失败或取消后的登记项拒绝后续回报。用户取消只更新登记状态；外部脚本须由智能体通过对应远端任务入口停止。

三道保险：运行中登记项超过 30 分钟未回报会标记为未回报；审核消息和回合上下文携带有篇幅上限的未结事项（运行/失败/取消登记项、运行/失败链、未登记媒体等）；登记结束、回合结束及面板打开时审计候选和资产目录，列出未被候选、资产版本、链段、登记产物或剪辑引用的媒体。摘要省略时会报告条数，用 `production_get(scope="changes", limit=10, offset=0)` 分页读取 `runs`、`chains`、`unregistered_files` 与 `open_items`，按各类总数推进 offset。`shot` 范围同时包含候选来源、分段、规格与衔接链。

固定帧数工作流省略 `segment_seconds`，由工作流决定实际段长并在完成后写入实测时长。需要指定秒数时声明 DURATION 槽位。未知提交或轮询失败的远端任务先核实，已有队列入口确认归档后可重试链；不能用新任务绕过尚未核实的远端占用。
