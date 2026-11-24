# ComfyUI 通用提交器框架与有状态链协议

本参考说明适用于把任意用户技能脚本接入 `comfy_save_submitter` / `comfy_save_workflow`，以及编写或运行 `CLI_ARGS`、`PAYLOAD_JSON` 与内置 API JSON 工作流。

## 双执行环境与安全边界

- **自动环境路由**：
  - 当前会话激活 **Linux 运行模式** 且内置 Linux 环境就绪时，干跑校验与正式执行均自动在 **Linux 虚拟环境**（`python3`）中运行，直接消费 Linux 内通过 `apk add` 安装的 `ffmpeg`、`ffprobe` 与第三方 Python 包（Alpine 为 musl ABI，不混用 Android 打包二进制）。
  - 其它情况（内置模式、Linux 未就绪、制作面板等无会话触发）均在应用打包的独立 **内置 Python 3.14** 中运行；`arm64-v8a` 已内置精简 FFmpeg 9.1 / `ffprobe` 9.1（含 8-bit `libx264`，不含 `lavfi` 虚拟输入），并通过 `runDir/bin` 符号链接前置进 `PATH`，脚本与子进程可直接调用。
  - 干跑自检与正式运行始终使用同一执行环境，并在 `comfy_list_hosts`、`comfy_save_submitter`、`comfy_preflight_workflow`、`comfy_run_workflow` 中返回 `execution_runtime`（`LINUX` 或 `BUILTIN_PYTHON`）与工具探测状态 `available_tools`。
  - 提交器使用单次运行目录和独立的工具配置。声明外部工具依赖时，以该提交器的 `available_tools` 和干跑结果为准。
  - 耗时超过 20 秒的 `comfy_run_workflow` 会自动转入后台任务（支持 `run_in_background: true`），后续可用 `job_wait` / `job_status` / `job_stop` 管理。
- **路径隔离与零凭据暴露**：
  - 宿主机在一过性运行目录内隔离执行提交器；在 Linux 模式下通过 `/mnt/comfy_run` 与 `/mnt/comfy_stage` 自动完成宿主机与 guest 路径双向换算，并默认设置 `PYTHONDONTWRITEBYTECODE=1`（防止向技能目录写入 `.pyc`）。
  - 主机密钥、Cookie 与令牌永不进入脚本进程。宿主机启动 `127.0.0.1` 本地回环鉴权代理并配置 `NO_PROXY=127.0.0.1,localhost`，脚本只需访问传入的回环地址即可透明鉴权。

## 标准接入流程（零改写适配任意外部技能）

1. **查看环境与主机**：调用 `comfy_list_hosts` 确认目标 `host_id`、当前 `execution_runtime` 与 `available_tools`（例如 `ffmpeg` / `ffprobe`）。
2. **零改写导入脚本包**：调用 `comfy_save_submitter`，使用 `skill_import: { "skill_name": "<技能名>", "entry_path": "scripts/<入口>.py" }`（可省略 `file_name`，自动保留同级模块与 `assets/` 相对目录并跳过 `__pycache__`/`.pyc`）；不手工抄录源码或内联 JSON 到工作区。
3. **阅读脚本参数并映射工作流配置**：通过 `read_skill_file` 阅读脚本的 `argparse` 参数与输出方式，将全部参数配置为 `comfy_save_workflow` 字段：
   - **固定/日常分离**：提示词、输入素材、随机种子归入 `CONTENT`；模型名、采样步数、分辨率、服务端版本等基线参数归入 `FIXED`。
   - **参数格式模板（`cliMode: "TEMPLATE"` + `cliValueTemplate`）**：表达组合参数，如 `--load-file 1=/path/a.png` 配 `"{node}={file}"`、`--guide 60:/path/mid.png` 配 `"{frame_idx}:{file}"`、`--set 10.prompt="hi"` 配 `"{node}.{input}={json}"`。`{file}` 自动暂存工作区文件并完成 Linux 路径换算，`{json}` 自动 JSON 序列化。
   - **布尔反向标志（`cliFalseFlag`）**：适配 `argparse.BooleanOptionalAction`，`true` 传 `cliFlag`，`false` 传 `cliFalseFlag`（如 `--no-free-after`）。
   - **任意文件与目录（`ANY_FILE` / `DIRECTORY` / `stageParentDirectory`）**：用于传入工作流 JSON、切片计划 JSON 或整目录输入。若计划文件内部按相对路径引用同级切片文件，把 `stageParentDirectory` 设为 `true`，宿主会自动暂存整个父目录树。
   - **种子策略（`seedPolicy`）**：设为 `"RANDOM_EACH_RUN"` 时，调用方省略种子即可每次自动生成独立正整数随机种子，实际种子写入 `applied_seeds` 与链节点上下文。
   - **主机元数据插值**：在 `cliBaseArgs`、字段默认值或字符串入参中使用 `{host.comfyui_version}`、`{host.id}`、`{host.name}`，运行时自动读取 `/system_stats` 填入真实版本，免去硬编码版本号。
   - **输出解析与历史补拉**：
     - 脚本无 `--receipt` 参数时把 `receiptFlag` 设为 `null` 或 `""`；宿主自动解析 stdout 尾部单行或多行缩进 JSON，并支持用 `promptIdPattern` 从纯文本提取 `prompt_id`。
     - 产物支持 `IMAGE`、`VIDEO`、`AUDIO`、`MODEL_3D`（`.glb`/`.gltf`/`.obj`/`.stl` 等）与通用 `FILE`。当脚本采用 `--submit-only` 或未下载服务端产物（如 `SaveGLB`）时，默认开启 `backfillOutputsFromHistory: true`，宿主会自动按 `prompt_id` 轮询 `/history` 并补拉至工作区；仅产生服务端中间包而无本地输出的工作流可声明 `allowEmptyOutputs: true`（若脚本输出的是不会进入远端 `/history` 的合成或只提交不等待的 `prompt_id`，须同时把 `backfillOutputsFromHistory` 设为 `false`，避免无效轮询直至超时）。
   - **外部工具与平台依赖（`requiredTools` / `requiredPlatforms`）**：如实声明脚本依赖的外部命令（如 `["ffmpeg", "ffprobe"]`）或平台限制（如仅 macOS 可用的脚本声明 `["macos"]`）。执行前若不满足，宿主直接失败关闭并给出明确安装或切换提示。
   - **单工作流超时（`timeoutSeconds`）**：长视频或高步数任务可单独设置 `10～7200` 秒。
