# Infinity Workbench 命令执行后端规格

- 状态：待评审
- 范围：`native-ui`（Android，targetSdk 36，minSdk 31）
- 方向：内置固定工具集（B）作为默认后端，Termux 与 SSH（D）作为完整 Linux 后端并存
- 依据：执行后端探针应用（`com.promenar.nexara.execprobe`），在 API 31、API 35 模拟器与 OPPO PKH110（Android 16，ColorOS V16.1.0）上实测；结论与关键数据见本文第 2 节事实表及 `.agent/handover.md` 2026-10-18 的两条执行后端真机验证记录

## 1. 目标与非目标

目标：

1. 不安装 Termux 也能在会话工作区里运行 shell 命令和 Python 脚本，覆盖数据处理、文件批量处理、git 读写等常见代理任务。
2. 内置后端、Termux、SSH 共用一套工具结果格式、虚拟路径约定和失败语义，模型与界面不需要按后端区分处理。
3. 命令改动工作区后，Room 索引、版本哈希和界面在命令结束时与磁盘一致。
4. 工具失败时，模型和界面都能看到错误码、指引、退出码和 stderr，不因截断丢失根因。

非目标：

- 不提供完整 Linux 发行版、包管理器或编译工具链；需要这些的任务交给 Termux 或 SSH。
- 不从网络下载可执行二进制或原生扩展（W^X 与 Play 政策都不允许）。
- 不解决厂商后台冻结；只做检测与引导。
- 不改变现有工作区文件工具（读写、补丁、回收区、按路径审批、SAF 挂载）的接口与语义，它们仍是文件读写主接口。

## 2. 已验证的平台事实

| 事实 | 结论 |
| --- | --- |
| target ≥ 29 时 execve 应用数据目录中的文件 | 被拒（`execute_no_trans` on `app_data_file`） |
| 执行 `nativeLibraryDir` 中的打包二进制 | 可以；需 `packaging.jniLibs.useLegacyPackaging = true`，否则库不解压、无文件路径可执行 |
| 经 `linker64` 运行或 `System.load` 数据目录文件 | 当前可以，但触发 `avc: granted { execute }` 审计记录，属于可能被收紧的行为，不作为设计依赖 |
| isolatedProcess | 可执行打包二进制；看不到应用数据目录（ENOENT）；无网络（socket EACCES）；无可写目录 |
| 系统 `/system/bin/sh` 与 toybox | 可用；toybox 版本随设备不同（实测 0.8.12） |
| 官方 CPython 3.14.8 Android 包 | 只支持嵌入。用 `Py_BytesMain` 启动器 `libnxpython.so`（`-Wl,-rpath,'$ORIGIN'`）可在主进程与隔离进程运行 |
| Python 打包库方案 | stdlib 存为不压缩 zip `libpystdlib.so`，扩展模块改名 `libpyext_<模块>.so` 由 meta_path finder 加载；不需要解压、不从数据目录 dlopen |
| 启动耗时 | 打包库方案冷启动：主进程 0.43 秒，隔离进程 0.63 秒 |
| APK 增量 | +12.5 MB（arm64-v8a，已裁剪 `test/`、`idlelib/`、`tkinter/`、`turtledemo/`、`pydoc_data/`）；解压安装后库占用约 32.5 MB |
| pip | 纯 Python 包可装；numpy、pandas、pillow、cryptography、pydantic-core、lxml、pyyaml 等常用原生包在 PyPI 上无 android-arm64 wheel |
| TLS | Python ssl 读不了 Android CA 目录（旧式哈希文件名）；需把 `/apex/com.android.conscrypt/cacerts` 拼成 bundle 并设 `SSL_CERT_FILE` |
| git | 无官方 Android git；dulwich（纯 Python）可 HTTPS 克隆、add、commit、status |
| 后台（默认电池策略） | ColorOS Hans 在熄屏后冻结整个应用 uid，前台服务（`isForeground=true`、procState 4）同样被冻结；AOSP `isFrozen` 不反映该冻结 |
| 亮屏无前台界面 | 内置执行设备测试（instrumentation，无 Activity）在亮屏时启动约 3 秒后被 Hans 冻结，连同刚 fork 尚未 exec 的子进程；冻结期间测试与子进程都不推进，收到 SIGQUIT 后解冻并继续，SIGCONT 不解冻；内置后端改以轮询间隔检测暂停并把暂停时间排除在超时之外（第 4.5 节） |
| 亮屏后台（命令前台服务） | 正式包在真机上运行 `sleep 90` 时回到桌面，`specialUse` 前台服务在前台、通知计时持续，约 50 秒内 Hans 无冻结记录；通知“停止”后模型收到 `EXEC_STOPPED` 并按指引不重试 |
| 后台（“完全允许后台行为”，未充电锁屏 15 分钟） | 不再被冻结，前台服务内的子进程运行到结束；但 CPU 会深度休眠，每 5 秒一次的心跳只写了 49/约 175 行，最长间隔 66 秒。持续计算需要 `PARTIAL_WAKE_LOCK` |
| 对账耗时 | 2 万个 4 KB 文件：元数据遍历 171 ms，size+mtime 差异检测并重算差异文件哈希 266 ms；全量 SHA-256 483 ms |
| Termux SAF 挂载 | 读写挂载 Termux 主目录后双向可用：Termux 新建的文件立即在 Infinity Workbench `/mnt/<别名>` 可见；Infinity Workbench 经 `create_file` 写入的文件在 Termux 主目录可见，属主为 Termux uid |
| 共享存储 | Termux（`termux-setup-storage` 后）、adb 与 Infinity Workbench（“挂载存储路径”，需所有文件访问）在同一 `Download` 子目录中互相读写可见，内容一致 |

