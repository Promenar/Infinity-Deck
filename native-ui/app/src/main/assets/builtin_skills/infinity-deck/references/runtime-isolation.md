# 运行时架构与可读写隔离契约

本文件定义 Infinity Deck 全站所有执行后端、真实路径体系、临时目录、媒体工具与安全边界的统一事实源。排查路径不可见、权限拒写、命令硬拦截或双运行时差异时按本表核对。

## 一、四层隔离分级

| 层级 | 工具 / 载体 | 进程与身份 | 文件读写边界 | 网络与凭据边界 |
|---|---|---|---|---|
| 1. 强沙盒层 | `exec_js`、`run_script`、`python_sandbox`；会话 HTML 画布 | 前三者在 Android `isolatedProcess="true"` 进程 `:script_runtime` 运行（Linux 会话自动隐藏）；画布在离线安全 WebView 运行 | 零本地文件系统直访；脚本一切工作区读写经主进程能力通道校验与审批；画布仅可只读拉取 `https://workspace-media.infinity.invalid/workspace/...` | 零直连外网、零凭据接触 |
| 2. 统一路径与规则边界层 | `read_file`、`write_file`、`patch_file`、`diff_file`、`create_file`、`create_directory`、`move_file`、`delete_file`、`list_files`、`search_files`、媒体与画布存取 | 主进程路径解析器按运行模式路由，拒绝 `..` 与已废除伪虚拟前缀 | **内置模式**与命令共用真实 Android 路径（工作区内支持无前导 `/` 相对路径，仅 `/mnt/knowledge` 与 SAF `/mnt/<别名>` 保留虚拟前缀）：工作区内读写自由（`delete_file` 按档审批），工作区外合法真实路径写入在按风险审批档需 L1 审批；关键运行数据开放只读自诊断并硬拒写删；加密密钥存储 `infinity_encrypted_secrets.xml*` 读写列举全硬拒。**Linux 模式**统一用 Linux 路径 | 不直接发起外网；媒体/画布保存对资料库与 Linux rootfs 一律只读 |
| 3. 暂存隔离 + 零凭据代理层 | ComfyUI 兼容层（`comfy_save_submitter` 干跑、`comfy_run_workflow`） | 会话处于 Linux 模式且环境就绪时走 Alpine `proot`（`LINUX`）；其余走打包 Python 3.14（`BUILTIN_PYTHON`） | 不挂载工作区、`/home/session`、`/mnt/skills` 或资料库；输入素材由宿主显式拷入临时暂存目录，脚本只在单次隔离运行目录执行（Linux 仅绑定 `/mnt/comfy_run` 与 `/mnt/comfy_stage`），产物由宿主验回工作区后销毁运行目录 | 主机密钥留在宿主 `127.0.0.1` 回环代理（`COMFY_HOST_URL`），脚本零接触凭据；默认 `NO_PROXY=127.0.0.1,localhost` |
| 4. 命令执行与外部层 | `shell_exec`、`python_exec`（内置 / Linux）；`termux_exec`、`ssh_exec` | 内置与 Linux 命令均以本应用 Android UID 运行，无内核级路径沙盒；`termux_exec` 为 Termux 身份；`ssh_exec` 为远端主机账号 | 内置命令在工作区真实目录运行，与文件工具路径 1:1 一致；Linux 命令经 `proot` 将视图收拢到 rootfs 与显式挂载；两档均对毁灭性根目录破坏命令实施不可审批硬拦截（`EXEC_BLOCKED_CATASTROPHIC_COMMAND`） | 继承 Tailnet 代理（启用时）；内置 Git HTTPS/SSH 与 Linux `git` HTTPS/`gh` 走宿主凭据代理；保密值走 `request_secret` / `capture_secret` 脱敏 |

## 二、路径与挂载可见性矩阵

