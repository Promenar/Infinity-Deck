# 影视制作工具与执行路线

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