## 3. 总体结构

```
ToolExecutor
  └─ 命令类工具（shell_exec、python_exec、termux_exec、ssh_exec）
        └─ 共用结果契约 CommandToolResult（第 3.1 节）
              ├─ BuiltinExecService   主进程子进程（独立进程组），打包 Python，工作区物理目录
              ├─ python_sandbox       isolatedProcess（与 exec_js 同一隔离服务），打包 Python，经主进程能力代理访问工作区
              ├─ TermuxExecSkill      现有 TermuxRuntime（RUN_COMMAND）
              └─ SshSkills            现有 SshRuntime
```

状态：内置后端已实施（`data/exec/BuiltinExecService.kt`、`BuiltinCommandRunner.kt`、`BuiltinExecEnvironment.kt`，工具 `ui/chat/manager/skills/BuiltinExecSkills.kt`）。三个后端的请求参数与生命周期差异较大（Termux 经 Intent 回调、SSH 经会话池、内置经子进程与工作区锁），共享的是结果格式而非调用接口，因此不另设 `ExecBackend` 接口。沙箱模式复用脚本运行时的请求模型（`ScriptRequest`），不与内置后端合并，见第 4.3 节。

### 3.1 统一结果格式

状态：已实施（`domain/tool/CommandToolResult.kt`；`termux_exec`、`ssh_exec` 已接入）。

所有命令类后端返回同一 JSON 对象，键顺序固定，根因字段在前，后端专有字段排在共有字段之后：

| 字段 | 说明 |
| --- | --- |
| `error_code` | 后端或协议层错误码，命令本身运行成功（无论退出码）时为 null |
| `guidance` | 面向模型的下一步指引，可为 null |
| `exit_code` | 进程退出码；未启动或超时为 null |
| `timed_out` | 是否超时 |
| `stderr` / `stdout` | 输出；超限时的保留方向见 `truncation` |
| `stdout_original_length` / `stderr_original_length` | 原始长度，`length_unit` 为 `UTF-16`；后端无法得知时为 null |
| `stdout_truncated` / `stderr_truncated` | 各输出流是否截断 |
| `truncated` / `truncation` | 是否截断及去掉的部分（`head_removed`、`tail_removed`、`none`） |
| `workspace_changes` | 仅内置后端：命令结束对账得到的 `added`、`modified`、`removed`（整目录删除只列最上层）与 `moved`（`{from,to}`，整目录移动只列最上层）虚拟路径，`counts` 给各类总数，四类合计超过 200 项时按此顺序截断并置 `truncated`；对账失败时为 null |
| `workspace_warnings` | 仅内置后端，可省略：`[{code, count, paths}]`，`code` 为 `WORKSPACE_RESERVED_PATH`（改动 `.infinity*`、`.recycle_bin` 或新建顶层 `mnt`）或 `WORKSPACE_UNSUPPORTED_ENTRY`（新建或改动的符号链接、特殊文件、文件工具不接受的名称），`paths` 最多 20 项 |
| `paused_ms` | 仅内置后端，可省略：等待期间 Infinity Workbench 被系统暂停（后台冻结或深度休眠）的累计毫秒数，达到 3 秒才给出；暂停时间不计入超时 |

`status` 规则统一为：`error_code == null && !timed_out && exit_code == 0` 为 `success`，否则 `error`。因此 `ssh_exec` 退出码非 0（如 `grep` 无匹配、`diff` 有差异）也记为失败步骤，模型收到第 6 节的失败摘要（不超过 4,000 字符，保留根因字段与输出尾部），与 `termux_exec` 一致。

各后端的差异：

- 内置后端（`shell_exec`、`python_exec`）：stdout、stderr 各保留尾部 32,000 UTF-16 字符（`head_removed`），原始长度按完整解码计数。命令本身运行（无论退出码）时 `error_code` 为 null；后端错误码为 `EXEC_INVALID_ARGUMENTS`、`EXEC_PYTHON_UNAVAILABLE`、`EXEC_WORKSPACE_UNAVAILABLE`、`EXEC_START_FAILED`、`EXEC_TIMEOUT`（`exit_code` 为 null）、`EXEC_STOPPED`（用户在通知栏或聊天界面停止，`exit_code` 为 null，指引要求不自动重试）、`EXEC_PROCESS_KILLED`（退出码 137 且不是本端发出的 SIGKILL）与 `WORKSPACE_RECONCILE_FAILED`（命令已执行但索引未更新，`exit_code` 照常给出）。只有警告时 `guidance` 说明警告含义，状态不受影响；出现 `paused_ms` 时 `guidance` 追加冻结原因与“完全允许后台行为”设置指引。
- `termux_exec`：输出由 Termux 截断并保留尾部（`head_removed`），专有字段 `err`、`errmsg`、`errmsg_original_length`、`errmsg_truncation_possible` 排在共有字段之后。
- `ssh_exec`：凭据与私钥块遮蔽需要完整前文，输出在遮蔽之后截断并保留开头（`tail_removed`）。stdout 与 stderr 合计超过 `max_output_chars` 时按 `splitOutputBudget` 分配：一方不足一半时余量让给另一方，双方都超过一半时各取一半。字节采集上限（`max_output_chars × 4` 字节）被触发时原始长度未知，对应字段为 null。超时返回 `error_code` = `SSH_TIMEOUT`、`exit_code` = null。
- SSH 其余失败（参数、主机、凭据、指纹、连接、会话上限、SFTP 冲突等）返回 `{"error_code":"SSH_<码>","guidance":"..."}`，指引按错误码给出，不含主机地址、用户名或凭据。

