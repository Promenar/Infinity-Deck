package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.ToolCall
import org.junit.Test

class ToolDetailModelsTest {

    private val initArgs = """{"goal":"迁移仓库","tree":[{"id":"1","title":"环境与空间检查"},{"id":"2","title":"复制仓库到手机存储"},{"id":"3","title":"完整性与 git 校验"}]}"""
    private val initResult = """{"planId":"1","goal":"迁移仓库","status":"doing","leafProgress":{"done":0,"total":3},"currentFocusStepId":"1","message":"任务已创建，共 3 个步骤"}"""

    private fun init(key: String = "init") = PlanCallRecord(key, "initialize_plan", initArgs, initResult)

    private fun update(key: String, ops: String, focus: String? = null, failed: Boolean = false) = PlanCallRecord(
        key, "update_plan", """{"operations":$ops}""",
        focus?.let { """{"applied":1,"currentPlan":{"planId":"1","goal":"迁移仓库","status":"doing","leafProgress":{"done":1,"total":3},"currentFocusStepId":"$it"}}""" },
        failed,
    )

    // ── 计划 ──

    @Test fun `初始化计划按参数建清单且第一个叶子为当前`() {
        val model = planCardModel(replayPlanCalls(listOf(init())).getValue("init"))!!
        assertThat(model.rows.map { it.title to it.state }).containsExactly(
            "环境与空间检查" to PlanRowState.CURRENT,
            "复制仓库到手机存储" to PlanRowState.TODO,
            "完整性与 git 校验" to PlanRowState.TODO,
        ).inOrder()
        assertThat(model.position).isEqualTo(1)
        assertThat(model.total).isEqualTo(3)
        assertThat(model.rows.none { it.justCompleted }).isTrue()
        assertThat(planSummaryText(model)).isEqualTo(ToolText(R.string.tool_card_plan_summary_created, listOf("迁移仓库", 3)))
    }

    @Test fun `更新计划标出刚完成与当前步骤并概括变化`() {
        val snapshots = replayPlanCalls(listOf(
            init(),
            update("u1", """[{"action":"set_status","stepId":"1","payload":{"status":"done"}},{"action":"set_status","stepId":"2","payload":{"status":"doing"}}]""", focus = "2"),
            update("u2", """[{"action":"set_status","stepId":"2","payload":{"status":"done"}},{"action":"set_status","stepId":"3","payload":{"status":"in_progress"}}]""", focus = "3"),
        ))
        val first = planCardModel(snapshots.getValue("u1"))!!
        assertThat(first.rows.map { Triple(it.title, it.state, it.justCompleted) }).containsExactly(
            Triple("环境与空间检查", PlanRowState.DONE, true),
            Triple("复制仓库到手机存储", PlanRowState.CURRENT, false),
            Triple("完整性与 git 校验", PlanRowState.TODO, false),
        ).inOrder()
        assertThat(planSummaryText(first)).isEqualTo(
            ToolText(R.string.tool_card_plan_summary_done_started, listOf("环境与空间检查", "复制仓库到手机存储")),
        )
        // 第二次只把上一步标为刚完成，之前完成的步骤不再标注。
        val second = planCardModel(snapshots.getValue("u2"))!!
        assertThat(second.rows.filter { it.justCompleted }.map { it.title }).containsExactly("复制仓库到手机存储")
        assertThat(second.position).isEqualTo(3)
        assertThat(planSummaryText(second)).isEqualTo(
            ToolText(R.string.tool_card_plan_summary_done_started, listOf("复制仓库到手机存储", "完整性与 git 校验")),
        )
    }

