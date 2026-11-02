# 执行环境

> 所属：[使用指南](README.md)。仓库首页只介绍概览，本文给出完整说明与边界。

## 内置 Linux 环境

仅侧载版、arm64-v8a 设备提供；Play 版不打包相关文件，入口不显示。安装包内含 Alpine 3.24.3 minirootfs（约 4 MB）与 proot，基础安装（bash、ca-certificates、curl）需约 64 MB 空间，附加包 git、ripgrep、Python 3（含 pip）、Node.js（含 npm）与 FFmpeg 默认勾选，全选约 470 MB（FFmpeg 连同编解码依赖约占 160 MB，可取消），需联网下载（默认清华镜像，可在镜像源页切换或测速）；附加包也可以之后在管理页补装。首次引导会在选择语言后提供同样的安装（可跳过），安装在后台进行，引导可继续，失败可从中断处重试。安装在 `filesDir/linux/alpine`，不进备份；卸载 Infinity Workbench 时一并删除。

每个会话固定一种运行模式，顶栏标题旁显示，点标题下方状态行的运行模式手动切换；新会话使用 Linux 设置中的“新会话默认模式”（Linux 未就绪时为内置）。切换不搬动文件，Linux 环境失效时不会静默改用其它环境。

- **Linux 模式**：Agent 使用 `shell_exec` 与文件工具，在 Alpine 中以 Infinity Workbench 身份运行。当前主工作区为 `/workspace`，会话私有目录固定为 `/home/session`（每个会话独立，即会话专用空间本身，Linux 重置不影响），有真实路径的外部挂载为 `/mnt/<别名>`，用户级技能为 `/mnt/skills`；SAF 授权的挂载对 Linux 会话不可见，添加外部文件夹只能按设备路径并需要所有文件访问。Linux 命令不受工作区路径约束，“工作区”档逐条审批，“完全访问”档免审批。缺少程序（退出码 127）时 Agent 会申请 `apk add`。命令在伪终端中运行（`TERM=xterm-256color`，120×40，stderr 并入 stdout），Python 等程序的输出逐行实时显示；前台命令默认 15 秒（`yield_after_seconds`，0 表示一直等到超时）仍未结束时转为后台任务继续运行，Agent 先拿到已有输出（例如登录链接与验证码）转告你，再用 `job_wait` 等待或 `job_input` 回答交互提示（审批规则同 `shell_exec`），命令结束后立即返回结果，会话私有目录的索引由后台合并对账；`job_status`、`job_wait` 与任务日志读取不触发全量扫描。私有目录的有效持久身份基线支持命令内改名和同卷移动延续 UUID 与版本关联，首次或基线失效时的边界见[工作区说明](workspaces.md)。生图、下载与画布返回 Linux 路径，回复里引用这些路径（含 Linux 环境中其它普通文件，只读）的图片与文件可直接显示和打开。
- **内置模式**：使用打包 Python（`python_exec`、`python_sandbox`）、隔离脚本与系统 sh 等受限工具，不能安装软件包；会话私有文件仍在会话专用空间。