### 3.2 统一路径与环境

- 命令的工作目录是会话当前工作区的物理根目录，文件工具与 `workspace_changes` 仍以 `/` 表示工作区根。会话绑定自选工作区（项目文件夹或侧载共享文件夹）时，命令直接在该目录运行：不持工作区锁、不做 Room 对账，结果不含 `workspace_changes`，改以 `workspace_kind` 与 `workspace_tracking: "none"` 标明，改动用 `git status` 查看；目录不可用时返回 `EXEC_WORKSPACE_UNAVAILABLE`，不回退到私有工作区。内置后端把 stdout、stderr 中的工作区物理路径替换为 `$INFINITY_WORKSPACE` 而不是 `/`：替换后既不暴露应用目录，又能原样用于下一条命令；以 `/` 开头的虚拟路径在命令里会指向设备根目录。
- `/mnt/<别名>` 是 SAF 挂载，只对文件工具可见，命令不可见。内置后端在 `guidance` 中说明需要先用文件工具复制到工作区。
- `/skills` 是固定内部挂载，文件工具与脚本网关在所有工作区模式下均映射到用户级技能真实目录。内置 shell、Python 与 git 使用 `INFINITY_SKILLS` 环境变量访问该目录；HOME 下维护 `.agents/skills` 符号链接，已有正确链接时保持不变，已有其它文件、目录或链接时保留并记录日志。项目级技能位于工作区内 `.agents/skills/`，使用相对路径。文件工具中的保留虚拟根会遮蔽工作区同名 `skills/`；命令在物理工作区创建的同名条目不进入私有工作区虚拟索引。
- 文件工具与脚本网关对 `/skills`、工作区 `/.agents/skills` 的变更，以及可能连带移除项目技能的父目录操作，在全部执行模式下逐次审批；UUID 按解析后的路径判断，审批目标不可记忆，旧路径白名单不适用。真实进程的技能目录写入沿用命令执行审批，不按文件路径拦截。
- 环境变量统一：`HOME`（后端各自的家目录）、`TMPDIR`、`INFINITY_WORKSPACE`（工作区物理根）、`INFINITY_BACKEND`（`builtin` / `sandbox` / `termux` / `ssh`）、`INFINITY_WORKSPACE_KIND`（`private` / `project` / `shared`，内置 git 据此给出私有工作区 clone 提示与共享存储兼容配置）、`LANG=C.UTF-8`。内置后端额外设置 `PYTHONHOME`、`PYTHONPATH`、`INFINITY_PYEXT_DIR`、`INFINITY_PYTHON`、`SSL_CERT_FILE`、`PYTHONNOUSERSITE=1`、`PYTHONDONTWRITEBYTECODE=1`、`PYTHONUTF8=1`，并从主进程只继承 `PATH` 与 `ANDROID_ROOT` 等系统路径变量。`LD_LIBRARY_PATH` 只在启动解释器时设置（`python_exec` 与 shell 函数 `python3`），不影响 shell 中的系统命令。

## 4. 内置后端（B）

### 4.1 打包内容

状态：已实施（构建见 `docs/distribution-builds.md#打包-python`，运行时见 `data/exec/PackagedPython.kt`、`BuiltinExecEnvironment.kt`，git 与 pip 包装见 `app/python/site/`）。

