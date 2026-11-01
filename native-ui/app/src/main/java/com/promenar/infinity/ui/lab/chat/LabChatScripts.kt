package com.promenar.infinity.ui.lab.chat

/**
 * 会话页脚本的文案。内容取自宣传片真机实跑原文（starlog 与 web-01 演示环境），
 * 按界面语言二选一；命令、路径与输出保持原样，不随语言变化。
 * 画面中不出现真实用户名、家目录或网络地址：远端账户写作 deploy，路径用 ~ 或 /root。
 */
internal class LabAdeText(zh: Boolean) {
    val sessionTitle = if (zh) "添加JSON输出" else "Add JSON output"
    val branchTitle = if (zh) "配置迁移到 TOML" else "Move config to TOML"
    val prompt = if (zh) {
        "给 starlog 加一个 --json 输出。先列计划，再派两个子代理并行：一个读代码找出要改的地方，一个检查现有测试还缺什么。" +
            "完整测试比较慢，放到后台跑。另外把“配置迁移到 TOML”单独提成分支会话，用新的 git worktree。改完提交。"
    } else {
        "Add a --json output to starlog. Make a plan first, then send two sub-agents in parallel: one reads the code to find " +
            "what needs to change, the other checks what the existing tests are missing. The full test suite is slow, so run it " +
            "in the background. Also propose \"move config to TOML\" as a separate branch session in a new git worktree. Commit when you're done."
    }
    val explored = if (zh) {
        "项目结构清楚了：`parser.py`、`report.py`、`__main__.py`，4 个测试文件，其中 `test_large_log.py` 默认生成 400 万行，确实很慢。先建计划树。"
    } else {
        "The layout is clear: `parser.py`, `report.py`, `__main__.py` and 4 test files. `test_large_log.py` generates 4 million lines by default, so it really is slow. Building the plan first."
    }
    val planTitle = if (zh) "给 starlog CLI 增加 --json 输出（含测试与提交），并把 TOML 迁移提成分支会话" else "Add --json output to the starlog CLI (tests and commit included) and propose the TOML migration as a branch session"
    val planSteps = if (zh) {
        listOf("调研：代码改动点与测试缺口", "实现 --json 输出", "补充 JSON 输出测试", "后台跑完整测试套件", "提出 TOML 迁移分支方案", "提交 --json 改动")
    } else {
        listOf("Research: change points and test gaps", "Implement --json output", "Add JSON output tests", "Run the full suite in the background", "Propose the TOML migration branch", "Commit the --json change")
    }
    val dispatch = if (zh) "计划已建。现在并行派两个子代理。" else "Plan is in place. Sending two sub-agents in parallel now."
    val explorer1 = if (zh) "定位 --json 代码改动点" else "Locate the --json change points"
    val explorer2 = if (zh) "审查现有测试覆盖缺口" else "Review gaps in the existing tests"
    val explorer1Result = if (zh) {
        "## 改动点\n- `__main__.py`：argparse 增加 `--json`，与 `--format` 互斥\n- `report.py`：`render_table` 旁新增 `render_json`，复用统计结果"
    } else {
        "## Change points\n- `__main__.py`: add `--json` to argparse, mutually exclusive with `--format`\n- `report.py`: add `render_json` next to `render_table`, reusing the stats"
    }
    val explorer2Result = if (zh) {
        "## 测试缺口\n- 没有 JSON 结构与字段类型的断言\n- 没有空日志与只有错误行的情况\n- `test_large_log.py` 可用 `STARLOG_LARGE_LINES` 缩小"
    } else {
        "## Test gaps\n- No assertions on JSON structure or field types\n- No cases for an empty log or a log of only errors\n- `test_large_log.py` can be shrunk with `STARLOG_LARGE_LINES`"
    }
    val implement = if (zh) {
        "两个子代理结论都拿到了：改动集中在 `__main__.py` 的参数解析和 `report.py` 的输出层，测试缺 JSON 结构与空日志两种情况。现在开始实现。"
    } else {
        "Both sub-agents reported back: the change sits in argument parsing in `__main__.py` and the output layer in `report.py`, and the tests miss JSON structure and empty logs. Implementing now."
    }
    val testThinking = if (zh) "实现完成，接着按测试缺口补 JSON 结构、空日志与只有错误行三类用例。" else "Implementation done. Next, tests for JSON structure, an empty log and an errors-only log."
    val quickTest = if (zh) {
        "实现和测试都写好了，先跑快速测试验证（把大日志缩到 1000 行，避免慢测）。"
    } else {
        "Implementation and tests are written. Running the quick suite first, with the large log shrunk to 1000 lines."
    }
    val toBackground = if (zh) {
        "21 个测试全通过。现在核对真实 CLI 输出，并把 400 万行的完整测试丢到后台。"
    } else {
        "All 21 tests pass. Checking the real CLI output now and sending the 4-million-line full suite to the background."
    }
    val proposeBranch = if (zh) {
        "表格输出与改动前逐字符一致，JSON 结构正确。完整测试已在后台跑。现在更新进度，并提交 TOML 迁移的分支方案（需你选择派发方式）。"
    } else {
        "Table output matches the old output byte for byte and the JSON is well formed. The full suite is running in the background. Updating progress and proposing the TOML migration branch (pick how to dispatch it)."
    }
    val branchGoal = if (zh) {
        "把 starlog 的配置从当前的隐式/命令行来源迁移到 TOML 配置文件（默认 starlog.toml），并保持现有 CLI 行为向后兼容。" +
            "验收：新增 starlog/config.py 负责 TOML 读取与默认值合并；--json 输出不受影响；新增 tests/test_config.py 覆盖缺失文件、" +
            "部分字段、非法 TOML、默认值四种情况；python -m unittest discover -s tests -t . 全绿。"
    } else {
        "Move starlog's configuration from implicit and command-line sources to a TOML file (starlog.toml by default) while keeping " +
            "the current CLI behavior backward compatible. Done when: starlog/config.py reads TOML and merges defaults; --json output is " +
            "unaffected; tests/test_config.py covers a missing file, partial fields, invalid TOML and defaults; python -m unittest discover -s tests -t . is green."
    }
    val branchReason = if (zh) {
        "与当前 main 上的 --json 改动完全隔离，避免两边同时改 __main__.py 造成冲突；TOML 迁移需要独立分支评审后再合入。"
    } else {
        "Keeps it fully apart from the --json change on main so the two never edit __main__.py at once; the TOML migration needs its own branch review before merging."
    }
    val branchSteps = if (zh) {
        listOf("新增 starlog/config.py 读取 TOML 并合并默认值", "命令行参数覆盖配置文件", "补 tests/test_config.py 四种情况", "跑完整测试并提交")
    } else {
        listOf("Add starlog/config.py to read TOML and merge defaults", "Let command-line flags override the file", "Cover four cases in tests/test_config.py", "Run the full suite and commit")
    }
    val branchExclusions = if (zh) "不改 --json 输出与表格格式" else "Leave the --json output and table format alone"
    val branchCommunication = if (zh) "完成后汇报提交号与测试结果" else "Report the commit and test results when done"
    val cmdSurvey = if (zh) "查看项目结构" else "Inspect the project"
    val cmdQuick = if (zh) "快速测试" else "Quick tests"
    val cmdCli = if (zh) "核对 CLI 输出" else "Check CLI output"
    val cmdSuite = if (zh) "后台跑完整测试套件" else "Full suite in background"
    val cmdCommit = if (zh) "提交 --json 改动" else "Commit the --json change"
    val suiteDone = if (zh) "400 万行完整套件 59 秒跑完，21 个测试全绿。现在提交。" else "The 4-million-line full suite finished in 59 seconds with all 21 tests green. Committing now."
    val summary = if (zh) {
        "完成。\n\n- `--json` 功能已在 `main` 上提交为 `4831233`，工作区干净。\n" +
            "- TOML 迁移分支 `branch_21b2659c`，worktree 在 `/root/starlog-toml`，分支 `config-toml`，正在独立推进。"
    } else {
        "Done.\n\n- `--json` is committed on `main` as `4831233` and the working tree is clean.\n" +
            "- The TOML migration runs on its own in branch `branch_21b2659c`, worktree `/root/starlog-toml`, branch `config-toml`."
    }
}

