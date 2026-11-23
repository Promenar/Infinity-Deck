# 运行时架构与可读写隔离契约

本文件定义 Infinity Deck 全站所有执行后端、文件命名空间、临时目录、媒体工具与凭据边界的统一事实源。排查路径不可见、权限拒写、环境变量表现或双运行时差异时按本表核对。

## 一、四层隔离分级

| 层级 | 工具 / 载体 | 进程与身份 | 文件读写边界 | 网络与凭据边界 |
|---|---|---|---|---|
| 1. 强沙盒层 | `exec_js`、`run_script`、`python_sandbox`；会话 HTML 画布 | 前三者在 Android `isolatedProcess="true"` 进程 `:script_runtime` 运行（Linux 会话自动隐藏）；画布在离线安全 WebView 运行 | 零本地文件系统直访；脚本一切工作区读写经主进程能力通道校验与审批；画布仅可只读拉取 `https://workspace-media.infinity.invalid/workspace/...` | 零直连外网、零凭据接触 |
| 2. 受控虚拟命名空间层 | `read_file`、`write_file`、`patch_file`、`diff_file`、`create_file`、`create_directory`、`move_file`、`delete_file`、`list_files`、`search_files`、媒体与画布存取 | 主进程路径解析器强制边界，拒绝 `..` 与越界符号链接 | 仅可见当前会话命名空间（工作区、用户技能、已授权外部挂载、资料库及 Linux 模式下 rootfs）；绝不能访问任意 Android 私有目录 | 不直接发起外网；媒体/画布保存对资料库与 Linux rootfs 一律只读 |
| 3. 暂存隔离 + 零凭据代理层 | ComfyUI 兼容层（`comfy_save_submitter` 干跑、`comfy_run_workflow`） | 会话处于 Linux 模式且环境就绪时走 Alpine `proot`（`LINUX`）；其余走打包 Python 3.14（`BUILTIN_PYTHON`） | 不挂载工作区、`/home/session`、`/mnt/skills` 或资料库；输入素材由宿主显式拷入临时暂存目录，脚本只在单次隔离运行目录执行（Linux 仅绑定 `/mnt/comfy_run` 与 `/mnt/comfy_stage`），产物由宿主验回工作区后销毁运行目录 | 主机密钥留在宿主 `127.0.0.1` 回环代理（`COMFY_HOST_URL`），脚本零接触凭据；默认 `NO_PROXY=127.0.0.1,localhost` |
| 4. 命令执行与外部层 | `shell_exec`、`python_exec`（内置 / Linux）；`termux_exec`、`ssh_exec` | 内置与 Linux 命令均以本应用 Android UID 运行，无内核级路径沙盒；`termux_exec` 为 Termux 身份；`ssh_exec` 为远端主机账号 | 内置命令在工作区物理目录运行，OS 层可触及应用私有目录；Linux 命令经 `proot` 将路径视图收拢到 rootfs 与显式挂载；外部工具与本应用完全独立 | 继承 Tailnet 代理（启用时）；内置 Git HTTPS/SSH 与 Linux `git` HTTPS/`gh` 走宿主凭据代理；保密值走 `request_secret` / `capture_secret` 脱敏 |

## 二、路径与挂载可见性矩阵

