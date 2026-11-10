# 后台进程、长时间任务与定时任务

需要让进程在一次工具调用之后继续运行，或者要周期性地做一件事时，按本文件操作。命令结果字段、各执行环境差异与错误码见同目录的 `command-execution.md`。

## 选用

| 需求 | 做法 |
|---|---|
| 命令能在 `timeout_seconds`（最多 600 秒）内结束 | 前台调用 `shell_exec`、`python_exec`、`termux_exec` 或 `ssh_exec` |
| 构建、下载、训练、开发服务器等耗时更久或需要持续运行 | 同一工具加 `background: true`，得到 `job_id` |
| 任务结束后需要你接着处理 | `background: true` 加 `on_exit: "wake"`，然后结束本轮 |
| 定期检查、到点提醒、应用重启后仍要继续的周期工作 | `schedule_create` |
| Linux 脚本在某个事件发生时叫醒你 | 后台任务里调用 `infinity-wake` |
| 远端主机上脱离本应用长期存活的服务 | 用 `ssh_exec` 交给远端的 systemd、tmux 或 nohup，之后用 `ssh_exec` 查询 |

后台任务由应用登记：有任务号、日志、状态、停止入口，并计入应用的后台保活。命令里自行后台化的进程没有这些。

## 各环境里自行后台化的结果

| 环境 | `nohup`、`&`、`setsid`、`screen`、`tmux` 启动的进程 |
|---|---|
| 内置模式（系统 sh、打包 Python） | 命令结束或超时时，同一进程组的子进程一并收到 TERM、随后 KILL。用 `setsid` 脱离进程组的进程没有日志、状态与停止入口，不计入保活，应用进程被系统回收时一并消失 |
| Linux 模式（proot + Alpine） | 每条命令运行在独立的 proot 中，主命令退出时其中全部进程被结束，`setsid`、`tmux` 也不例外 |
| Termux | 进程属于 Termux，前台 `termux_exec` 里 `nohup … &` 启动后会继续运行，但应用没有它的任务号、日志与停止入口；`background: true` 由应用代为 `setsid nohup` 启动并登记 |
| SSH 远端 | 进程在远端主机，是否存活由远端决定。`nohup`、tmux、systemd 在这里是让服务脱离本应用存活的正确做法 |

因此在本机三种环境里，持续运行一律用 `background: true`。后台任务的主命令必须保持前台运行：服务类程序关闭自带的守护化（例如 `nginx -g 'daemon off;'`，不传 `-d`、`--daemonize`），命令末尾不加 `&`。主命令一退出，任务即结束，残留子进程被清理。

## 启动

```json
{"command": "npm run build", "background": true, "on_exit": "wake", "max_runtime_minutes": 180, "title": "构建前端"}
```

- `max_runtime_minutes`：1–1440，默认 120。到时任务被终止，状态为 `timed_out`。后台模式不使用 `timeout_seconds`。
- `on_exit`：`none` 不处理；`notify`（默认）只给用户发系统通知，你不会收到消息，需要自己调用 `job_status`；`wake` 在任务结束时为本会话发起新的一轮，带上状态、退出码与日志末尾 2000 字符。
- `capture_secret` 只支持前台，与 `background: true` 同用返回 `SECRET_CAPTURE_BACKGROUND_UNSUPPORTED`。
- 返回 `job_id`、`state`、`runner`、`started_at`、`launch_confirmed`、`guidance`。`launch_confirmed` 为 false（SSH 任务始终如此，Termux 在拿到进程号之前如此）表示启动结果尚未确认，先用 `job_status` 确认，不要直接重发命令。
- 同时运行的任务数有会话上限与全局上限，超出返回 `JOB_SESSION_LIMIT` 或 `JOB_GLOBAL_LIMIT`，`guidance` 给出当前上限；先等待或停止已有任务。

Linux 模式的前台 `shell_exec` 与 `ssh_exec` 默认在 15 秒后自动让出：返回 `state: "running"` 与 `job_id`，进程继续运行，后续操作与 `background: true` 启动的任务相同。`yield_after_seconds: 0` 关闭自动让出，等待到 `timeout_seconds`。内置模式的 `shell_exec` 与两种模式的 `python_exec` 不自动让出，超时即终止。

## 查询、等待、输入、停止

| 工具 | 用途 | 要点 |
|---|---|---|
| `job_status` | 当前状态与日志末尾 | `log_tail` 最多 8000 字符；`last_output_seconds_ago` 可判断是否卡住；`keep_alive` 见下文 |
| `job_wait` | 阻塞等待 | `timeout_seconds` 1–600，默认 60；`until_output` 是匹配调用之后新输出的正则，命中时返回 `matched_line`；`until_file` 是工作区相对路径或 `/workspace` 路径，出现即返回，SSH 任务不支持。返回的 `timed_out: true` 表示等待到时，任务仍在运行 |
| `job_input` | 回答交互提示 | 只对 Linux 模式下未传 `stdin` 启动的 `shell_exec` 任务有效；`newline` 默认追加回车，`eof` 发送输入结束。工作区档逐次审批 |
| `job_stop` | 停止任务 | 本机任务对整个进程组发 TERM，2 秒后 KILL；SSH 任务只断开通道，远端进程可能仍在运行；Termux 返回 `JOB_STOP_UNCONFIRMED` 时进程组尚未确认停止，稍后重试 |
| `job_list` | 列出本会话任务 | 不含日志。任务按会话隔离，其它会话的任务号返回 `JOB_NOT_FOUND` |

等待服务就绪的常用写法：启动后调用 `job_wait`，`until_output` 写就绪标志（例如 `Listening|ready`），`timeout_seconds` 取合理上限。不要用循环反复调用 `job_status` 轮询；没有事可做时用 `on_exit: "wake"` 并结束本轮。

