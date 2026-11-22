# ComfyUI 受控 Python 3.14 提交器与有状态链协议

本参考说明适用于通过 `comfy_save_submitter` 编写或更新 `AGENT_AUTHORED` 脚本，以及在 `comfy_save_workflow` 中绑定 `CLI_ARGS` / `PAYLOAD_JSON` 工作流。

## 运行隔离与安全边界

- 所有提交器均在应用打包的独立 Python 3.14 环境与一过性运行目录（`run_<id>/`）中执行，仅依赖 Python 标准库，不调用系统包管理器或外部持久化虚拟环境。
- 不向提交器传递任何 ComfyUI 主机密钥、Cookie 或令牌；宿主机在本地启动 `127.0.0.1` 回环代理，脚本只需将请求发往传入的本地代理基地址，由宿主自动注入主机鉴权头。

## 零改写导入已启用技能包（推荐用于桌面验证脚本）

- 禁止将 `$INFINITY_SKILLS/<skill>/scripts/*.py` 手工抄录到工作区或内联其中的 `assets/` JSON。
- 调用 `comfy_save_submitter` 时直接传入：
  - `skill_import: { "skill_name": "<已启用技能名>", "entry_path": "scripts/<入口脚本>.py" }`（可选 `include_paths`，省略时自动导入主脚本同级目录模块及 `assets/` 目录，完整保留相对层级并跳过 `__pycache__`/`.pyc`）；
  - 使用 `skill_import` 时可省略 `file_name`（默认沿用 `entry_path`，如 `scripts/submit_h3.py`），也可同时通过 `support_paths` 传入 `/skills/...` 或 `/mnt/skills/...` 下的附加文件。

## `PAYLOAD_JSON` 协议（推荐用于新编写或自编脚本）

宿主执行命令：`<entry>.py --payload <payload.json>`。

### 输入 JSON 字段（`<payload.json>`）

- `dry_run`（boolean）：干跑自检时为 `true`；脚本应校验标准库导入、参数解析或图构造逻辑后以退出码 `0` 返回，不得访问真实网络或提交 GPU 任务。
- `stage`（string）：单次执行为 `"single"`；有状态链为 `"first"` 或 `"next"`；干跑为 `"dry_run"`。
- `branch_id`（string）：主链为 `"main"`；分叉续接段为宿主机分配的分支标识（如 `"b_01_v1_a1b2"`）。
- `branch_policy`（string）：`"ISOLATED_DIR"`（支持真物理分支隔离时默认）或 `"IN_PLACE_OVERWRITE"`（原地覆写；单目录耦合 CLI 脚本分叉时宿主自动诚实降级为此策略）。
- `segment_index`（int）：当前段落序号（`1` 起步，逐段递增）。
- `parent_node_id`（string，可选）：`stage == "next"` 时为父节点 ID（如 `"seg_01_v1"`）。
- `read_latent_dir` / `write_latent_dir` / `latent_dir`（string，有状态链可选）：由宿主机自首段起统一托管的 ComfyUI 服务端潜空间读取目录与本段写入目录（分叉真隔离时 `write_latent_dir` 指向独立分支目录）。
- `output_dir`（string）：本地下载目录绝对路径；脚本须将生成结果（PNG/MP4/WAV 等）下载到此目录。
- `result_path`（string）：结构化结果回执 JSON 写入路径；建议同时写入该文件，或在 stdout 最后一行输出单行 JSON。
- `host_proxy_url`（string，可选）：本地回环 ComfyUI 代理根地址（与环境变量 `COMFY_HOST_URL` 相同），仅在 `dry_run == false` 时提供，如 `"http://127.0.0.1:43210/p/<nonce>"`。请求直接拼 `/prompt`、`/history/<prompt_id>`、`/view?...`、`/upload/image`。
- `workflow_id`（string，可选）：所属工作流 ID。
- `inputs`（object）：已合并 `CONTENT` 与 `FIXED`（含 `fixed_overrides`）后的最终字段映射；媒体文件字段值已替换为沙箱内可读的本地暂存文件绝对路径。
- `inherited_state`（object，可选）：有状态链 `stage == "next"` 时来自父节点的继承只读上下文（包含 `latent_dir`、`clip_index`、前序脚本返回的自定义键值及可选 `workflow_snapshot`）。

