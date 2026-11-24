# 命令执行环境与结果处理

选择命令工具、判断命令结果、处理失败时读取本文件。持续运行的进程、后台任务与定时任务见同目录的 `background-processes.md`。

## 工具分工

| 工具 | 运行位置 | 何时使用 |
|---|---|---|
| `shell_exec` | 本会话固定运行模式：内置模式为系统 `/system/bin/sh`，Linux 模式为应用内 Alpine | 本机命令的默认入口 |
| `python_exec` | 内置模式为打包 Python（仅 arm64），Linux 模式为 Linux 的 python3 | 直接执行一段 Python 源码；代码较长时写入文件再用 `shell_exec` 运行 |
| `termux_exec` | 同机的 Termux 应用，仅内置模式且用户已启用集成时提供 | 需要 apt 包、编译工具链、原生 Python 扩展 |
| `ssh_exec` | 用户在设置中保存的远端主机 | 远程运维；每条命令相互独立 |
| `ssh_session_open` 等 | 远端主机的交互终端 | 只在确需交互式程序或跨命令保留 shell 状态时使用 |
| `exec_js`、`run_script`、`python_sandbox` | 应用提供的隔离脚本入口，Linux 会话不提供 | 只需计算与工作区文件、不需要系统命令的脚本 |

运行模式由用户在聊天顶部切换，本轮内固定。环境不可用时工具返回错误，不会自动改用其它环境；按错误提示请用户处理。

## 环境差异

| 项目 | 内置 `shell_exec` | Linux `shell_exec` | `termux_exec` | `ssh_exec` |
|---|---|---|---|---|
| 起始目录 | 工作区根，即 `$INFINITY_WORKSPACE` | `$INFINITY_WORKSPACE`，普通会话为 `/workspace` | Termux 主目录，可用 `workdir` 指定 | 远端账号主目录 |
| 身份与可见范围 | 本应用的 Android 用户，可访问应用数据 | 同左；`/root`、`/etc` 等位于 rootfs | Termux 身份，范围由 Termux 权限决定 | 远端账号 |
| 跨调用状态 | 不保留，每次新进程 | 不保留，每次新进程 | 不保留 | 不保留；`cd`、环境变量在下一条命令失效 |
| 终端 | 无终端 | 伪终端 120 列 × 40 行，stderr 合并到 stdout | 无终端 | 无终端 |
| 标准输入 | `stdin` 参数 | 交互命令省略 `stdin`，让出后用 `job_input` | `stdin` 参数 | 不支持后续输入，交互用 `ssh_session_*` |
| 前台时限 | `timeout_seconds` 默认 60，最多 600；到时终止整个进程组 | 同左；默认 15 秒自动让出为后台任务 | 默认 60，最多 600；到时只停止本地等待，命令可能仍在运行 | 默认 60，最多 600；默认 15 秒自动让出 |
| 输出上限 | stdout、stderr 各保留末尾 32000 字符 | 同左 | 可能被 Termux 从开头截断，结果含原始长度 | `max_output_chars` 默认 32000，最多 256000 |
| 软件安装 | 不能安装系统软件包；pip 只能装纯 Python 包 | `apk add` | Termux 的 `pkg`、`apt` | 远端自身的包管理器 |

其它要点：