| 组件 | 形式 | 说明 |
| --- | --- | --- |
| shell | 系统 `/system/bin/sh` | 不打包 busybox/toybox；命令文案声明“POSIX sh + 系统 toybox，具体命令随设备不同” |
| Python 3.14 | `libnxpython.so`、`libpython3.14.so`、`lib*_python.so`、`libpystdlib.so`、`libpyext_*.so` | 打包库方案；stdlib 裁剪同第 2 节 |
| `python3` / `python`、`git`、`pip`/`pip3` 入口 | `shell_exec` 预置的 `sh` 函数 | 数据目录里的包装脚本无法经 PATH 执行（execve 被拒），因此以函数提供；函数只在该次 `sh -c` 内有效，子 `sh` 脚本、`xargs`、`find -exec` 与 Python 的 `subprocess` 找不到，可改用 `python3 -m infinity_git` / `python3 -m infinity_pip` 或以 `.` 在当前 shell 执行脚本 |
| 纯 Python 依赖 | `libpysite.so`：dulwich 1.2.17、urllib3 2.8.0 的 PyPI wheel（版本、地址与 SHA-256 见 `app/python/python-site.properties`）与 `app/python/site/` 中的 Infinity Workbench 模块合成的不压缩 zip | 排在标准库之后进入 `PYTHONPATH`；`python_exec` 中可直接 `import dulwich.porcelain`。用户用 pip 装的同名包排在其后，不会覆盖打包版本 |
| git | `infinity_git`：把子命令转交 `dulwich.cli` | 开放 add、blame、branch、cat-file、checkout、cherry-pick、clean、clone、commit、config、describe、diff、fetch、for-each-ref、grep、init、log、ls-files、ls-remote、ls-tree、merge、merge-base、mv、pull、push、rebase、reflog、remote、reset、restore、rev-list、rev-parse、revert、rm、shortlog、show、show-ref、stash、status、switch、tag；全局选项只支持 `-C <目录>`。其它子命令与全局选项、SSH 远端（`ssh://`、`user@host:path`）返回 `GIT_UNSUPPORTED` 与改用 Termux/SSH 的指引，退出码 2；其它失败输出单行 `fatal:`，退出码 128。补齐 dulwich 缺少的写法：`add -A`、`commit -F <文件>`、`log -<N>`（dulwich 会把 `-5` 当成路径而静默输出空结果）、`branch -a`/`-r`、`remote [-v]`/`get-url`/`set-url`/`remove`，本地克隆源记为绝对路径。stderr 不接终端时只保留以回车刷新的进度行的最终状态（实测一次克隆的进度由约 514 KB 降到约 300 字节）。`clone`、`fetch` 默认只请求 `HEAD`、`refs/heads/`、`refs/tags/`：dulwich 默认要远端通告的全部引用，GitHub 会通告每个 PR 的 `refs/pull/*`，真机上 `--depth 1` 克隆 octocat/Spoon-Knife 因此在 120 秒超时前仍在下载（宿主实测约 636 MiB），限制后约 60 KB、2 秒。没有编辑器，`commit` 缺少 `-m`/`-F` 时直接报错；未配置 `user.name`/`user.email`（仓库、`$HOME/.gitconfig` 或 `GIT_AUTHOR_*`/`GIT_COMMITTER_*`）时 commit、merge、cherry-pick、revert、rebase、stash 返回 `GIT_IDENTITY_MISSING`，不使用 `u0_aNNN@localhost` 兜底 |
| pip（仅侧载） | `infinity_pip`：运行标准库 `ensurepip/_bundled` 中的 pip wheel（首次使用时复制到 `noBackupFilesDir/python-pip`，zip 中的 zip 无法直接导入） | 包安装到用户站点 `filesDir/python-user`（`PYTHONUSERBASE`，侧载渠道不设 `PYTHONNOUSERSITE`），缓存在 `cacheDir/pip`，关闭版本检查与交互输入。安装后若新出现原生扩展文件，在 stderr 提醒其无法导入。Play 渠道不设 `INFINITY_PIP`，`pip` 返回 `PIP_UNSUPPORTED`，退出码 2 |
| CA bundle | 首次运行由 `/apex/com.android.conscrypt/cacerts`（不存在时为 `/system/etc/security/cacerts`）中的 PEM 块拼出，存于 `noBackupFilesDir/exec-ca` | 证书目录的文件名、大小或修改时间变化时重建 |

ABI：官方包只有 arm64-v8a 与 x86_64。设了 `abiFilters` 的变体按过滤打包；发行产物是不按 ABI 拆分的通用 APK，只带 arm64-v8a Python（x86_64 约 11.5 MB 只服务 x86_64 设备）。armeabi-v7a、x86 与 x86_64 设备上的 Release 中 `PackagedPython.isAvailable` 为 false，内置后端须给出不可用指引。

清单变更：`packaging.jniLibs.useLegacyPackaging = true`，全部原生库改为压缩存放并在安装时解压到 `nativeLibraryDir`。实测：单 ABI 打包 Python 后 deviceTest APK 增加 11.3 MB，安装后 `nativeLibraryDir` 为 33 MB；Release 通用 APK 由约 20.8 MB 增至约 29.8 MB；内置 git 依赖 `libpysite.so`（不压缩 3.5 MB）再增约 0.8 MB，至约 30.6 MB。需在发布说明中披露。

### 4.2 进程与安全