### 状态

| `state` | 含义 | 下一步 |
|---|---|---|
| `running` | 仍在运行 | 继续其它工作，或 `job_wait` |
| `exited` | 进程自行退出 | 看 `exit_code` 与 `log_tail` |
| `stopped` | 被用户或 `job_stop` 停止 | 用户在通知栏或界面停止的，不要自动重启，先问用户 |
| `timed_out` | 超过 `max_runtime_minutes` 被终止 | 确有需要时用更大的时限重新启动 |
| `interrupted` | 应用进程重启，本机任务的残留进程已清理；SSH 任务为通道断开 | 本机任务不会自动恢复，按需重新启动；SSH 任务先用 `ssh_exec` 检查远端进程是否仍在 |
| `failed` | 启动失败或状态无法确认 | 读 `guidance`，修正后重试 |

### 日志

工具只返回日志末尾 8000 字符，`log_reference` 不是文件路径，完整日志只有用户能在界面查看，单个任务日志上限 20 MiB。需要完整输出时，让命令自己写到工作区文件（`命令 > build.log 2>&1`），再用 `read_file` 或 `search_files` 读取。Linux 任务的 stderr 已合并到 stdout。

### 工作区

内置模式下，私有工作区的后台任务在工作区快照中运行（返回 `workspace_mode: "snapshot_merge_on_exit"`）：任务写出的文件在结束合入之前对文件工具不可见，冲突产物保留在 `jobs/`。Linux 模式的任务直接在原目录运行，结束后对账，结果含 `workspace_changes`。自选目录工作区不对账，用 `git status` 查看改动。

## 后台任务能活多久

- 最长 24 小时（`max_runtime_minutes` 上限 1440）。
- `job_status` 的 `keep_alive` 为 `service` 时，应用用前台服务保活，离开应用后任务继续运行；为 `foreground_only`（Play 版）时只在应用位于前台时保证运行，离开应用后可能暂停。SSH 任务始终由连接服务保活。
- 取消本轮生成不会停止后台任务；删除会话会停止该会话的全部任务。
- 应用进程被系统回收、崩溃、更新或设备重启后，本机任务变为 `interrupted`，不会自动恢复。Termux 任务运行在 Termux 中，应用重启后仍可用原任务号查询，存活取决于 Termux 自身是否被系统回收。
- 系统冻结应用期间任务暂停，前台命令的 `paused_ms` 给出暂停时长，不计入超时。

保活条件由用户在“设置 → 后台与定时”查看与调整：离开应用后继续运行、精确闹钟、电池优化、通知。你不能代为授权；任务被系统中断或定时任务明显延后时，请用户到该页把电池优化设为不受限制并允许通知与精确闹钟。

需要超过 24 小时或跨越应用重启的服务：放到 SSH 远端主机，或在本机用“后台任务 + 定时看门狗”——后台任务运行服务，`schedule_create` 的 interval 任务定期用 `job_list` 检查，任务不在 `running` 时重新启动。

## 定时任务

`schedule_create` 每次到点为本会话发起完整的一轮，把 `prompt` 交给你执行；它不保持任何进程存活。定时任务保存在应用中，应用重启后依然有效。

- `trigger.type`：`once`（`in_minutes` 1–10080，或用户给出具体钟点时用 `at`）、`interval`（`every_minutes`）、`cron`（五段表达式 `expr`）。时区固定为创建时的设备时区。
- 间隔下限：应用在前台时最短 1 分钟；在后台，重复任务精确模式至少 5 分钟，非精确至少 15 分钟，系统可能再延后几分钟。单次任务不受后台下限约束。`exact: true` 需要用户授予精确闹钟权限。
- `max_runs`、`expires_at` 限定次数与有效期。
- 每个会话最多启用 5 个，全局最多 20 个。创建每次都需用户批准；`schedule_list`、`schedule_pause`、`schedule_resume`、`schedule_cancel` 免审批。
- 触发的那一轮以 system_trigger 标记，正文不是用户亲自发送。每轮应独立完成检查；不再需要时调用 `schedule_cancel`。

## infinity-wake

Linux 模式的脚本可调用 `infinity-wake [--key K] [--title T] [消息]`（消息缺省读 stdin）唤醒本会话，适合在后台任务里盯日志、等下载。同一会话 60 秒内最多唤醒一次，期间多条合并、同 key 只保留最新，正文超过 4096 字符截断；只在应用运行期间有效。

## SSH 远端的长期服务

`ssh_exec` 的每条命令使用独立的执行通道，不分配终端。`background: true` 的 SSH 任务绑定在这条通道上：`job_stop`、到达时限、网络切换或应用重启都只是断开通道，远端进程可能仍在运行，也可能已随通道结束。

要让服务脱离本应用存活，交给远端主机管理，并把标准输入输出重定向，否则命令会一直占用通道直到超时：

```sh
systemd-run --user --unit=myapp /opt/myapp/run.sh
nohup /opt/myapp/run.sh > /var/tmp/myapp.log 2>&1 < /dev/null &
tmux new-session -d -s myapp '/opt/myapp/run.sh'
```

之后用 `ssh_exec` 执行 `systemctl --user status myapp`、`pgrep -af myapp`、`tail -n 50 /var/tmp/myapp.log` 查询，用 `systemctl --user stop`、`pkill` 或 `tmux kill-session` 停止。需要与远端交互式程序对话时使用 `ssh_session_open`、`ssh_session_send`、`ssh_session_read`、`ssh_session_close`；智能体打开的终端随本轮生成结束释放，不能用来保持进程。