4. **先导图 / 预检再真跑**：配置 `emitWorkflowFlag`（如 `"--emit-workflow"` 搭配默认 `emitWorkflowTakesPath: true`，或 `"--dry-run"` 搭配 `emitWorkflowTakesPath: false`）或 `preflightOnlyFlag`（如 `"--preflight-only"`）后，调用 `comfy_run_workflow(run_mode="emit_workflow")` 导出完整 API 图核对参数接线，或用 `run_mode="preflight_only"` 仅做主机预检，确认无误后再用 `run_mode="execute"` 提交 GPU。

## `PAYLOAD_JSON` 协议（自编提交器规范）

宿主执行：`<entry>.py --payload <payload.json>`。

### 输入字段（`<payload.json>`）
- `dry_run`（boolean）、`run_mode`（`"execute" | "emit_workflow" | "preflight_only"`）、`stage`（`"single" | "first" | "next" | "dry_run"`）
- `branch_id`、`branch_policy`（`"ISOLATED_DIR" | "IN_PLACE_OVERWRITE"`）、`segment_index`、`parent_node_id`
- `read_latent_dir`、`write_latent_dir`、`latent_dir`：有状态链宿主统一托管的服务端读取目录与本段写入目录
- `output_dir`、`result_path`、`emit_workflow_path`（导图模式可选）、`previous_workflow`（续段可选）
- `host_proxy_url`（与环境变量 `COMFY_HOST_URL` 一致）、`workflow_id`、`host`（`{id, name, comfyui_version}`）
- `inputs`（所有工作区路径已替换为当前运行时可读的暂存路径）、`inherited_state`

### 输出格式（写入 `result_path` 或输出至 stdout 末尾 JSON）
```json
{
  "prompt_id": "comfyui-prompt-uuid",
  "summary": "简短执行摘要（可选）",
  "outputs": [{"path": "/abs/or/relative/output.png", "kind": "IMAGE"}],
  "continuation_state": {
    "latent_dir": "comfy_chain/wf/chain_xxx/main",
    "clip_index": 1
  }
}
```
- 进度行：向 stdout 输出 `{"type":"progress","percent":50,"message":"sampling"}` 可实时刷新进度。
- 编写支持 `ISOLATED_DIR` 分叉隔离的 `PAYLOAD_JSON` 脚本时，必须从 `read_latent_dir` 读取前段潜空间、向 `write_latent_dir` 写入当前段潜空间，并在 `continuation_state` 中如实回传 `latent_dir = payload["write_latent_dir"]` 与 `clip_index = payload["segment_index"]`。

## `STATEFUL_CHAIN` 分支隔离、回验与配套证明

- **严格真隔离判定**：`CLI_ARGS` 提交器仅在同时声明成对 `readLatentDirFlag` 与 `writeLatentDirFlag` 时才允许执行 `ISOLATED_DIR`；仅配置单 `--latent-dir`（即使含 `branchIdFlag`）在分叉前即诚实降级为 `IN_PLACE_OVERWRITE`。
- **执行后物理槽位回验与 DAG 守卫**：
  - 每次执行后宿主都会核对「宿主分配目录与段号」vs「脚本实际回报 `reported_latent_dir`、`clip_index` 与 `server_latent_slot_key`」。
  - 若脚本未遵守隔离目录导致实际槽位与既有非祖先 `ACTIVE` 兄弟节点碰撞，宿主自动将本节点降级为 `IN_PLACE_OVERWRITE`，并将冲突兄弟及其后继标为 `OVERWRITTEN_BY_SIBLING`，同时绝不误杀父/祖先链并给出 `isolation_warning`。
- **链状态完全可观测**：`comfy_run_workflow` 与 `comfy_get_chain_state` 均返回 `assigned_read_latent_dir`、`assigned_write_latent_dir`、`reported_latent_dir`、`clip_index`、`server_latent_slot_key`、`has_workflow_snapshot`、`isolation_warning` 及精简 `continuation_summary`。
- **续段配套文件（`sidecarSuffixes`）**：声明如 `[".segment.json"]` 后，宿主自动按原始字节保存前段工作流与同名配套证明文件并在下一段运行前逐字节还原至 `previous_workflow.json` 同目录，保证 SHA-256 来源校验一致。
