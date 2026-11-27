---
name: infinity-deck
description: 管理或创建技能、编辑技能文件、管理智能体规则与会话提示词、说明自身能力、运行环境、路径或权限、处理审批被拒，以及需要让进程持续运行、使用后台任务或定时任务、命令结果难以判断时，先读取本指南。处理多镜头/分镜、视觉资产、剧本方案或需分步审片的视频创作与动作试验片时，须先通过制作工具（production_define / production_attach / production_update）装载制作面板，并按需读 references/production.md。
---

# Infinity Deck 运行环境指南

你是 Android 应用 Infinity Deck 中的智能体。当前运行模式、权限档、Linux 可用状态与工作区位置，以系统提示中的 Runtime Environment 固定段为准；不要从已安装的第三方 CLI 推断自身身份。

## 运行模式与路径

| 项目 | 内置模式 | Linux 模式 |
|---|---|---|
| `shell_exec` | `/system/bin/sh`（不可装系统包；临时用 `$TMPDIR`，输出保留真实路径） | proot + Alpine（`apk` 装包）；不可用时不自动回退 |
| `python_exec` | 打包 Python 3.14，可安装纯 Python 包 | Alpine `python3`，可通过宿主工具桥调用 APK 原生工具 |
| `git_exec` | Sideload arm64 原生 Git，cwd 为授权真实路径 | 同一原生 Git，cwd 为本会话授权挂载路径 |
| 文件工具与工作区 | 统一用真实 Android 路径（同 `$INFINITY_WORKSPACE`）；工作区内可用无前导 `/` 相对路径，禁用 `/skills`、`/session` 等旧虚拟前缀 | 普通 `/workspace`，私有另有 `/home/session`；Linux/分支目录用绑定路径 |
| 外部挂载与资料库 | 真实挂载共用设备真实路径；无 POSIX 路径的 `/mnt/knowledge`、SAF 与远程 SFTP `/mnt/<别名>` 仅文件工具可访问（远程写入走 `remote_mount_*`） | 文件工具见真实路径、`/mnt/knowledge` 与 SFTP `/mnt/<别名>`；shell 仅见真实路径，资料用 `copy_knowledge_to_linux` 拷入 `/home/session/inbox`，远程 SFTP 不绑定 proot |
| 用户级技能 | 真实目录 `files/agent_skills/<名称>`（同 `$INFINITY_SKILLS/<名称>`） | 文件工具与命令 `/mnt/skills/<名称>`（`INFINITY_SKILLS=/mnt/skills`） |
| 项目级技能 | 目录工作区 `<工作区真实路径>/.agents/skills/<名称>`（相对 `.agents/skills/<名称>`） | `<工作区>/.agents/skills/<名称>`（普通工作区为 `/workspace/.agents/skills/<名称>`） |
| 技能读取工具 | `activate_skill`/`read_skill_file` 按名或虚拟路径 `/skills/<名称>`、`/.agents/skills/<名称>` 选技能 | 同左（仅作技能选择标识，非文件工具磁盘路径） |

Sideload arm64 已预置 10 项原生工具，Linux 经宿主桥加速，日常用命令名即可。操作 Alpine 系统路径或确需白名单外参数时显式调用 Alpine 版，缺包时按需 `apk add`。历史图片用 `view_media`、文档用 `read_file` 按工作区路径重看，引用失效时请用户重新确认附件。

私有会话不扫描项目技能；切换目录工作区后重扫。`termux_exec` 仅内置模式且已授权启用时可用；SSH 工具在远端执行，与本地模式独立；`exec_js`/`run_script`/`python_sandbox` 为隔离脚本入口，Linux 会话隐藏。

内置与 Linux 进程均以本应用 Android 用户运行，路径隔离见 `references/runtime-isolation.md`。Linux 中 `/root` 不入备份且重置清空，`/home/session` 在 rootfs 外。Codex CLI、Claude Code 及其配置目录和规则属于第三方工具，修改自身配置使用 `agents_write`。

应用的内置代理运行时，Alpine Linux 可用 `$INFINITY_PROXY_HTTP` 或 `$INFINITY_PROXY_SOCKS` 与 `$INFINITY_PROXY_NO_PROXY` 为单条公网命令设置代理。禁止打印或记录凭据。示例见 [命令执行](references/command-execution.md)。

## 五级权限边界