| 目标对象 | 内置：文件工具 | 内置：命令（`shell_exec` / `python_exec`） | Linux：文件工具 | Linux：命令（`shell_exec` / `python_exec`） | ComfyUI 提交器 |
|---|---|---|---|---|---|
| 主工作区 | 真实规范路径（如 `$INFINITY_WORKSPACE`），或无前导 `/` 相对路径（如 `notes/a.md`） | 同左（起始目录 `$INFINITY_WORKSPACE`，输出保留真实路径） | `/workspace`（分支/Linux 目录工作区用绑定路径） | `/workspace`（或绑定路径，见 `$INFINITY_WORKSPACE`） | 不可直访；仅读取宿主显式暂存的输入 |
| 会话私有空间 | 真实规范路径；未绑定目录工作区时即主工作区 | 同左 | `/home/session`（独立于 rootfs，重置 Linux 不删） | `/home/session` | 不可直访 |
| 临时目录 | 可按真实 `$TMPDIR` 读取 | `$TMPDIR`（`cacheDir/exec-tmp`）；Android 无可写 `/tmp` | `/tmp`（rootfs 内） | `/tmp` 可写；另有 `$TMPDIR`（如设置） | `BUILTIN_PYTHON` 为 `runDir/tmp`（`$TMPDIR`）；`LINUX` 为 `/mnt/comfy_run/tmp`（`$TMPDIR`）与 `/tmp` |
| 真实路径外部挂载 | 直接使用设备真实路径（按授权只读或读写；禁止 `/mnt/<别名>`） | 同左（与文件工具路径一致） | `/mnt/<别名>` | `/mnt/<别名>` | 不可直访 |
| SAF 授权目录 | `/mnt/<别名>` | 不可见 | 不可见 | 不可见 | 不可直访 |
| 知识库（资料库） | 经 `request_knowledge_access` 授权后为 `/mnt/knowledge`（`read_only` 或 `read_write`；录音 `.wav` 仅只读） | 不可见 | 经 `request_knowledge_access` 授权后为 `/mnt/knowledge`（`read_only` 或 `read_write`；录音 `.wav` 仅只读） | **不挂载**；需调用 `copy_knowledge_to_linux` 拷入 `/home/session/inbox`（或自定义目录） | 不可直访 |
| 用户级技能 | 真实目录 `files/agent_skills/<名称>`（写删改始终 L2 逐次审批） | `$INFINITY_SKILLS`（与文件工具同路径；禁止用命令绕过文件工具审批修改） | `/mnt/skills/<名称>`（写操作逐次审批） | `/mnt/skills`（`$INFINITY_SKILLS`，禁止用命令绕过审批修改） | 仅 `comfy_save_submitter(skill_import)` 由宿主拷入快照，脚本运行期不可见技能源目录 |
| 项目级技能 | `<工作区真实路径>/.agents/skills/<名称>`（相对 `.agents/skills/<名称>`） | 同左 | `<工作区>/.agents/skills/<名称>` | `<工作区>/.agents/skills` | 同上 |
| 内置只读技能 | 仅 `activate_skill` / `read_skill_file` 可读；所有文件写入拒绝 | 无对应磁盘目录 | 同左 | 无对应磁盘目录 | 不可直访 |
| 关键运行数据（非密钥） | 开放**只读自诊断**（`databases/`、非密钥 `shared_prefs/`、`no_backup/`、诊断日志）；所有写删硬拒 | 读写受系统权限约束，修改前须取得用户明确书面同意；`runtime_changes` 事后审计 | 不可见 | 不可见 | 不可直访 |
| 加密密钥存储 | `shared_prefs/infinity_encrypted_secrets.xml*` 读写列举全硬拒 | 不得读取或导出 | 不可见 | 不可见 | 不可直访 |
| Linux rootfs（`/root`、`/etc` 等） | 不可见 | 不可见 | 可读；写入在按风险审批档逐次审批、免批准档免批；媒体保存只读 | 完整读写；`/root` 不进入备份，重置 Linux 会清空 | 仅 `LINUX` 运行时可见 rootfs，但不得向 `/root` 存持久化状态 |

## 三、关键运行时特性与硬防线