- Sideload arm64-v8a 将 APK 提供的 `git`、`ffmpeg`、`ffprobe`、`rg`、`fd`、`jq`、`sqlite3`、`zstd`、`bsdtar`、`rsync` 置于内置及 Linux 命令的 `PATH` 前端。用 `command -v` 查看当前入口。Linux 处理 `/etc`、`/usr` 等系统路径时显式调用 Alpine 的工具。
- 常规 Git 操作用 `git_exec`，例如 `{"args":["status","--short"],"cwd":"."}`。Linux 的 cwd 使用会话挂载路径，内置模式使用授权真实路径。HTTPS 凭据与 SSH 主机都来自应用设置。Git 的外部命令、仓库自定义钩子、过滤器和配置包含受限。宿主模式会拒绝不支持的仓库配置或参数。
- `git_exec` 在按风险审批档区分只读与写入，私有工作区执行后对账并刷新 Git 状态。传入 `background: true` 可在任务面板或用 `job_status`、`job_stop` 管理。
- 内置 `python3` 和 `pip` 由当前 shell 函数提供。子脚本可通过 `sys.executable` 或 `$INFINITY_PYTHON` 运行 Python。Play 的 Git 仍通过 `python3 -m infinity_git` 执行。
- 内置模式下，命令与文件工具完全共用真实 Android 规范路径，`pwd`、`echo "$INFINITY_WORKSPACE"` 与文件工具返回路径一致，命令输出直接保留真实路径（不再脱敏为字面量 `$INFINITY_WORKSPACE`）。
- 内置模式运行在 Android 原生环境，系统根目录没有可写的 `/tmp`，临时文件一律使用 `$TMPDIR`（指向 `cacheDir/exec-tmp`）或工作区临时文件并在结束后清理；Linux 模式 rootfs 内 `/tmp` 正常可写。
- 内置模式的命令可直接按真实路径访问已授权的真实路径挂载，仅 SAF 挂载与 `/mnt/knowledge` 资料库对命令不可见（需用文件工具读取或拷入工作区）。Linux 模式仅真实路径 `/mnt/<别名>` 可见，SAF 与 `/mnt/knowledge` 均不挂载到 Linux shell（资料库内容用文件工具读写，或经 `copy_knowledge_to_linux` 拷入 `/home/session/inbox`）。完整四层隔离矩阵见 `references/runtime-isolation.md`。
- 本机命令以普通应用身份使用设备当前网络，服务监听端口请用 1024 以上。
- `command` 最多 32000 字符，`python_exec` 的 `code` 最多 40000 字符且 UTF-8 不超过 120000 字节；更长的内容写入工作区文件后执行。
- 写入工作区的大日志或大文本用 `read_file` 分段读取：单文件最多 100 MiB，每次最多 2000 行；按结果“后续”行给出的 `startLine`（超长行另带 `lineByteOffset`）继续读，直到显示“已到文件末尾”。
- 命令直接读写工作区，删除或覆盖的文件无法恢复。应用工作区在命令结束后对账，结果的 `workspace_changes` 列出变化；自选目录工作区不对账。
- **按风险审批档**下，经严格词法解析确认零副作用的只读 `shell_exec` 命令（如 `ls`/`pwd`/`cat`/`head`/`tail`/`stat`/`rg`/`grep`/只读 `find`/只读 `git`/`ffprobe`/`python3 --version`）自动免批，其余命令与脚本逐条审批；**免批准档**下常规命令免批。针对根目录、共享存储根、应用数据根或未展开根目录变量的毁灭性命令在两档下均直接硬拦截（`EXEC_BLOCKED_CATASTROPHIC_COMMAND`）且不可审批。审批被拒后不重试，也不改换工具或环境绕过。

## 结果字段

命令类工具返回同一种 JSON：

| 字段 | 含义 |
|---|---|
| `error_code`、`guidance` | 工具或环境层面的失败及处理建议；命令正常执行时为 null |
| `exit_code` | 命令退出码；未执行或未结束时为 null |
| `timed_out` | 是否因 `timeout_seconds` 到时结束 |
| `stdout`、`stderr` | 输出文本；Linux 模式 stderr 合并在 stdout |
| `truncated`、`truncation` | 是否截断；`head_removed` 表示去掉了开头，保留的是末尾 |
| `stdout_original_length`、`stderr_original_length` | 截断前的长度（UTF-16 单元） |
| `state`、`job_id` | 命令已让出为后台任务时出现 |
| `paused_ms` | 应用被系统暂停的时长，不计入超时 |
| `runtime_changes`、`workspace_changes` | 关键运行数据与工作区的变化检测结果 |

`error_code` 为 null、`timed_out` 为 false 且 `exit_code` 为 0 才是成功。

## 按结果决定下一步