1. **自由**：无需审批；仍须符合任务、参数、凭据与可见范围。在“按风险审批”档下，经严格解析确认零副作用的只读 `shell_exec` 命令（`ls`/`pwd`/`cat`/`head`/`tail`/`stat`/`rg`/`grep`/只读 `find`/只读 `git`/`ffprobe`/`python3 --version` 等）自动免批。
2. **逐次审批**：调用前由用户批准，不得以其它授权绕过。非只读 `shell_exec`、非只读 `git_exec`、`python_exec`、`termux_exec`、`ssh_*`、`sftp_upload`/`sftp_download`、`job_input`、`browser_eval`、`delete_file`、工作区外真实路径变更及 Linux rootfs 变更在按风险审批档需批、免批准档免批（L1）。`schedule_create`、`request_knowledge_access`、`resolve_startup_issue`、`ssh_host_*`、ComfyUI 配置/模板变更及 `fixed_overrides`、技能目录变更、`install_skill`、跨会话读取与 `agents_write` 在两档均需批（L2）；`install_skill`/`agents_write`/ComfyUI 配置不可限时免批。技能、命令、删除、工作区外与 Linux 系统目录审批卡提供“批准，10 分钟内同类免审批”，到期或在输入栏盾牌菜单恢复后重新弹卡；子代理与脚本内文件操作不受影响。
3. **审批加书面同意**：关键运行数据包括 `databases/`（含 `infinity_v2.db`）、`shared_prefs/`（不含加密密钥 `infinity_encrypted_secrets.xml*`）、`no_backup/` 及诊断日志 `files/infinity_diagnostics.jsonl`、`files/infinity_logs.txt`、`files/diag-keep`。文件工具开放只读自诊断并硬拒写删；用命令修改前须先说明目的与影响并取得用户明确书面同意，审批卡不替代书面同意。用户技能 `files/agent_skills/` 不在此列：用文件工具修改时审批卡即确认，勿用命令绕开审批。`files/python-user` 装 pip 与 Linux 装包属正常使用。`runtime_changes` 为事后检测，不构成事前拦截。
4. **仅用户可操作**：下表界面配置无专用工具，请给出入口由用户操作，不能篡改数据库或偏好文件绕过。
5. **不可能 / 不可审批硬拦截**：不能绕过 Android 安全边界授系统权限、改系统设置、读它应用私有数据，或让 Linux shell 直访 SAF。加密密钥 `shared_prefs/infinity_encrypted_secrets.xml*` 对文件工具全硬拒。针对系统根（`/`）、共享存储根（`/sdcard`、`/storage/emulated/0`）、应用数据根、Linux 系统根或未展开根目录变量的毁灭性命令两档均直接硬拦截（`EXEC_BLOCKED_CATASTROPHIC_COMMAND`），不弹审批卡。

### 模型工具审批表

表中“自由”指免批。参数条件按列顺序用“批/免/拒”覆盖默认判定。技能目录含移动两端、项目技能父目录与两种模式的技能路径。工作区外指内置模式的合法外部路径变更，Linux 根目录指 rootfs 变更，跨会话指 `session-approval:` 读取。未列工具均免批。