internal class LabOpsText(zh: Boolean) {
    val sessionTitle = if (zh) "502错误排查修复" else "Fix the 502"
    val prompt = if (zh) "web-01 上的 starlog 报 502 了，帮我查一下原因并修好。" else "starlog on web-01 is returning 502. Find out why and fix it."
    val cmdStatus = if (zh) "查看服务状态" else "Check service status"
    val cmdPorts = if (zh) "查看监听端口" else "Check listening ports"
    val cmdLog = if (zh) "查看 api 日志" else "Read the api log"
    val cmdConfig = if (zh) "查看 api 配置" else "Read the api config"
    val cmdRestore = if (zh) "恢复数据库路径" else "Restore the database path"
    val cmdRestart = if (zh) "重启 starlog-api" else "Restart starlog-api"
    val cmdVerify = if (zh) "验证健康检查" else "Verify health checks"
    val survey = if (zh) "先登录 web-01，看 starlog 的部署方式、服务状态和 nginx 配置。" else "Logging in to web-01 first to see how starlog is deployed, its service status and the nginx config."
    val found = if (zh) {
        "找到了：用户级 systemd 服务 starlog-api 和 starlog-edge。edge 在 8080 对外，反代到 api 的 8081，而 api 一直在重启。看一下 api 的日志和配置。"
    } else {
        "Found it: user-level systemd services starlog-api and starlog-edge. edge serves 8080 and proxies to api on 8081, and api keeps restarting. Checking the api log and config."
    }
    val rootCause = if (zh) {
        "根因：`config.toml` 在 20:32:20 被改成指向 `/srv/starlog/data/starlog.db`，但该目录从未创建，starlog-api 启动即退出，" +
            "edge 拿不到上游，于是对外返回 502。先把数据库路径恢复为原来的 `~/starlog/data/starlog.db`，再重启 api。"
    } else {
        "Root cause: at 20:32:20 `config.toml` was changed to point at `/srv/starlog/data/starlog.db`, but that directory was never created. " +
            "starlog-api exits on start, edge has no upstream, so it answers 502. Restoring the database path to `~/starlog/data/starlog.db` and restarting api."
    }
    val summary = if (zh) {
        "## 已修复\n- **根因**：数据库路径指向从未创建的 `/srv/starlog/data`，starlog-api 启动即失败\n" +
            "- **修复**：恢复 `config.toml` 的 `db_path`，`systemctl --user restart starlog-api`\n\n" +
            "## 验证\n- `starlog-api`：`active`，8081 健康检查 `{\"status\": \"ok\"}`\n- 经 edge 访问：`GET :8080/health` → 200\n" +
            "- `GET :8080/api/stars` → 200，正常返回 Sirius / Canopus / Arcturus / Vega 四条记录\n\nedge 进程全程未重启，无需干预。"
    } else {
        "## Fixed\n- **Root cause**: the database path pointed at `/srv/starlog/data`, which was never created, so starlog-api failed on start\n" +
            "- **Fix**: restored `db_path` in `config.toml` and ran `systemctl --user restart starlog-api`\n\n" +
            "## Verified\n- `starlog-api`: `active`, health check on 8081 returns `{\"status\": \"ok\"}`\n- Through edge: `GET :8080/health` → 200\n" +
            "- `GET :8080/api/stars` → 200 with the four records Sirius / Canopus / Arcturus / Vega\n\nThe edge process never restarted and needs nothing."
    }
}