| 现象 | 含义 | 下一步 |
|---|---|---|
| `error_code` 为 null，`exit_code` 非 0 | 命令自身失败 | 读输出，修正命令 |
| 返回 `state: "running"` 与 `job_id` | 命令仍在运行，已让出为后台任务，不是失败 | `job_wait` 等待，或继续其它工作 |
| `timed_out: true`（内置 shell、`python_exec`） | 进程已被终止 | 改用 `background: true` 重新运行，不要原样重试 |
| `TERMUX_TIMEOUT` | 只停止了本地等待，Termux 中的命令可能仍在运行 | 先确认原命令状态，再决定是否重试；长命令改用 `background: true` |
| `truncated: true` | 只保留了输出末尾 | 把输出重定向到工作区文件，用 `read_file` 或 `search_files` 读取；`ssh_exec` 可调大 `max_output_chars` |
| `EXEC_STOPPED`、`TERMUX_STOPPED`、`SSH_STOPPED` | 用户停止了命令 | 不自动重试，说明情况或询问用户 |
| `EXEC_BLOCKED_CATASTROPHIC_COMMAND` | 命令触及系统根、共享存储根、应用数据根或未展开根目录变量的毁灭性操作，两档均不可审批硬拦截 | 严禁重试或换壳绕过；将操作范围精确限定到具体子文件或子目录后重新评估 |
| `EXEC_INTERRUPTED` | 用户插入了新消息，命令未执行或已中断 | 先处理用户的新消息 |
| `EXEC_LINUX_UNAVAILABLE` | Linux 环境不可用 | 请用户到“设置 → Linux 环境”修复，或在聊天顶部切换运行模式 |
| `EXEC_MODE_CHANGED`、`EXEC_RUN_MODE_REQUIRED` | 会话运行模式已变化或尚未确定 | 按 `guidance` 处理后重新调用 |
| `EXEC_WORKSPACE_UNAVAILABLE` | 绑定的工作区不可用 | 请用户检查目录或更换工作区，不回退到私有工作区 |
| `EXEC_PYTHON_UNAVAILABLE` | 设备不提供打包 Python（只随 arm64 发行） | 改用 `termux_exec` 或 `ssh_exec`；`shell_exec` 的系统命令仍可用 |
| `EXEC_INVALID_ARGUMENTS`、`JOB_INVALID_ARGUMENTS`、`TERMUX_INVALID_ARGUMENTS`、`SSH_INVALID_ARGUMENTS` | 参数不符合约束 | 按 `guidance` 修正参数 |
| `TERMUX_INTEGRATION_DISABLED`、`TERMUX_PERMISSION_DENIED` | Termux 集成未启用或未授权 | 请用户到设置的 Termux 配置处理 |
| `SSH_HOST_NOT_FOUND` | 主机别名不存在 | 只使用已保存的别名，需要新主机时用 `ssh_host_save` |
| `SSH_HOST_KEY_UNTRUSTED`、`SSH_HOST_KEY_CHANGED` | 主机尚未信任或指纹变化 | 由用户在 SSH 设置中确认，不能代为信任 |
| `SSH_CONNECTION_FAILED`、`SSH_TIMEOUT` | 连接失败或超时 | 检查网络与主机状态；远端命令可能已经开始执行，重试前先核对 |
| `SECRET_CAPTURE_BACKGROUND_UNSUPPORTED` | 保密捕获只支持前台 | 去掉 `background: true` |
| `JOB_NOT_FOUND` | 任务号不属于本会话 | 用 `job_list` 取本会话任务号 |
| `JOB_NOT_RUNNING`、`JOB_INPUT_UNAVAILABLE`、`JOB_INPUT_UNSUPPORTED` | 任务已结束，或该任务不接受输入 | 用 `job_status` 看状态；SSH 交互改用 `ssh_session_*` |
| `JOB_SESSION_LIMIT`、`JOB_GLOBAL_LIMIT` | 运行中的任务达到上限 | 等待或停止已有任务 |
| `JOB_UNAVAILABLE` | 后台任务服务不可用 | 请用户重新打开应用 |
| 审批被拒 | 用户不同意执行 | 不重试，推进不依赖它的工作 |

## 单条公网命令的内置代理

确认相应变量存在后，用命令前置赋值将代理限制在当前调用。以下命令使用 HTTP 代理。

```sh
test -n "${INFINITY_PROXY_HTTP:-}" &&
  http_proxy="$INFINITY_PROXY_HTTP" https_proxy="$INFINITY_PROXY_HTTP" \
  no_proxy="$INFINITY_PROXY_NO_PROXY" curl -fsS https://example.org/
```

需要 SOCKS5 远端解析时，把两个代理地址都改用 `$INFINITY_PROXY_SOCKS`，绕过列表继续使用 `$INFINITY_PROXY_NO_PROXY`。保持标准代理变量的原有会话配置。执行时关闭 `set -x`，避免用 `env`、`printenv`、`echo` 或详细调试日志输出凭据。