    @Test fun `完成多步与只开始或只完成时摘要各有文案`() {
        val many = replayPlanCalls(listOf(init(), update("u", """[
            {"action":"set_status","stepId":"1","payload":{"status":"done"}},
            {"action":"set_status","stepId":"2","payload":{"status":"done"}},
            {"action":"set_status","stepId":"3","payload":{"status":"doing"}}]""", focus = "3")))
        assertThat(planSummaryText(planCardModel(many.getValue("u"))!!))
            .isEqualTo(ToolText(R.string.tool_card_plan_summary_done_many_started, listOf(2, "完整性与 git 校验")))

        val startedOnly = replayPlanCalls(listOf(init(), update("u", """[{"action":"set_status","stepId":"3","payload":{"status":"doing"}}]""", focus = "3")))
        val startedModel = planCardModel(startedOnly.getValue("u"))!!
        assertThat(planSummaryText(startedModel)).isEqualTo(ToolText(R.string.tool_card_plan_summary_started, listOf("完整性与 git 校验")))
        // doing 同时只有一个：原来的当前步骤回到待办。
        assertThat(startedModel.rows.first().state).isEqualTo(PlanRowState.TODO)

        val finished = replayPlanCalls(listOf(init(), update("u", """[
            {"action":"set_status","stepId":"1","payload":{"status":"done"}},
            {"action":"set_status","stepId":"2","payload":{"status":"done"}},
            {"action":"set_status","stepId":"3","payload":{"status":"completed"}}]""", focus = "")))
        val finishedModel = planCardModel(finished.getValue("u"))!!
        assertThat(finishedModel.allDone).isTrue()
        assertThat(finishedModel.position).isEqualTo(3)
        assertThat(planSummaryText(finishedModel)).isEqualTo(ToolText(R.string.tool_card_plan_summary_done_many, listOf(3)))

        val single = replayPlanCalls(listOf(init(), update("u", """[{"action":"set_status","stepId":"1","payload":{"status":"done"}}]""", focus = "")))
        assertThat(planSummaryText(planCardModel(single.getValue("u"))!!)).isEqualTo(ToolText(R.string.tool_card_plan_summary_done, listOf("环境与空间检查")))
    }

    @Test fun `看不出状态变化时摘要退回通用文案`() {
        val renamed = replayPlanCalls(listOf(init(), update("u", """[{"action":"update_title","stepId":"3","payload":{"title":"校验"}}]""", focus = "1")))
        val model = planCardModel(renamed.getValue("u"))!!
        assertThat(model.rows.last().title).isEqualTo("校验")
        assertThat(planSummaryText(model)).isNull()
    }

    @Test fun `新增放弃移除步骤后清单随之变化`() {
        val snapshots = replayPlanCalls(listOf(init(), update("u", """[
            {"action":"add_step","stepId":"4","payload":{"title":"收尾与说明"}},
            {"action":"set_status","stepId":"2","payload":{"status":"dropped"}},
            {"action":"remove_step","stepId":"3"}]""")))
        val model = planCardModel(snapshots.getValue("u"))!!
        assertThat(model.rows.map { it.title }).containsExactly("环境与空间检查", "收尾与说明").inOrder()
        assertThat(model.total).isEqualTo(2)
    }

    @Test fun `结果中的当前步骤纠正回放看不到的变化`() {
        val snapshots = replayPlanCalls(listOf(init(), update("u", """[{"action":"set_note","stepId":"1","payload":{"note":"x"}}]""", focus = "2")))
        val model = planCardModel(snapshots.getValue("u"))!!
        assertThat(model.rows.map { it.state }).containsExactly(PlanRowState.TODO, PlanRowState.CURRENT, PlanRowState.TODO).inOrder()
    }

    @Test fun `前面没有已知计划时更新调用没有快照`() {
        val snapshots = replayPlanCalls(listOf(update("u", """[{"action":"set_status","stepId":"1","payload":{"status":"done"}}]""", focus = "2")))
        assertThat(snapshots).doesNotContainKey("u")
        assertThat(standalonePlanSnapshot("u", "update_plan", """{"operations":[]}""", null, failed = false)).isNull()
    }

    @Test fun `失败的调用与丢弃计划不影响后续回放`() {
        val snapshots = replayPlanCalls(listOf(
            init(),
            update("bad", """[{"action":"set_status","stepId":"1","payload":{"status":"done"}}]""", failed = true),
            update("ok", """[{"action":"set_status","stepId":"2","payload":{"status":"doing"}}]""", focus = "2"),
            PlanCallRecord("drop", "drop_plan", "{}", """{"dropped":true}"""),
            update("after", """[{"action":"set_status","stepId":"2","payload":{"status":"done"}}]""", focus = ""),
        ))
        assertThat(snapshots).doesNotContainKey("bad")
        assertThat(planCardModel(snapshots.getValue("ok"))!!.rows.first().state).isEqualTo(PlanRowState.TODO)
        assertThat(snapshots).doesNotContainKey("after")
    }