进入 Linux 会话而环境不可用时，先尝试无损修复；不能无损修复时可重置（先显示将删除的系统、`/root` 与软件包），或把会话绑定的 Linux 工作区目录导出到会话专用空间或外部位置，每一步都可改用内置模式。重置会清空 `/root` 与已安装软件包、恢复初始系统，`/home/session` 与其它会话工作区不受影响；重置后 `/home`、`/opt`、`/srv` 下的目录同样随系统清空，绑定到旧环境任意 Linux 目录的会话需重新选择工作区。proot 下文件元数据密集的操作（大量小文件解包、编译）比原生慢。Linux 进程继承设备时区。Linux 模式的原生 git 访问 HTTPS 远端时，共用“设置 → 执行环境与连接 → Git 凭据”中保存的凭据（含 GitHub 登录），无需在 Linux 中另行登录或配置凭据助手。授权失效或缺少凭据时，命令会立即失败并给出对应的设置引导，不会停在用户名提示。工作区或挂载目录位于手机共享存储时，Linux 中看到的文件属主是系统媒体存储而不是 root，应用每次启动命令或终端时都把本次绑定的 `/workspace` 与各个 `/mnt/<别名>`（含其下子目录）作为 `safe.directory` 经 `GIT_CONFIG_*` 环境变量传给 git，因此不会出现 `dubious ownership`；这项配置不写入 Linux 系统，重置后依然有效，也不改动 `~/.gitconfig`。若在 shell 里自行设置 `GIT_CONFIG_COUNT`，会覆盖这项注入。`gh` 同样经应用代理使用已保存的 github.com 凭据：应用为每条命令生成受管的 `GH_CONFIG_DIR` 并把一次性凭证放进 `GH_TOKEN`，真实令牌只在应用侧注入 `api.github.com` 与上传请求，命令结束即失效；无需也不应运行 `gh auth login`。未保存 github.com 凭据时 gh 保持未登录状态，AI 会用保密输入卡请你填写或改用浏览器登录 GitHub。发行附件、源码包等下载地址跳转到的下载主机不带认证，私有仓库的此类下载请用 `git` 或 GitHub API。`git@主机:` 与 `ssh://` 远端在 Linux 中不经应用 SSH 桥，需要时切到内置模式。

## Termux 接入