- 命令是主进程的子进程（非 isolated），以 Infinity Workbench uid 运行。没有单独的 `:exec` 进程：工具结果、审批账本与工作区对账都在主进程，主进程死亡时本次工具调用随之失效，`:exec` 进程不能让命令结果送达；ColorOS 冻结的是整个 uid，单独进程也不能避开。子进程位于应用的进程 cgroup 内，应用进程被结束时随之结束。
- 启动方式：`/system/bin/setsid /system/bin/sh -c '<写出 $$ 到临时文件>; exec "$@"' …`，命令成为新会话首进程，进程组号即其 pid（API 31 toybox 0.8.4、API 35 toybox 0.8.11 实测）。信号经 `Os.kill(-pgid, …)` 发给整个进程组；命令自行 `setsid` 脱离的进程不在其中。
- 命令可读写应用私有目录，包括 Room 数据库与配置；密钥由 Android Keystore 保护，命令无法直接取得明文，但不能视为强隔离。
- 因此 `shell_exec` 与 `python_exec` 的风险等级为 `ToolRisk.PROCESS`，沿用现有审批（半自动与手动模式逐次审批，全自动模式不审批，与 `termux_exec` 相同）；审批卡片展示完整命令或代码（收起时显示前 6 行，“展开全部”后在限高 360dp 的框内滚动查看全文）及“可访问 Infinity Workbench 私有数据与网络、删除或覆盖的文件不进回收站”的提示。审批目标为 `["process","builtin",<命令>]` 与 `["process","python",<代码>]`；工作目录固定为本会话工作区根，不作为参数，因此不进入审批目标。非内置来源的同名工具不使用这两个目标。
- 环境变量白名单启动，不继承主进程环境。
- 参数上限：`command` 32,000 字符；`code` 40,000 字符且 UTF-8 不超过 120,000 字节（经 `python -c` 传入，单个 execve 参数上限 128 KiB），更长的代码先写入工作区文件再运行。
- 网络：默认允许（pip、git 需要）。是否增加“仅局域网白名单”开关与脚本运行时保持一致，不单独设计。
- 资源：单条命令默认 60 秒、最长 600 秒；stdout、stderr 各保留尾部 32,000 UTF-16 字符；超时或取消先 SIGTERM，2 秒后 SIGKILL 整个进程组。命令正常结束后，同组仍在运行的后台进程立即被 SIGKILL，避免在释放工作区写锁后继续改动文件；需要常驻的服务改用 Termux。

### 4.3 沙箱后端

状态：已实施（`data/script/PythonSandboxEngine.kt`、`app/python/site/infinity_sandbox.py`，工具 `ui/chat/manager/skills/PythonSandboxSkill.kt`）。

独立工具 `python_sandbox(code, stdin, allow_network, timeout_seconds)`，风险等级 `SCRIPT`（与 `run_script` 相同；工具风险是静态属性，因此不做成 `python_exec` 的参数）。审批目标为 `["tool","python_sandbox"]`。

- 进程：复用 `exec_js` 的隔离服务 `ScriptRuntimeService`（`:script_runtime`，`isolatedProcess`），`ScriptRequest.language = "python"` 时由 `PythonSandboxEngine` 启动打包 Python 子进程（`-c` 只导入 `infinity_sandbox`，用户代码经控制通道传入，不受 execve 参数上限约束）。子进程继承隔离 UID：读不到应用数据（含 Room 数据库），无可写目录，套接字被拒绝；`PYTHONNOUSERSITE` 开启，侧载 pip 安装的包不可见。
- 控制通道：子进程原始 stdin/stdout，每行一个 JSON 帧（start、out、cap、cap_result、done），单帧上限 260 KiB。`infinity_sandbox` 在执行用户代码前把 fd 0/1/2 换成管道并转发为 out 帧，C 扩展与 `os.write` 的输出不会混入控制帧。
- 能力：`import infinity` 的 read、read_text、stat、list、write、create、save、mkdir、delete、fetch 经隔离服务的 Binder 会话交给主进程 `ScriptWorkspaceGateway` 与 `ScriptHttpClient`，与 JavaScript 共用文件审批（含“脚本写入审批”设置）、挂载身份校验、64 KiB 文本文件上限、联网开关与局域网白名单、128 次能力调用上限；`module` 能力对 Python 不开放。以 `_LIMIT` 结尾的失败终止整次执行，其它失败以 `infinity.InfinityError(code)` 交给用户代码。
- 结果：沿用第 3.1 节命令结果格式，`backend` 为 `sandbox`；stdout 保留尾部 32 KiB、stderr 尾部 12 KiB（按 UTF-8 字节，整体结果不超过脚本服务的 64 KiB 上限），`result` 变量 JSON 化后不超过 16 KiB，超出时以 `result_error` 说明。
- 超时与取消：引擎按 `timeout_seconds` 自行终止子进程并带回已有输出（`error_code = TIMEOUT`），主进程另加 3 秒余量兜底；服务终止前先 SIGKILL 子进程。设备测试确认超时后没有残留的 Python 进程。

### 4.4 工作区一致性

状态：已实施（`data/repository/WorkspaceCommandReconciliation.kt`，`WorkspaceRepository.runCommandInWorkspace`）。