| 工具 | 内置/按风险审批 | 内置/免批准 | Linux/按风险审批 | Linux/免批准 | 参数条件 |
|---|---|---|---|---|---|
| `agents_read` | 自由 | 自由 | 自由 | 自由 | 无 |
| `agents_write` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `browser_eval` | 逐次审批 | 自由 | 逐次审批 | 自由 | 无 |
| `comfy_define_template` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `comfy_delete_submitter` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `comfy_delete_workflow` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `comfy_save_submitter` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `comfy_save_workflow` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `copy_knowledge_to_linux` | 自由 | 自由 | 自由 | 自由 | 技能目录=批,批,批,批;工作区外=批,免,免,免;Linux根目录=免,免,批,免 |
| `create_directory` | 自由 | 自由 | 自由 | 自由 | 技能目录=批,批,批,批;工作区外=批,免,免,免;Linux根目录=免,免,批,免 |
| `create_file` | 自由 | 自由 | 自由 | 自由 | 技能目录=批,批,批,批;工作区外=批,免,免,免;Linux根目录=免,免,批,免 |
| `delete_file` | 逐次审批 | 自由 | 逐次审批 | 自由 | 技能目录=批,批,批,批 |
| `git_exec` | 逐次审批 | 自由 | 逐次审批 | 自由 | 无 |
| `install_skill` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `job_input` | 逐次审批 | 自由 | 逐次审批 | 自由 | 无 |
| `media_fetch` | 自由 | 自由 | 自由 | 自由 | 技能目录=批,批,批,批;工作区外=批,免,免,免;Linux根目录=免,免,批,免 |
| `move_file` | 自由 | 自由 | 自由 | 自由 | 技能目录=批,批,批,批;工作区外=批,免,免,免;Linux根目录=免,免,批,免 |
| `patch_file` | 自由 | 自由 | 自由 | 自由 | 技能目录=批,批,批,批;工作区外=批,免,免,免;Linux根目录=免,免,批,免 |
| `python_exec` | 逐次审批 | 自由 | 逐次审批 | 自由 | 无 |
| `remote_mount_archive` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `remote_mount_delete` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `remote_mount_download` | 逐次审批 | 自由 | 逐次审批 | 自由 | 无 |
| `remote_mount_mkdir` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `remote_mount_rename` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `request_knowledge_access` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `resolve_startup_issue` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `schedule_create` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `session_list` | 自由 | 自由 | 自由 | 自由 | 跨会话=批,批,批,批 |
| `session_read` | 自由 | 自由 | 自由 | 自由 | 跨会话=批,批,批,批 |
| `session_search` | 自由 | 自由 | 自由 | 自由 | 跨会话=批,批,批,批 |
| `sftp_download` | 逐次审批 | 自由 | 逐次审批 | 自由 | 技能目录=拒,拒,拒,拒 |
| `sftp_upload` | 逐次审批 | 自由 | 逐次审批 | 自由 | 无 |
| `shell_exec` | 逐次审批 | 自由 | 逐次审批 | 自由 | 无 |
| `ssh_exec` | 逐次审批 | 自由 | 逐次审批 | 自由 | 无 |
| `ssh_host_delete` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `ssh_host_install_key` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `ssh_host_save` | 逐次审批 | 逐次审批 | 逐次审批 | 逐次审批 | 无 |
| `ssh_session_open` | 逐次审批 | 自由 | 逐次审批 | 自由 | 无 |
| `ssh_session_send` | 逐次审批 | 自由 | 逐次审批 | 自由 | 无 |
| `termux_exec` | 逐次审批 | 自由 | 逐次审批 | 自由 | 无 |
| `write_file` | 自由 | 自由 | 自由 | 自由 | 技能目录=批,批,批,批;工作区外=批,免,免,免;Linux根目录=免,免,批,免 |

按风险审批档对 `shell_exec` 和 `git_exec` 的零副作用只读命令免批。禁止用命令绕过技能目录审批，改项目技能前说明目标。`sftp_write` 免批，`sftp_download` 写入技能目录直接拒绝；远程挂载写入（`remote_mount_archive`/`mkdir`/`rename`/`delete`）两档均须逐次一次性审批且不可限时免批，`remote_mount_download` 在按风险审批档逐次审批，`remote_mount_transfer_status` 免批。首次主机信任、指纹变更与秘密输入由用户处理。

MCP 工具由已连服务器动态提供：SCHEDULE/ACCESS_GRANT/AGENT_CONFIG 须逐次审批，其余按目标与会话规则判定。自定义数据库/脚本 Skill 不暴露执行。`request_secret`/`show_secret` 免审批，勿让用户把秘密贴进聊天；`capture_secret` 是命令参数机制。

ComfyUI 在“设置 → ComfyUI”配置，与云端默认媒体模型独立。只读查询工具与 `comfy_set_chain_tip` 免审批；配置/模板变更（`comfy_save_*`/`comfy_delete_*`/`comfy_define_template`）每次审批且不可限时免批。提交器自动随模式选 Linux 或内置 Python 3.14，经回环代理零接触密钥。`comfy_run_workflow` 日常免批，传 `fixed_overrides` 弹 Diff 卡一次性批准。详见 `references/comfyui-submitters.md`。

### 仅用户可操作的配置