安装 [F-Droid](https://f-droid.org/packages/com.termux/) 或 [GitHub](https://github.com/termux/termux-app/releases) 提供的 Termux，并先打开一次完成初始化。RUN_COMMAND 需要 0.109 及以上版本，在 `~/.termux/termux.properties` 开启 `allow-external-apps=true`，再为 Infinity Workbench 授予 RUN_COMMAND 权限；诊断页可复制配置命令、请求权限并运行固定测试命令。协议字段和限制以 [Termux 官方说明](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) 为准。

Termux 回传的 stdout/stderr 合计超过约 100KB 时保留尾部；工具分别返回原始长度和截断标记。超时或取消会释放本地等待并丢弃迟到回调，命令可能仍在 Termux 中运行，需要在 Termux 中检查和停止。Android 12+ 可能因子进程限制结束任务；`termux-wake-lock` 可防止休眠，但不能解除系统进程限制，用完执行 `termux-wake-unlock`。

SSH 路线复用 SSH 主机管理：在 Termux 安装 `openssh`、配置密码或公钥并运行 `sshd`，在 Infinity Workbench 添加 `127.0.0.1:8022` 和 `whoami` 返回的用户名。主目录通过系统文件选择器授权后，以 `/mnt/<别名>/...` 供文件工具使用；SAF 授权和终端执行权限分别管理。

## 隔离脚本运行时

`exec_js(code, allow_network=false, timeout_seconds=30)` 使用 QuickJS 1.0.15，在独立隔离 UID 服务中执行；优先返回 `result` 变量，未定义时返回最后表达式。支持顶层 `await`、Promise 和 `console.log/warn/error`；异步返回使用顶层 `await` 或将 Promise 赋给 `result`。`run_script(path, args=[], allow_network=false, timeout_seconds=30)` 执行会话工作区或 `/mnt/<别名>/...` 中的 `.js` / `.mjs`；模块通过 `result` 返回值，导入只接受入口所在目录内的相对 `.js` / `.mjs`，允许目录内的父级引用，拒绝越根、绝对路径、远程与裸包导入。每次调用创建全新引擎上下文，同一会话可复用隔离进程。

脚本仅通过主进程代理获得能力：`fs.read(path)` 返回 `{content, sha256, path}`，`fs.readFile(path)` 返回文本；`fs.stat/list/createFile/mkdir/writeFile/deleteFile` 复用文件工具的路径、只读、保留名、挂载身份与回收规则。覆盖写入使用 `fs.writeFile(path, content, {expected_sha256})`，删除可传同名版本条件。默认对写入按 `FILE_WRITE`、删除按 `DELETE` 单独审批，弹窗显示来源会话与文件目标；可记住会话内该文件操作的授权。工具设置的“脚本写入审批”可让写入继承脚本审批，删除仍单独审批。默认开启是因为批准脚本代码不等于已经看过它运行时生成的文件目标与内容。

`fetch(url, options)` 支持文本请求体、请求头、HTTP 方法，以及响应的 `status/ok/headers/text()/json()`。默认禁网，开启时与 `web_fetch`、`media_fetch` 共用同一套目标判定：公网直接放行，局域网与本机（含链路本地）需加入设置 → 工具 → 网络访问中的精确主机白名单（默认为空、不支持通配符），tailnet 经内置 Tailscale，未指定地址与组播地址始终拒绝。Android 系统策略只允许明文 http 访问本机 127.0.0.1，局域网服务需使用 https。网络逐跳验证并固定 DNS，不携带应用 Key、Cookie 或系统代理；白名单不放宽发行版的明文 HTTP 禁令。网络与写入授权设置属于本机状态，不随备份迁移。

超时为 1–300 秒，默认 30 秒；QuickJS 堆上限 64 MiB、栈 512 KiB。代码最多 50,000 字符，单文件和单次网络正文最多 64 KiB；每次执行最多 20 次网络请求（含重定向）、1 MiB 累计响应和 128 次能力调用。总结果限 64 KiB，日志限 16 KiB 并按顺序截断。超时、资源超限、生成取消会终止执行进程，失联后下次重建；主进程的阻塞能力调用最多占用 4 个共享槽，迟到回调不再交给脚本。已批准且已进入文件后端的操作沿用其提交与回滚语义，不能保证随取消原子撤销。真实 UID 权限、进程死亡与 JNI 行为需通过设备测试验收。

例如，读取后按版本保存：`var f = await fs.read('/notes.txt'); result = await fs.writeFile('/notes.txt', f.content + '\n完成', {expected_sha256: f.sha256});`。两个发行渠道提供相同脚本能力。

`python_sandbox(code, stdin="", allow_network=false, timeout_seconds=30)` 在同一个隔离 UID 服务中启动随 APK 打包的 Python 3.14（仅 arm64-v8a 设备）：看不到 Infinity Workbench 私有数据与工作区物理目录，没有可写目录，不能直接建立网络连接，只有标准库与 dulwich（侧载版 pip 装的包不可见）。代码用 `import infinity` 经同一套主进程代理访问工作区：`infinity.read(path)` 返回 `{content, sha256, path}`，另有 `read_text`、`stat`（不存在为 `None`）、`list`、`create`、`mkdir`、`delete`、`write(path, text, expected_sha256)`，以及“不存在则新建、存在则按当前哈希覆盖”的 `save(path, text)`；失败抛出 `infinity.InfinityError`（带 `code`）。`allow_network=true` 时 `infinity.fetch(url, method, headers, body)` 走与 `fetch` 相同的网络策略。文件、审批、网络与能力调用上限与 JavaScript 相同。结果沿用命令工具格式：`exit_code`、`stdout`（尾部 32 KiB）、`stderr`（尾部 12 KiB）与各流原始长度，`result` 变量（可 JSON 序列化，16 KiB 以内）另行返回；超时终止 Python 子进程并带回终止前的输出。需要直接读写大文件、子进程、git 仓库或 pip 包时使用 `python_exec`。

## SSH 使用

在“设置 → 工具 → SSH 主机与终端”新增主机，别名使用小写英文字母开头，后接小写字母、数字、下划线或连字符，最多 48 字符。密钥认证支持 Ed25519、ECDSA 和 RSA 的 OpenSSH 新格式，含 bcrypt 口令保护；PEM、PKCS8、PuTTY 和跳板连接尚未提供。生成密钥后仅把公钥复制到服务端的 `authorized_keys`。在可信渠道核对密钥类型与 SHA256 指纹，再点击信任；更换服务端密钥时须在同一页面明确替换信任。

Termux 路径：在 Termux 安装 OpenSSH，运行 `whoami` 获取用户名、设置密码后运行 `sshd`。在 Infinity Workbench 配置主机 `127.0.0.1`、端口 `8022` 和该用户名，核对 Termux 中 `ssh-keygen -lf "$PREFIX/etc/ssh/ssh_host_ed25519_key.pub" -E sha256` 显示的指纹后打开终端。需要密钥认证时，把 Infinity Workbench 复制的公钥添加到 Termux 的 `~/.ssh/authorized_keys`。

Agent 协助配置：“允许 Agent 管理 SSH 主机”默认开启（用户可关闭），开启时模型可使用 `ssh_host_list`、`ssh_host_save`、`ssh_host_delete` 与 `ssh_host_install_key`。保存密码主机且没有密码时弹窗请用户输入，保存密钥主机且没有密钥时生成 Ed25519 并只返回公钥；修改主机名或端口会清除已信任指纹。`ssh_host_install_key` 依次确认指纹、使用已存或弹窗输入的密码、生成新密钥并追加到远端 `~/.ssh/authorized_keys`（目录 700、文件 600、已存在则不重复），再用新密钥验证登录，成功后切换为密钥认证，失败则恢复原密码认证。后台限制可能暂停 Termux 自身，需按设备设置允许其运行。

Agent 工具只接受已配置别名，不接受地址或凭据。`ssh_exec` 默认 60 秒、最多 600 秒，stdout/stderr 合计采集有上限（超限时两路分配额度并保留开头），结果与 `termux_exec` 使用同一格式，退出码非 0 或超时记为失败；与 `shell_exec` 一样支持让出与 `background=true`，后台命令与本地后台任务同列在运行详情“后台”页，运行环境标签为“SSH · 主机 ID”，停止只断开通道；`sftp_read` 按 UTF-16 偏移分页，拒绝二进制与大于 16 MiB 的文件。`sftp_write` 支持 `expected_sha256` 冲突检查，以临时文件发布并保留目标权限，但 SFTP 不保证跨客户端原子比较写入。取消上传时会尝试删除临时文件；连接中断导致清理失败时，主机页显示提示，之后用相同配置连接会重试。

`sftp_upload` / `sftp_download` 传输任意二进制文件，单文件上限 4 GiB，分块流式读写并在两端计算 SHA-256，目标端重读校验一致后才发布。参数为 `host`、`local_path`、`remote_path`、`local_scope`（`workspace` 为会话工作区，以 `/` 为根；`linux` 为 Linux 内的绝对路径，仅侧载版的 Linux 会话可用，Play 版返回错误）与 `overwrite`（默认 false，目标已存在时返回 `TARGET_CONFLICT`），并沿用 `ssh_exec` 的让出与后台参数。上传先写远端 `<目标>.infinity-<uuid>.tmp`，新目标通过 OpenSSH 的 `hardlink@openssh.com` 扩展发布，服务器不支持时返回 `ATOMIC_PUBLISH_UNSUPPORTED`，不会退回可能覆盖的方式；允许覆盖的已有目标用 rename 替换。下载先写本地临时文件，拒绝写入 `/skills` 与 `.agents` 路径，不支持 SAF 挂载的文件夹。上传按外部写入、下载按本地写入审批，审批绑定真实主机身份。传输进入后台任务列表并显示进度，`job_status` 的 `transfer` 字段给出方向、路径、字节数、速度、摘要与失败原因；工具结果与 `job_status` 另有 `connection_source`（`transferred` 表示借用了本会话已认证的空闲连接）与 `timings_ms` 分段耗时，任务日志末尾也有一行分段耗时摘要。传输任务优先借用同一会话空闲的已认证连接，正常结束后归还，停止、超时或断连时断开不归还。SFTP 不提供跨客户端的原子比较写入；断网时远端临时文件留待之后连接同一主机时清理。

终端离开页面默认断开，可为单个主机选择保持；用户终端和本地转发运行时使用前台通知，可从通知停止。PTY 闲置 10 分钟自动关闭；Agent 生成结束释放连接，待审批期间最多保留 10 分钟，显式停止或删除会话会关闭所属连接。进程结束不恢复终端或转发，页面状态以运行时资源为准。