1. 命令运行期间持有该工作区的写锁（`WorkspaceMutationCoordinator`，与文件工具同一把锁）。文件工具的写入与内容读取（`read_file`、`diff_file` 等也在锁内）都排队等待，最长约为命令超时加对账耗时；列表、搜索等只查 Room 的读取不受影响。
2. 启动前经 `SecureDirectoryStream` 逐级遍历物理树（不跟随符号链接），记录每个条目的类型、size、mtime（纳秒）与 inode（`fileKey`）。Room 不保存 mtime，因此前快照只能来自现场遍历。
3. 命令结束（含失败、超时、取消）后在不可取消上下文中再遍历一次，以 Room 索引为准计算差异：size、mtime、inode 均未变且 size 与索引一致的文件不重算哈希；其余文件重算 SHA-256，内容不同则更新哈希、大小与修改时间并清空向量化状态。前快照中某条已删除条目的 inode 出现在新路径、类型相同且（文件）size 与 mtime 未变时视为移动，保留条目 uuid、版本历史与派生数据；同 inode 但内容已变按删除加新增处理。新增文件的 MIME 按扩展名判定，未知扩展名且不超过 256 KiB 的文件按 UTF-8 文本嗅探。删除经与永久删除相同的派生数据事务与向量化屏障提交，并清理对应 `.infinity_versions` 快照；新增与更新在一个 Room 事务中提交。随后为索引管线支持的 MIME 发布 `FileIndexEvent.Changed`，再释放写锁。
4. 命令直接删除或覆盖的文件不进入回收区，也不生成版本快照（覆盖前的内容已不可得），审批卡片写明这一点。命令改动 `.infinity*`、`.recycle_bin`（任意层级的保留名称）或新建顶层 `mnt`（挂载命名空间，同名目录会被挂载遮蔽）时，对账不写入索引并给出 `WORKSPACE_RESERVED_PATH` 警告；回收区或版本历史可能因此损坏，界面不自动修复。
5. 根身份标记 `.infinity_root_identity` 被命令删除或改写会让后续全部工作区操作失败：命令前读取原始字节，结束后在根目录 fileKey 未变时写回，并列入保留路径警告；标记被替换为目录时对账失败。
6. 命令运行期间工作区根存在 `.infinity_command_pending` 标记，对账成功后删除。进程在命令中途被杀时标记残留，下一次经工作区仓储的修改（新建、移动、回收、删除或再次运行命令）前先以无前快照方式对账（全部文件重算哈希、不识别移动）；恢复对账失败只记日志，不阻断后续修改。`write_file`、`patch_file` 不经工作区仓储，在恢复对账之前对已被改动的文件会因哈希与索引不一致而拒绝写入。
7. 共享存储是 FUSE，不支持符号链接与可执行位，不适合放 git 仓库或虚拟环境；内置后端只把会话工作区作为工作目录。
8. `.git` 目录之下的条目照常入索引（工作区与磁盘一致，移动、删除仓库时一并处理），但不发布向量化事件：仓库元数据对检索无用，`.git/config` 的远端地址可能带令牌，不应发给嵌入服务。命令结果的 `workspace_changes` 不逐条列出这些路径，只给 `git_internal_changes` 计数；`.git` 目录本身照常列出。

### 4.5 后台与生命周期

状态：已实施（`background/exec/AndroidExecKeepAlive.kt`、`background/exec/ExecForegroundService.kt`、`data/exec/ExecKeepAlive.kt`）。

- 每条命令从等待工作区写锁到对账结束都持有 `PARTIAL_WAKE_LOCK`（标签 `Infinity Workbench:builtin-exec`，超时上限加 5 分钟兜底，结束、超时、停止或取消时在 finally 中释放）。
- 侧载渠道同时启动 `ExecForegroundService`（`specialUse`，子类型说明写在清单 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）：通知显示“Shell/Python · 命令首行”与计时，锁屏只显示“Infinity Workbench 正在运行命令”；按钮“停止”（多条命令时“全部停止”）只结束通知渲染时列出的命令。登记表清空即退出前台。服务不持有命令，主进程被结束时命令随之结束。
- 生成任务自身的前台服务在等待审批时退出；半自动与手动模式下批准后的命令在界面协程中执行，正是这里的命令前台服务提供保活与停止入口。全自动模式下两者可能同时显示，各自的停止分别作用于生成与命令。
- Play 渠道不声明命令前台服务与 `FOREGROUND_SERVICE_SPECIAL_USE`（政策待评审），只持有唤醒锁；`scripts/ci/check-distribution-manifests.py` 校验该渠道差异。
- 系统拒绝启动前台服务（应用在后台且无豁免）时命令照常运行，只有唤醒锁。
- 停止：通知按钮或聊天界面实时输出区的“停止”经 `BuiltinCommandControl` 请求停止，等待循环（250 ms 步长）发现后 SIGTERM→2 秒→SIGKILL 整个进程组，返回 `EXEC_STOPPED` 并照常对账；模型据此继续本轮，而不是整轮生成被取消。
- 实时输出：读取线程每读到一块输出即经 `SkillExecutionContext.liveOutput` 推送到内存中的 `ToolLiveOutputStore`（按工具调用步骤 id 登记，命令结束即移除，每条命令保留尾部 16 KB，不写数据库），推送前同样把工作区物理路径替换为 `$INFINITY_WORKSPACE`（块尾若是被切开的路径前缀则暂存到下一块，命令结束时补发）；只有执行方实际使用过的通道才在界面显示，其它工具不出现空终端区。启动前已请求停止的命令不再启动。聊天界面在运行中的工具行下方显示最后 12 行，每 100 ms 刷新一次。交给模型的结果与上述截断规则不变。`ssh_exec` 把通道输出同时写入采集缓冲与按连接凭据遮蔽的流式过滤器，聊天界面停止时断开通道并返回 `SSH_STOPPED`；沙箱 Python 与 `exec_js` 的输出由隔离进程每 100 ms 合并为一条 `OUTPUT` 消息（每个窗口只保留尾部 16 K 字符，相邻同一路输出合并、片段数不超过 256）回传，宿主每次运行最多处理 4000 条，超出只丢弃实时展示，停止时取消执行并终止隔离进程，返回 `STOPPED`；`termux_exec` 结果一次性回传，界面注明不提供实时输出。
- 冻结检测：等待循环的时钟为 `SystemClock.elapsedRealtimeNanos`（含深度休眠）。相邻两次轮询间隔超出步长 2 秒以上即视为本进程被暂停，超出部分计入 `paused_ms` 而不消耗超时；累计达到 3 秒时 `guidance` 说明暂停时长、前台服务状态，并提示在应用耗电管理中设为“完全允许后台行为”。
- 进程被系统结束时返回 `error_code = EXEC_PROCESS_KILLED`，对账照常执行。