    @Test fun `已有计划时初始化冲突显示原计划`() {
        val conflict = PlanCallRecord("c", "initialize_plan", """{"goal":"新目标","tree":[{"id":"a","title":"新步骤"}]}""",
            """{"conflict":true,"existingPlanId":"1","existingGoal":"迁移仓库","existingProgress":"0/3 done"}""")
        val snapshots = replayPlanCalls(listOf(init(), conflict))
        assertThat(planCardModel(snapshots.getValue("c"))!!.rows.map { it.title }).contains("复制仓库到手机存储")
        assertThat(planCardModel(snapshots.getValue("c"))!!.goal).isEqualTo("迁移仓库")
    }

    @Test fun `读取计划从结果树取状态并支持嵌套分组`() {
        val result = """{"active":true,"planId":"1","goal":"发布","status":"doing","leafProgress":{"done":1,"total":3},"currentFocusStepId":"1.2",
            "tree":[{"id":"1","title":"构建","status":"doing","children":[{"id":"1.1","title":"编译","status":"done"},{"id":"1.2","title":"打包","status":"doing","sortOrder":1}]},
            {"id":"2","title":"上传","status":"todo","sortOrder":1}]}"""
        val snapshot = standalonePlanSnapshot("g", "get_plan", "{}", result, failed = false)!!
        val model = planCardModel(snapshot)!!
        assertThat(model.rows.map { Triple(it.title, it.depth, it.isGroup) }).containsExactly(
            Triple("构建", 0, true), Triple("编译", 1, false), Triple("打包", 1, false), Triple("上传", 0, false),
        ).inOrder()
        assertThat(model.rows.single { it.title == "打包" }.state).isEqualTo(PlanRowState.CURRENT)
        assertThat(model.position).isEqualTo(2)
        assertThat(model.total).isEqualTo(3)
        assertThat(planSummaryText(model)).isEqualTo(ToolText(R.string.tool_card_plan_summary_viewed, listOf(2, 3)))
        assertThat(standalonePlanSnapshot("g", "get_plan", "{}", """{"active":false,"message":"当前没有活跃计划"}""", false)).isNull()
    }

    @Test fun `解析失败或参数不完整时退回原文`() {
        assertThat(standalonePlanSnapshot("x", "initialize_plan", "不是 JSON", null, false)).isNull()
        assertThat(standalonePlanSnapshot("x", "initialize_plan", """{"goal":"g","tree":[{"title":"缺少 id"}]}""", null, false)).isNull()
        assertThat(standalonePlanSnapshot("x", "get_plan", "{}", "{oops", false)).isNull()
        assertThat(standalonePlanSnapshot("x", "web_fetch", initArgs, initResult, false)).isNull()
    }