| 目标对象 | 内置：文件工具 | 内置：命令（`shell_exec` / `python_exec`） | Linux：文件工具 | Linux：命令（`shell_exec` / `python_exec`） | ComfyUI 提交器 |
|---|---|---|---|---|---|
| 主工作区 | `/` | 起始目录 `$INFINITY_WORKSPACE` | `/workspace`（分支/Linux 目录工作区用绑定路径） | `/workspace`（或绑定路径，见 `$INFINITY_WORKSPACE`） | 不可直访；仅读取宿主显式暂存的输入 |
| 会话私有空间 | 未绑定目录工作区时即 `/` | 同左 | `/home/session`（独立于 rootfs，重置 Linux 不删） | `/home/session` | 不可直访 |
| 临时目录 | — | `$TMPDIR`（`cacheDir/exec-tmp`）；Android 无可写 `/tmp` | `/tmp`（rootfs 内） | `/tmp` 可写；另有 `$TMPDIR`（如设置） | `BUILTIN_PYTHON` 为 `runDir/tmp`（`$TMPDIR`）；`LINUX` 为 `/mnt/comfy_run/tmp`（`$TMPDIR`）与 `/tmp` |
| 真实路径外部挂载 | `/mnt/<别名>`（按授权只读或读写） | 不能直接以 `/mnt/<别名>` 访问；需用文件工具拷入工作区 | `/mnt/<别名>` | `/mnt/<别名>` | 不可直访 |
| SAF 授权目录 | `/mnt/<别名>` | 不可见 | 不可见 | 不可见 | 不可直访 |
| 知识库（资料库） | 经 `request_knowledge_access` 授权后为 `/mnt/knowledge`（`read_only` 或 `read_write`；录音 `.wav` 仅只读） | 不可见 | 经 `request_knowledge_access` 授权后为 `/mnt/knowledge`（`read_only` 或 `read_write`；录音 `.wav` 仅只读） | **不挂载**；需调用 `copy_knowledge_to_linux` 拷入 `/home/session/inbox`（或自定义目录） | 不可直访 |
| 用户级技能 | `/skills/<名称>`（写操作逐次审批） | `$INFINITY_SKILLS`（禁止用命令绕过文件工具审批修改） | `/mnt/skills/<名称>`（写操作逐次审批） | `/mnt/skills`（`$INFINITY_SKILLS`，禁止用命令绕过审批修改） | 仅 `comfy_save_submitter(skill_import)` 由宿主拷入快照，脚本运行期不可见技能源目录 |
| 项目级技能 | `/.agents/skills/<名称>`（私有会话不扫描） | `$INFINITY_WORKSPACE/.agents/skills` | `<工作区>/.agents/skills/<名称>` | `<工作区>/.agents/skills` | 同上 |
| 内置只读技能 | 仅 `activate_skill` / `read_skill_file` 可读；所有文件写入拒绝 | 无对应磁盘目录 | 同左 | 无对应磁盘目录 | 不可直访 |
| Linux rootfs（`/root`、`/etc` 等） | 不可见 | 不可见 | 可读；写入在工作区档逐次审批、完全访问档免批；媒体保存只读 | 完整读写；`/root` 不进入备份，重置 Linux 会清空 | 仅 `LINUX` 运行时可见 rootfs，但不得向 `/root` 存持久化状态 |

## 三、高频误判与环境特性说明

1. **内置模式输出中的 `$INFINITY_WORKSPACE` 是刻意脱敏，不是环境变量未展开**：
   - `BuiltinExecService` 在回传 `stdout`/`stderr` 前会把工作区物理路径替换回字面量 `$INFINITY_WORKSPACE`，既隐藏 Android 私有目录，又便于在下一条命令中直接引用。
   - 因此执行 `pwd` 或 `echo "$INFINITY_WORKSPACE"` 回显 `$INFINITY_WORKSPACE` 表示环境变量正常生效，切勿误报环境异常。
2. **内置模式与 Linux 模式的临时目录差异**：
   - 内置模式运行在 Android 原生环境，系统根目录不存在可写的 `/tmp`；一切临时文件必须写入 `$TMPDIR` 或当前工作区子目录并在结束后清理。
   - Linux 模式运行在 Alpine rootfs 内，`/tmp` 正常可写。
3. **内置 FFmpeg/ffprobe 与 Linux FFmpeg 的能力边界**：
   - **内置模式（`arm64-v8a`）**：应用自带精简 FFmpeg 9.1 / `ffprobe` 9.1（含 8-bit `libx264`，为控包体裁掉了 `lavfi` 等虚拟输入设备）。宿主已将指向原生库的符号链接目录前置进 `PATH`，因此 **`shell_exec`、`python_exec`（及 Python `subprocess`/`shutil.which`）与 `ComfySubmitterRunner(BUILTIN_PYTHON)` 三处完全统一、均可直接调用 `ffmpeg`/`ffprobe`**；同时仍保留 `INFINITY_FFMPEG` / `INFINITY_FFPROBE` 环境变量。
   - **内置模式 `python3` / `git` / `pip`**：因 Android 限制在数据目录直接 `execve` 包装脚本，这三者在 `shell_exec` 中以当前 shell 函数提供，子 shell 与 `subprocess` 不可见；脚本内调打包 Python 用 `sys.executable` 或 `$INFINITY_PYTHON`，调内置 Git/Pip 用 `python3 -m infinity_git` / `python3 -m infinity_pip`。
   - **Linux 模式**：因 Alpine 使用 musl libc、与 Android Bionic 二进制 ABI 不兼容，Linux 会话（含 `ComfySubmitterRunner(LINUX)`）一律使用 Alpine 自身通过 `apk add` 安装的 `python3`、`ffmpeg`、`ffprobe` 等工具，不混用 Android 打包二进制。
4. **资料库与关键运行数据保护**：
   - 资料库文档由 Room 托管，禁止任何无沙盒命令直接修改应用数据库目录；必须通过 `request_knowledge_access` 申请授权后用文件工具读写，或用 `copy_knowledge_to_linux` 导出副本给 Linux 脚本处理。
   - `databases/`、`shared_prefs/`、`no_backup/` 与诊断日志属于关键运行数据，任何修改须先取得用户明确书面同意；命令返回的 `runtime_changes` 为事后元数据审计，不构成事前拦截。