### 4.6 分发渠道

| 能力 | 侧载 | Play |
| --- | --- | --- |
| shell_exec、python_exec、python_sandbox | 有 | 有（全部代码随 APK 分发） |
| pip 安装 | 有，仅纯 Python 包，安装到 `filesDir/python-user` | 不提供（`PIP_UNSUPPORTED`），待政策评审 |
| git（dulwich） | 有 | 有（代码随 APK 分发） |
| 前台服务类型 | `specialUse`（已声明） | 不声明，只持有唤醒锁；待评审 |

### 4.7 内置 Linux 后端（侧载 arm64）

- 侧载渠道在 arm64-v8a 设备上另带 proot + Alpine 3.24.3 minirootfs（设计与决策见 `.agent/plans/2026-10-24-linux-runtime.md`）。用户在设置中安装并开启“Agent 使用 Linux”后，`LinuxExecRouting` 判定环境 Ready 时 `shell_exec` 改在 Alpine 中执行；环境未安装、安装中、损坏或关闭时仍走第 4 节的内置 shell。`python_exec`、`python_sandbox` 与 git（dulwich）不受影响。
- 路径映射：会话工作区为 `/workspace`，会话挂载为 `/mnt/<别名>`，选择 Linux 主目录作为工作区时为 `/root`。结果格式沿用第 3.1 节，`runtime` 字段标明 LINUX，审批卡片以独立类别提示“在 Linux 环境中执行”。
- Play 渠道不打包 proot、rootfs 与 `libtermux.so`，Linux 运行时恒为 `Unavailable(DISTRIBUTION)`；`scripts/verify-release-apk.py --distribution` 对两个渠道分别校验。
- 真机（PKH110，ColorOS 16.1，2026-10-25）：Linux 后台任务启动 `specialUse` 前台服务后回到桌面，Hans 仍很快冻结整个应用 uid（proot 与 guest 进程停在 `do_freezer_trap`），回到前台后任务继续并正常合入工作区；默认电池策略下长时间 Linux 后台任务需要用户允许应用后台运行。

## 5. Termux 与 SSH 后端（D）

- `termux_exec`、`ssh_exec` 保持现有接口，结果按第 3.1 节对齐。
- Termux 主目录与 Infinity Workbench 工作区互不可见。双向交换文件的方式：读写挂载 Termux 主目录到 `/mnt/<别名>`，文件工具在两边复制；Termux 侧命令看不到 Infinity Workbench 工作区。
- 模型选择后端的规则写进工具描述：需要完整 Linux、apt 包、原生 Python 扩展或编译时用 Termux/SSH；其余优先内置后端。

## 6. 失败文案结构化

状态：已实施（`ui/chat/manager/ToolFailureSummary.kt`、`WorkspaceToolSupport.missingEntryGuidance`）。

`ToolExecutor` 失败分支当前为 `"工具执行失败：${result.content.take(200)}"`，同一字符串既写入执行步骤（界面）也写入工具消息（模型）。Termux 结果 JSON 的 `stdout` 排在 `stderr`、`exit_code` 之前，stdout 稍长就会把退出码和 stderr 截掉；纯文本失败也只剩 200 字符。

改为 `ToolFailureSummary.format(content)`：

1. `content` 能解析为 JSON 对象且含任一根因或输出字段时，按顺序取 `error_code`、`guidance`、`exit_code`、`timed_out`、`err`、`errmsg`，再取其余标量字段（如 `truncated`、`length_unit`），最后取 `stderr` 与 `stdout`，组装为紧凑 JSON；值为 null 或空输出的字段省略。
2. 总长上限 4,000 UTF-16 字符（按 JSON 编码后长度计）；超出时依次压缩 `stdout`（保留尾部）、`stderr`（保留尾部）、`errmsg`（保留开头），`error_code`、`guidance`、`exit_code` 始终完整保留。截断处标注 `…(已截断，原始 N 字符)`，结果自带 `<字段>_original_length` 时取其与当前长度的较大值。
3. 非 JSON 内容，以及不含上述字段的 JSON，保留前 1,000 字符并标注原始长度。
4. 前缀“工具执行失败：”保留，供界面识别。
5. 执行步骤与工具消息使用同一摘要。

文件工具的“未找到条目”同样缺少下一步指引：`write_file` 只覆盖已有文件，模型对新路径调用时只得到“未找到条目：<路径>”，真机上多用了两轮才改用 `create_file`。`WorkspaceToolSupport` 的该错误追加指引：“新建文件请使用 create_file；先用 list_files 确认路径”。

测试（`ToolFailureSummaryTest`）：stdout 2 万字符的 Termux 失败结果保留 `exit_code` 与 stderr 尾部；只有 `error_code`/`guidance` 的结果原样保留；超长 guidance 不被截断；非 JSON 文本按 1,000 字符截断并带原始长度；中文与代理对不被截断在半个字符上。