    @Test fun `会话消息中的计划调用按展示副本的步骤键回放`() {
        val messages = listOf(
            Message("m1", MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("c1", "initialize_plan", initArgs)), executionSteps = listOf(
                ExecutionStep("s1", "tool_call", toolArgs = initArgs, toolCallId = "c1"),
                ExecutionStep("r1", "tool_result", toolCallId = "c1", content = initResult),
            )),
            Message("u", MessageRole.USER, "继续"),
            Message("m2", MessageRole.ASSISTANT, "", executionSteps = listOf(
                ExecutionStep("s2", "tool_call", toolName = "update_plan", toolCallId = "c2",
                    toolArgs = """{"operations":[{"action":"set_status","stepId":"1","payload":{"status":"done"}}]}"""),
                ExecutionStep("r2", "tool_result", toolName = "update_plan", toolCallId = "c2", content = """{"applied":1,"currentPlan":{"currentFocusStepId":""}}"""),
                ExecutionStep("s3", "tool_call", toolName = "shell_exec", toolCallId = "c3", toolArgs = """{"command":"ls"}"""),
            )),
        )
        val records = planCallRecords(messages)
        assertThat(records.map { it.key to it.name }).containsExactly("m1::s1" to "initialize_plan", "m2::s2" to "update_plan").inOrder()
        val snapshots = planCallSnapshots(messages)
        assertThat(planCardModel(snapshots.getValue("m2::s2"))!!.completedTitles).containsExactly("环境与空间检查")
    }

    // ── 后台任务 ──

    private val runningJob = """{"runtime":"linux","runner":"linux","job_id":"job-86c294b8-ab90","state":"running","exit_code":null,
        "summary":"cp -a /src /dst","elapsed_seconds":723,"started_at":"2026-11-01T08:00:00Z","log_reference":"job-log:job-86c294b8-ab90",
        "keep_alive":"service","log_tail":"line1\nline2\n\u001B[31mcp: Permission denied\u001B[0m\r\n\n","log_truncated":false}"""

    @Test fun `任务结果解析状态时长与日志末尾`() {
        val model = parseJobCard(runningJob)!!
        assertThat(model.jobId).isEqualTo("job-86c294b8-ab90")
        assertThat(model.running).isTrue()
        assertThat(model.exitCode).isNull()
        assertThat(model.elapsedSeconds).isEqualTo(723)
        assertThat(model.startedAtMillis).isEqualTo(java.time.Instant.parse("2026-11-01T08:00:00Z").toEpochMilli())
        assertThat(model.logLines).containsExactly("line1", "line2", "cp: Permission denied").inOrder()
        assertThat(model.logTruncated).isFalse()
        assertThat(model.logReference).isEqualTo("job-log:job-86c294b8-ab90")
    }

    @Test fun `日志只保留末尾若干行并截断超长行`() {
        val tail = (1..20).joinToString("\\n") { "row$it" } + "\\n" + "x".repeat(400)
        val model = parseJobCard("""{"state":"exited","exit_code":1,"log_tail":"$tail"}""", maxLines = 4)!!
        assertThat(model.logLines.take(3)).containsExactly("row18", "row19", "row20").inOrder()
        assertThat(model.logLines.last()).hasLength(240)
        assertThat(model.logLines.last()).endsWith("…")
        assertThat(model.logTruncated).isTrue()
        assertThat(jobStateText(model.state, model.exitCode)).isEqualTo(ToolText(R.string.tool_card_job_state_exited))
        assertThat(jobStateIsFailure(model.state, model.exitCode)).isTrue()
        assertThat(jobStateText("exited", 0)).isEqualTo(ToolText(R.string.tool_card_job_state_completed))
        assertThat(jobStateText("weird", null)).isEqualTo(ToolText(R.string.tool_card_job_state_other, listOf("weird")))
    }

    @Test fun `不是任务状态的结果不出任务卡`() {
        assertThat(parseJobCard("""{"error_code":"JOB_NOT_FOUND","guidance":"x"}""")).isNull()
        assertThat(parseJobCard("plain text")).isNull()
        assertThat(parseJobCard(null)).isNull()
        assertThat(usesJobCard("job_status")).isTrue()
        assertThat(usesJobCard("job_wait")).isTrue()
        assertThat(usesJobCard("job_stop")).isFalse()
    }

    @Test fun `任务摘要优先短标题其次命令最后任务号`() {
        val model = parseJobCard(runningJob)!!
        val action = ToolText(R.string.chat_step_job_status_name)
        val running = ToolText(R.string.tool_card_job_state_running)
        assertThat(jobSummaryText(CommandStepKind.STATUS, "复制 Personal-Archive", model))
            .isEqualTo(ToolText(R.string.tool_card_job_summary_elapsed, listOf(action, "复制 Personal-Archive", running, "12:03")))
        assertThat(jobSummaryText(CommandStepKind.STATUS, null, model).args[1]).isEqualTo("cp -a /src /dst")
        val bare = model.copy(command = null, elapsedSeconds = null)
        assertThat(jobSummaryText(CommandStepKind.WAIT, null, bare)).isEqualTo(ToolText(
            R.string.tool_card_job_summary,
            listOf(ToolText(R.string.chat_step_job_wait_name), ToolText(R.string.chat_step_job_unknown, listOf("86c294b8")), running),
        ))
    }

    @Test fun `已运行时长按分秒或时分秒显示`() {
        assertThat(jobElapsedClock(0)).isEqualTo("0:00")
        assertThat(jobElapsedClock(723)).isEqualTo("12:03")
        assertThat(jobElapsedClock(3_725)).isEqualTo("1:02:05")
    }

    // ── 通用字段 ──

    @Test fun `对象按字段名与值逐行列出`() {
        val rows = toolFieldRows("""{"url":"https://docs.github.com","max_length":20000,"raw":true,"note":null}""")!!
        assertThat(rows).containsExactly(
            ToolFieldRow("url", "https://docs.github.com"),
            ToolFieldRow("max_length", "20000"),
            ToolFieldRow("raw", "true"),
            ToolFieldRow("note", "null"),
        ).inOrder()
    }

    @Test fun `数组与嵌套对象短时紧凑成一行长时缩进展开`() {
        val rows = toolFieldRows("""{"tags":["a","b",3],"empty":[],"opt":{"k":1},
            "meta":{"title":"About authentication to GitHub","length":18402,"source":"https://docs.github.com/en/authentication"},
            "items":[{"id":1,"name":"first item with a fairly long name"},{"id":2,"name":"second item with a long name"}]}""")!!
        assertThat(rows.take(3)).containsExactly(
            ToolFieldRow("tags", "a, b, 3"),
            ToolFieldRow("empty", "[]"),
            ToolFieldRow("opt", """{"k":1}"""),
        ).inOrder()
        assertThat(rows).contains(ToolFieldRow("meta", "", depth = 0, isHeader = true))
        assertThat(rows).contains(ToolFieldRow("length", "18402", depth = 1))
        assertThat(rows).contains(ToolFieldRow("items", "", depth = 0, isHeader = true))
        assertThat(rows).contains(ToolFieldRow("[1]", """{"id":1,"name":"first item with a fairly long name"}""", depth = 1))
    }

    @Test fun `超长与多行值标记为可折叠`() {
        val long = "x".repeat(LONG_FIELD_CHARS + 1)
        val rows = toolFieldRows("""{"content":"$long","text":"a\nb","short":"ok"}""")!!
        assertThat(rows.map { it.key to it.isLong }).containsExactly("content" to true, "text" to true, "short" to false).inOrder()
    }

    @Test fun `非 JSON 对象时退回原文`() {
        assertThat(toolFieldRows("纯文本结果")).isNull()
        assertThat(toolFieldRows("""[{"a":1}]""")).isNull()
        assertThat(toolFieldRows("{}")).isNull()
        assertThat(toolFieldRows("""{"a":""")).isNull()
        assertThat(toolFieldRows(null)).isNull()
    }

    // ── 完整结果原文 ──

    private val notice = com.promenar.infinity.data.browser.BROWSER_UNTRUSTED_NOTICE

    @Test fun `完整结果按缩进排版JSON并去掉浏览器不可信声明`() {
        val raw = toolRawText("""{"goal":"g","tree":[{"id":"s1"}]}""", listOf("$notice\n385", """{"currentFocusStepId":"s1"}"""))!!
        assertThat(raw.arguments).isEqualTo("{\n    \"goal\": \"g\",\n    \"tree\": [\n        {\n            \"id\": \"s1\"\n        }\n    ]\n}")
        assertThat(raw.results).containsExactly("385", "{\n    \"currentFocusStepId\": \"s1\"\n}").inOrder()
    }

    @Test fun `非JSON原文保持原样且全空时没有完整结果`() {
        assertThat(toolRawText("parameter", listOf("找到 3 条结果", "{不是 JSON"))).isEqualTo(ToolRawText("parameter", listOf("找到 3 条结果", "{不是 JSON")))
        assertThat(toolRawText(" ", listOf("", notice))).isNull()
        assertThat(toolRawText(null, emptyList())).isNull()
    }

    @Test fun `命令卡只取命令或代码本身`() {
        assertThat(commandArgumentText("""{"command":"ls -la","timeout_seconds":30}""")).isEqualTo("ls -la")
        assertThat(commandArgumentText("""{"code":"print(1)"}""")).isEqualTo("print(1)")
        assertThat(commandArgumentText("""{"job_id":"j1"}""")).isNull()
        assertThat(commandArgumentText("ls")).isNull()
    }

    @Test fun `识别合法JSON对象与数组`() {
        assertThat(looksLikeJson("""[{"title":"a"}]""")).isTrue()
        assertThat(looksLikeJson(""" {"a":1} """)).isTrue()
        assertThat(looksLikeJson("{不是 JSON")).isFalse()
        assertThat(looksLikeJson("42")).isFalse()
    }
}