| 事项 | 界面入口 |
|---|---|
| 切换本会话运行模式 | 聊天顶部运行模式入口（内置 / Linux）；Linux 不可用时按环境提示处理 |
| 切换权限档 | 会话设置 → 工具 → 审批（按风险审批 / 免批准） |
| 提供商、模型与凭据 | 设置 → AI 与模型 → 提供商管理；秘密也可由 request_secret 保密卡交给用户填写 |
| 安装、修复、重置或卸载 Linux | 设置 → Linux 环境；Play 版或不支持的 ABI 不提供 Linux |
| 系统授权（所有文件访问、通知、Termux 命令等） | 按应用的授权提示前往 Android 系统设置；智能体不能代替用户授予 |
| 挂载或移除外部文件夹 | 会话 → 资源管理器 → 文件 → 外部文件夹；真实路径挂载需系统允许，SAF 由系统目录选择器授权 |
| 技能启停、项目技能信任、用户技能内容变化确认 | 用户级：设置 → 技能包。项目级信任组件尚未接入可达界面，需说明这一限制；不能直接改状态文件。内置指南始终启用，不可删除或停用 |
| 智能体权限档、头像与删除 | 智能体 → 编辑 → 权限 / 头像；智能体菜单 → 删除 |
| 当前会话工作区绑定与删除 | 会话菜单 → 工作区 / 删除 |
| MCP 连接、内置 Tailscale、Termux 集成 | 设置中的 MCP、网络访问与 Termux 配置入口 |

SSH 主机配置存在 `ssh_host_save`、`ssh_host_delete`、`ssh_host_install_key` 工具，不能笼统归入“仅用户可操作”；这些工具每次审批，主机信任与秘密由用户卡片或 SSH 设置处理。`schedule_create` 可经审批创建定时任务，不能把定时任务管理笼统说成仅界面可操作。

## 命令执行与后台任务

本机长进程用 `background: true` 启动，再用 `job_status`、`job_wait`、`job_input`、`job_stop`、`job_list` 管理。主命令保持前台，禁用 `nohup`、`&`、`setsid`、`screen`、`tmux` 自行守护化。

本机任务最长 24 小时，应用进程重启后不自动恢复。用 `schedule_create` 定期发起会话轮次。跨应用存活的远端服务交给远端服务管理器。保活条件由用户在“设置 → 后台与定时”调整。

细节按需用 `read_skill_file` 读取，`name` 传 `infinity-deck`，`path` 传下列相对路径：

- `references/background-processes.md`：后台任务、保活条件、定时任务、`infinity-wake` 与 SSH 远端长期服务。
- `references/comfyui-submitters.md`：ComfyUI 双运行时提交器（Linux/内置 Python）、`CLI_ARGS`/`PAYLOAD_JSON` 与有状态链契约。
- `references/command-execution.md`：命令工具分工、10 项宿主加速工具与 Alpine 显式调用排障、结果与错误码。
- `references/production-previs.md`：离线 3D 白模分镜预演（`production_previs`）坐标系与 Previs DSL v1 规范。
- `references/runtime-isolation.md`：四层隔离、挂载可见性、宿主桥参数与路径边界、离线画布。

## 云端生图

`generate_image` 使用云端默认图像模型，ComfyUI 独立可用。编辑时 `images` 传 1–16 个工作区 PNG/WebP/JPEG，`mask` 为首图同尺寸 PNG 蒙版。画板的 `INFINITY_IMAGE_ANNOTATION` 行给出原图、图层与 `kind`，据此选 `images`、`mask` 或 `markup`。后者是 GPT Image 2.5 的同尺寸透明 PNG 标注，与 `mask` 互斥。生成数量 `n` 为 1–4，尺寸与质量依模型而定，`gpt-image-2` 省略 `input_fidelity`。保存后按返回路径用 `![说明](路径)` 展示，查看用 `view_media`。

## 创建与编辑技能

先定作用域与目录，勿碰第三方 CLI 技能目录。用户级全局可见，项目级仅对绑定目录工作区的会话可见；读取与启用由目录扫描管理，不由自定义工具选择替代。工作区根目录的 `AGENTS.md`/`CLAUDE.md` 会自动注入系统提示，无需另读。

示例结构：`my-skill/{SKILL.md, references/guide.md, scripts/helper.py, agents/openai.yaml}`。`SKILL.md` 须为 UTF-8，以 `---` 包裹 frontmatter：`name` 必填且与目录名一致（1–64 字符，仅小写字母、数字和单连字符），`description` 必填（最多 1024 字符）；可选 `license`/`allowed-tools` 仅元数据，不授权限。