## 7. 实施顺序

1. 失败文案结构化与文件工具“未找到条目”指引（第 6 节），独立于执行后端，可先合入。（已完成）
2. 统一结果格式（第 3.1 节）；`termux_exec`、`ssh_exec` 结果对齐。（已完成）`ExecBackend` 接口随第 4 步内置后端一起引入，避免只有现有两个实现时的空抽象。
3. 打包 Python（构建脚本纳入 Gradle 任务，产物不入库，按版本与 SHA-256 校验官方包）与 `useLegacyPackaging`。（已完成）标准库未预编译 `.pyc`（需要同版本的宿主 Python），冷启动沿用第 2 节实测值。
4. `shell_exec`、`python_exec`、审批卡片、工作区写锁与对账。（已完成）不设 `:exec` 进程，原因见第 4.2 节；前台服务与唤醒锁属于第 7 步。
5. 沙箱模式。（已完成）复用 `exec_js` 的隔离服务与能力代理，工作区文件按需读写而不是预先声明输入；见第 4.3 节。
6. dulwich 与 `git` 包装、pip（侧载）。（已完成）不改动 `ensurepip` 与标准库 zip；dulwich 依赖单独打包为 `libpysite.so`，便于独立升级与发行扫描。
7. 前台服务、唤醒锁与冻结检测文案。（已完成）停止按钮返回结构化结果 `EXEC_STOPPED` 而非取消整轮生成；冻结检测以轮询间隔判断并把暂停时间排除在超时之外，没有采用子进程 CPU 时间心跳。

每一步的验收：单元测试覆盖结果格式、对账差异、失败摘要；设备测试在 API 31、API 35 模拟器与真机上覆盖命令执行、超时、取消、对账与审批。

## 8. 待验证与风险

| 项目 | 状态 |
| --- | --- |
| 持有 `PARTIAL_WAKE_LOCK` 后锁屏未充电时心跳是否连续，以及耗电 | 未测 |
| Play Feature Delivery 按需模块中的原生库是否进入可执行的 `nativeLibraryDir`，可否把 Python 改为按需下载 | 未测 |
| Play 政策对解释器加载 pip 包的适用性 | 未评审 |
| dulwich 经 HTTPS 推送与认证（已实测：HTTPS 浅克隆公开仓库；本地裸仓库的 push、pull；init、add、commit、log、diff、status、branch、remote） | HTTPS push 未测；私有仓库只能把令牌写进远端 URL（会存入工作区的 `.git/config`），未接入凭据存储 |
| shell 函数在子 `sh` 脚本与 `subprocess` 中不可见 | 已知限制；工具说明给出 `python3 -m infinity_git` 等替代写法（真机实测模型会先尝试 `subprocess.run(['git', ...])`，失败后改用 `dulwich.porcelain`） |
| dulwich 没有单分支克隆 | 已由 `infinity_git` 包装层补齐：`--depth` 隐含 `--single-branch`，支持 `--single-branch`/`--no-single-branch`/`--no-tags`，单分支克隆收窄远端 fetch refspec，标签自动跟随（Mac 上对 GitHub 实测协议 v2 下浅克隆、`-b <标签>` 与后续 fetch 范围正确） |
| `log` 不支持 `--all`、`--format` 等选项；`tag` 不支持无参数列出标签 | 已知限制 |
| 用户 pip 安装的包排在打包依赖之后（如更新的 urllib3 被打包版本遮蔽） | 已知限制；保证内置 git 行为稳定 |
| 沙箱模式单个文件只支持不超过 64 KiB 的 UTF-8 文本（与 JavaScript 脚本相同，受 Binder 消息上限约束） | 已知限制；大文件与二进制改用 `python_exec` |
| `linker64` / 数据目录 dlopen 的 auditallow 在后续系统版本被收紧 | 设计不依赖该行为 |
| 命令绕过回收区，删除与覆盖不可恢复 | 审批时明示；是否加硬链接快照另行评估 |
| 命令运行期间同一工作区的文件读写（含界面打开文档）最长等待约 600 秒（被系统暂停的时间另计） | 已知限制；侧载渠道可从通知停止，两个渠道都可在聊天界面实时输出区停止该命令 |
| 命令自行 `setsid`/守护化的进程不在进程组内，结束后可能继续改动工作区 | 未处理；对账只反映结束时刻的磁盘状态 |
| 对账重算大文件哈希的耗时（数 GB 文件需数秒）与 inode 复用导致的误判移动（要求文件 size 与 mtime 同时未变） | 已知限制 |
| 命令在对账之后才写入的文件、或进程被杀后到下一次修改之间的磁盘变化 | 由 `.infinity_command_pending` 恢复对账收敛；根身份标记若在进程被杀前已被删除则无法自动恢复 |
| ColorOS 默认电池策略下前台服务在熄屏后仍被冻结（探针实测） | 不能由应用解决；结果的 `paused_ms` 与指引提示用户设为“完全允许后台行为” |
| `ssh_exec` 超限时保留开头，长输出的结尾（通常是错误）会丢失 | 改为保留尾部需先实现流式遮蔽（跨块识别凭据与私钥块） |