1. **内置模式真实路径唯一写法**：
   - 文件工具与 `shell_exec`/`python_exec` 完全共用同一套真实 Android 规范路径（`pwd`、`echo "$INFINITY_WORKSPACE"` 与 `list_files` 一致，不再做 `$INFINITY_WORKSPACE` 输出脱敏）。
   - 工作区内文件可使用不带前导 `/` 的便携相对路径（如 `media/2026-11-23/a.png`）或完整真实路径；严禁使用已废除的伪前导 `/`（如 `/notes/a.md`、`/skills/...`、`/session/...`、`/mnt/<真实挂载别名>/...`）。
2. **不可审批的毁灭性根目录破坏硬拦截（`EXEC_BLOCKED_CATASTROPHIC_COMMAND`）**：
   - 无论处于“按风险审批”还是“免批准”档，针对系统根目录（`/`）、Android 共享存储根目录（`/sdcard`、`/storage/emulated/0`、`/storage`）、应用数据根目录及核心子目录、Linux 系统根目录、`~` 或未展开根目录变量（如 `$EMPTY/`）的毁灭性命令（`rm`/`rmdir`/`shred`/`find -delete`/`dd`/`mkfs`/递归 `chmod`/`chown`/`mv`/`sh -c`/`eval`/`python3 -c` 等）均在策略层与执行层双重硬拦截，绝不弹出审批卡。
3. **内置模式与 Linux 模式的临时目录差异**：
   - 内置模式运行在 Android 原生环境，系统根目录不存在可写的 `/tmp`；一切临时文件必须写入 `$TMPDIR` 或当前工作区子目录并在结束后清理。
   - Linux 模式运行在 Alpine rootfs 内，`/tmp` 正常可写。
4. **内置 FFmpeg/ffprobe 与 Linux FFmpeg 的能力边界**：
   - **内置模式（`arm64-v8a`）**：应用自带精简 FFmpeg 9.1 / `ffprobe` 9.1（含 8-bit `libx264`，为控包体裁掉了 `lavfi` 等虚拟输入设备）。宿主已将符号链接目录前置进 `PATH`，因此 **`shell_exec`、`python_exec`（及 Python `subprocess`/`shutil.which`）与 `ComfySubmitterRunner(BUILTIN_PYTHON)` 三处均可直接调用 `ffmpeg`/`ffprobe`**；同时保留 `INFINITY_FFMPEG` / `INFINITY_FFPROBE` 环境变量。
   - `shell_exec`、`python_exec` 也将随 APK 提供的 `rg`、`fd`、`jq`、`sqlite3`、`zstd`、`bsdtar`、`rsync` 加入内置模式的 `PATH`。以当前环境的工具探测结果为准。
   - **内置模式 `python3` / `git` / `pip`**：这三者在 `shell_exec` 中以当前 shell 函数提供，子 shell 与 `subprocess` 不可见；脚本内调打包 Python 用 `sys.executable` 或 `$INFINITY_PYTHON`，调内置 Git/Pip 用 `python3 -m infinity_git` / `python3 -m infinity_pip`。
   - **Linux 模式**：因 Alpine 使用 musl libc、与 Android Bionic ABI 不兼容，Linux 会话（含 `ComfySubmitterRunner(LINUX)`）一律使用 Alpine 自身 `apk add` 安装的工具，不混用 Android 打包二进制。
5. **资料库与关键运行数据保护**：
   - 资料库文档由 Room 托管，必须通过 `request_knowledge_access` 申请授权后用文件工具读写，或用 `copy_knowledge_to_linux` 导出副本给 Linux 脚本处理。
   - 关键运行数据目录通过文件工具仅开放只读自诊断（`infinity_encrypted_secrets.xml*` 全硬拒）；任何命令修改均须先取得用户明确书面同意。

## Linux 命令按需使用内置代理

内置代理运行时，新启动的 Alpine Linux 进程可读取 `$INFINITY_PROXY_HTTP`、`$INFINITY_PROXY_SOCKS` 和 `$INFINITY_PROXY_NO_PROXY`。它们供单条公网命令主动使用。HTTP 版本适用于标准 HTTP 代理，SOCKS 版本使用 `socks5h` 由远端解析域名，绕过列表保留回环、私网与 Tailnet。用 `test -n "${INFINITY_PROXY_HTTP:-}"` 检查是否可用。两个代理地址含本机临时凭据，禁止打印、复制到日志、保存到配置或传给模型。