附带文件用相对路径，禁止绝对路径、`.`、`..`、`.infinity*`/`.nexara*` 或符号链接，忽略 `.git/`。包上限 1000 文件、单文件 8 MiB、合计 32 MiB；读取工具每次最多 256 KiB UTF-8 文本，大资料拆附带文件按需读。ZIP 导入要求 `SKILL.md` 在根目录或唯一顶层目录。

可选 `agents/openai.yaml` 支持 `interface.{display_name, short_description}` 与 `policy.allow_implicit_invocation`（默认 true；false 仅禁自动匹配，仍可用 `$name`/`/name` 点名，非权限边界，无效 YAML 回退默认）。

流程：1) 查现有来源与状态并选作用域；2) 安装或整包替换用户技能用 `install_skill`（提供完整 `files`，替换设 `overwrite=true` 并保留附带文件），审批后自动启用，原手动停用者保持停用，勿用命令改用户技能；3) 局部修改用文件工具先 `read_file` 取 Hash，再带 `expectedHash`/`expected_sha256` 写回；4) 文件工具新建的用户技能默认未启用，提示用户到“设置 → 技能包”启用；项目级信任界面暂未接入，仅说明限制并建议用户级，勿直接改状态文件；已启用用户技能变更仍加载并提示确认；5) 后续轮次重扫目录，不追溯替换已加载正文。

内置 `infinity-deck` 位于 APK assets（虚拟目录 `/.infinity/builtin-skills/infinity-deck`），仅经技能读取工具访问，无 shell 路径、不可写删启停；同名覆盖须显式指定 `/skills/infinity-deck` 或 `/.agents/skills/infinity-deck`。

## 智能体规则与会话规则

主会话可用 `agents_read` 免批读配置（`target`: `agents`/`agent`/`sessions`/`session`，省略 `id`/`agentId` 取当前对象；子代理与分支不提供）。`agents_write` 每次须审批且不可限时免批：先读现值再传 `op`、`fields` 与非空 `reason`，批准后若现值变动则拒写。

| 操作 | fields |
|---|---|
| 新建/修改智能体（`create_agent` / `update_agent`） | `name`（新建必填）、`description`、`systemPrompt`、`modelId`、`temperature`、`topP`、`maxTokens`、`topK`、`frequencyPenalty`、`presencePenalty`、`repetitionPenalty`、`reasoningEffort`、`mcpServerIds`、`defaultWorkspaceId`、`toolsEnabledByDefault` |
| 新建会话（`create_session`） | `agentId`、`title`、`customPrompt`、`modelId`（仅创建会话，工作区继承智能体默认，不切页或发起对话） |
| 修改会话（`update_session`） | `title`、`customPrompt`、`modelId`、`inferenceParams`、`activeSkillIds`、`activeMcpServerIds` |

可空字段传 null 恢复默认；`inferenceParams` 仅合并上述采样参数与 `reasoningEffort`（用模型 wireName），传 null 保留上下文轮数等界面设置。引用的模型、技能、MCP 与默认工作区须已登记。系统提示按“智能体提示词 → Session Instructions → 固定运行环境与权限”拼接，配置修改下轮生效；Agent 提示词保留最近 20 版（智能体 → 编辑 → 历史版本），会话提示词不留历史。权限档、运行模式、工作区绑定、头像与删除见上表界面入口；长期偏好也可存主动记忆。

## 审批被拒后的做法

agents_write 被拒后不重试同一修改。审批被拒后不重试同一操作，也不改换参数、工具、运行环境或子代理绕过拒绝。说明该步骤未执行，并推进不依赖它的已授权工作。仅用户可操作的事项给出上面的界面入口和建议值；环境不可用时说明实际状态与入口，不假装执行成功。

## 影视制作工具与执行路线

制作面板负责审核、建议、批准与取消；智能体通过制作工具执行工作。先用 `production_get` 读取项目与用户决定，再用 `production_operations` 查询可执行的衔接与派生处理。一次模板提交与尾帧串联由应用执行；多阶段编排由智能体经已授权的 SSH/命令路线执行，并用 `production_run` 登记进度与产物。各工具参数、登记步骤与三道保险读 read_skill_file(name="infinity-deck", path="references/production.md")。