同时提供环境变量：`COMFY_HOST_URL`、`COMFY_OUTPUT_DIR`、`COMFY_RESULT_PATH`、`COMFY_PAYLOAD_PATH`。

### 输出 JSON 格式（写入 `result_path`）

```json
{
  "prompt_id": "comfyui-prompt-uuid",
  "summary": "简短执行摘要（可选）",
  "outputs": [
    {"path": "/abs/or/relative/to/output_dir/result.png", "kind": "IMAGE"}
  ],
  "continuation_state": {
    "latent_dir": "comfy_chain/shot_01/main",
    "clip_index": 1,
    "custom_key": "value"
  }
}
```

- `outputs`：`kind` 可选 `"IMAGE"`、`"VIDEO"`、`"AUDIO"` 或 `"FILE"`；若未声明 `outputs`，宿主也会自动扫描 `output_dir` 中全部非空媒体文件。
- 进度行：向 stdout 输出 `{"type":"progress","percent":50,"message":"sampling"}` 可实时更新制作/任务进度。

## `CLI_ARGS` 协议（适配桌面端现成脚本）

- 干跑自检执行 `<entry>.py --help`；运行按工作流字段声明拼接 `cliBaseArgs`、各字段 `cliFlag`（`VALUE` / `REPEAT` / `BOOL_FLAG` / `TEXT_FILE` / `JSON`）及宿主通用标志（`hostFlag` 默认 `--host`、`outputDirFlag` 默认 `--output-dir`、`receiptFlag` 默认 `--receipt`）。
- 若脚本不支持 `--receipt`（例如 `submit_music3.py` 等将结果 JSON 打到 stdout 并直接写文件到 `--output-dir` 的脚本），保存工作流时把 `receiptFlag` 设为 `null` 或 `""`：宿主**无需包装脚本**即会自动解析 stdout 末行 JSON 并扫描 `--output-dir` 回收媒体产物。
- 启用 `STATEFUL_CHAIN` 时：
  - 宿主自首段 `stage=first` 起自动分配 `chain_id` 与服务端安全目录 `comfy_chain/<workflow_id>/<chain_id>/main`，无需把 `latent_dir` 塞进 `CONTENT`；单活跃链下后续 `stage=next` 亦可省略 `chain_id` 由宿主自动续接。
  - 首段与续段均按 `chainSpec` 自动注入 `stageFlag`（默认 `--stage`）、`latentDirFlag`（默认 `--latent-dir`）、可选 `segmentIndexFlag`（如 `--clip-index`）及续段 `previousWorkflowFlag`（默认 `--previous-workflow`）。
  - 若脚本支持分离父读目录与分支写目录（声明 `readLatentDirFlag` 与 `writeLatentDirFlag`）或声明 `branchIdFlag` / 使用 `PAYLOAD_JSON`，分叉执行真正隔离的分支目录并各自保持 `ACTIVE`；若仅为单 `--latent-dir` 耦合脚本（如 `submit_h3_motion.py`），分叉时宿主会自动诚实降级为 `IN_PLACE_OVERWRITE`，并依据实际写入目录与段号把被覆写的兄弟及下游段标为 `OVERWRITTEN_BY_SIBLING`。

## `STATEFUL_CHAIN` 续接与分支约定

- `latent_dir` **必须使用 ComfyUI 服务端相对目录**（如 `"comfy_chain/shot_01/chain_abc/main"`）或语义命名目录，**禁止将本地一过性 `output_dir` / `run_<id>` 路径回填为 `latent_dir`**（运行目录会在落盘后清理）。
- 从 `inherited_state` 读取 `latent_dir` 等继承键；宿主机已将 `lockedInheritedKeys` 锁定，并保证 `server_latent_slot_key` 严格反映实际物理写入目录与段号。
