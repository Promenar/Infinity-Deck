package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import org.junit.jupiter.api.Test

class ToolInvocationPresentationTest {
    @Test
    fun `本轮仍在进行时未完成工具展示等待而非运行或成功`() {
        val invocations = toolInvocations(listOf(
            call("a", "1", "web_search", 1_000),
            call("b", "2", "read_file", 1_100), result("br", "2", 1_500),
        ))
        val display = toolGroupDisplaySummary(invocations, isExecuting = false, turnActive = true)
        assertThat(display.status).isEqualTo(ToolSummaryStatus.PENDING)
        assertThat(display.pendingCount).isEqualTo(1)
        assertThat(display.runningAction).isNull()
        assertThat(display.durationMs).isNull()
        assertThat(toolGroupSummary(invocations).status).isEqualTo(ToolSummaryStatus.RUNNING)
    }

    @Test
    fun `本轮结束后未完成工具展示已中断且只计未完成调用`() {
        val invocations = toolInvocations(listOf(
            call("a", "1", "web_search", 1_000),
            call("b", "2", "read_file", 1_100), result("br", "2", 1_500),
            call("c", "3", "ssh_exec", 1_600),
        ))
        val display = toolGroupDisplaySummary(invocations, isExecuting = false, turnActive = false)
        assertThat(display.status).isEqualTo(ToolSummaryStatus.INTERRUPTED)
        assertThat(display.pendingCount).isEqualTo(2)
        assertThat(display.runningAction).isNull()
        // 正在执行时仍是运行态，不受本轮状态影响。
        assertThat(toolGroupDisplaySummary(invocations, isExecuting = true, turnActive = false).status)
            .isEqualTo(ToolSummaryStatus.RUNNING)
        // 全部完成的组不会显示为中断。
        val done = toolInvocations(listOf(call("d", "4", "read_file", 1_000), result("dr", "4", 1_100)))
        assertThat(toolGroupDisplaySummary(done, isExecuting = false, turnActive = false).status)
            .isEqualTo(ToolSummaryStatus.COMPLETED)
    }

    @Test
    fun `活动工具组保留最后未完成类别且等待数量不包含已完成调用`() {
        val invocations = toolInvocations(listOf(
            call("a", "1", "ssh_exec", 1_000),
            call("b", "2", "web_search", 1_100),
            call("c", "3", "read_file", 1_200), result("cr", "3", 1_500),
        ))
        val display = toolGroupDisplaySummary(invocations, isExecuting = true)
        assertThat(display.status).isEqualTo(ToolSummaryStatus.RUNNING)
        assertThat(display.runningAction).isEqualTo(ToolSummaryAction.WEB_SEARCH)
        assertThat(display.pendingCount).isEqualTo(2)
    }

    @Test
    fun `历史等待组保留失败数量且完成组不会变成等待`() {
        val steps = listOf(
            call("a", "1", "ssh_exec", 1_000), result("ar", "1", 1_200).copy(type = "error"),
            call("b", "2", "web_search", 1_300),
        )
        val pending = toolGroupDisplaySummary(toolInvocations(steps), isExecuting = false)
        assertThat(pending.status).isEqualTo(ToolSummaryStatus.PENDING)
        assertThat(pending.failedCount).isEqualTo(1)
        assertThat(pending.pendingCount).isEqualTo(1)
        val finished = toolGroupDisplaySummary(toolInvocations(steps + result("br", "2", 1_500)), isExecuting = false)
        assertThat(finished.status).isEqualTo(ToolSummaryStatus.FAILED)
        assertThat(finished.pendingCount).isEqualTo(0)
    }

    @Test
    fun `工具摘要严格按规格映射所有工具且保留检索与实用子类别`() {
        val cases = listOf(
            listOf("termux_exec", "ssh_exec", "ssh_session_open", "ssh_session_send", "ssh_session_read", "ssh_session_close", "run_script", "exec_js", "python_sandbox",
                "job_status", "job_wait", "job_stop", "job_list") to ToolSummaryAction.COMMAND,
            listOf("web_search", "search_tavily", "search_searxng") to ToolSummaryAction.WEB_SEARCH,
            listOf("web_fetch") to ToolSummaryAction.WEB_READ,
            listOf("read_file", "list_files", "search_files", "read_tool_output", "read_skill_file") to ToolSummaryAction.FILE_READ,
            listOf("write_file", "create_file", "patch_file", "diff_file", "move_file", "delete_file", "create_directory") to ToolSummaryAction.FILE_EDIT,
            listOf("sftp_upload", "sftp_download") to ToolSummaryAction.FILE_TRANSFER,
            listOf("knowledge_search") to ToolSummaryAction.KNOWLEDGE_SEARCH,
            listOf("memory_search") to ToolSummaryAction.MEMORY_SEARCH,
            listOf("generate_image", "image_generation") to ToolSummaryAction.IMAGE,
            listOf("view_media") to ToolSummaryAction.MEDIA_VIEW,
            listOf("media_fetch") to ToolSummaryAction.MEDIA_FETCH,
            listOf("initialize_plan", "update_plan", "get_plan", "drop_plan") to ToolSummaryAction.PLAN,
            listOf("current_time") to ToolSummaryAction.TIME,
            listOf("calculator") to ToolSummaryAction.CALCULATE,
            listOf("schedule_create", "schedule_list", "schedule_pause", "schedule_resume", "schedule_cancel") to ToolSummaryAction.SCHEDULE,
            listOf("activate_skill") to ToolSummaryAction.SKILL,
        )
        cases.forEach { (names, expected) ->
            names.forEach { name ->
                val summary = toolGroupSummary(completedInvocations(name))
                assertThat(summary.parts).containsExactly(ToolSummaryPart(expected, 1))
                assertThat(summary.count).isEqualTo(1)
                assertThat(summary.status).isEqualTo(ToolSummaryStatus.COMPLETED)
                assertThat(summary.durationMs).isEqualTo(400L)
            }
        }
    }

    @Test
    fun `未知名称与MCP工具不能凭子串归入内置类别`() {
        val summary = toolGroupSummary(completedInvocations("search", "mcp__files__read_file", "Read_File", "custom_exec"))
        assertThat(summary.parts).containsExactly(ToolSummaryPart(ToolSummaryAction.OTHER, 4))
        assertThat(summary.categories).containsExactly(ToolSummaryCategory.OTHER)
    }

    @Test
    fun `两类别按首次出现顺序汇总并且统计每次调用`() {
        val summary = toolGroupSummary(completedInvocations("read_file", "ssh_exec", "list_files", "exec_js"))
        assertThat(summary.parts).containsExactly(
            ToolSummaryPart(ToolSummaryAction.FILE_READ, 2), ToolSummaryPart(ToolSummaryAction.COMMAND, 2),
        ).inOrder()
        assertThat(summary.useGenericSummary).isFalse()
        assertThat(summary.count).isEqualTo(4)
    }

    @Test
    fun `三类别使用总调用数摘要而非列出内部工具`() {
        val summary = toolGroupSummary(completedInvocations("read_file", "web_search", "calculator", "read_file"))
        assertThat(summary.useGenericSummary).isTrue()
        assertThat(summary.count).isEqualTo(4)
        assertThat(summary.categories).hasSize(3)
    }

    @Test
    fun `知识与记忆检索和时间与计算分别保留动作但只算两大类别`() {
        val summary = toolGroupSummary(completedInvocations("memory_search", "calculator", "knowledge_search", "current_time"))
        assertThat(summary.categories).containsExactly(ToolSummaryCategory.RETRIEVAL, ToolSummaryCategory.UTILITY).inOrder()
        assertThat(summary.parts.map { it.action }).containsExactly(
            ToolSummaryAction.MEMORY_SEARCH, ToolSummaryAction.KNOWLEDGE_SEARCH,
            ToolSummaryAction.CALCULATE, ToolSummaryAction.TIME,
        ).inOrder()
        assertThat(summary.useGenericSummary).isFalse()
    }

    @Test
    fun `进行中取最后一个未完成调用而非最后一个事件或首个调用`() {
        val invocations = toolInvocations(listOf(
            call("a", "1", "ssh_exec", 1_000),
            call("b", "2", "web_search", 1_100),
            call("c", "3", "read_file", 1_200), result("cr", "3", 1_300),
            call("d", "4", "calculator", 1_400), result("dr", "4", 1_500),
        ))
        val summary = toolGroupSummary(invocations)
        assertThat(summary.status).isEqualTo(ToolSummaryStatus.RUNNING)
        assertThat(summary.runningAction).isEqualTo(ToolSummaryAction.WEB_SEARCH)
        assertThat(summary.count).isEqualTo(4)
    }

    @Test
    fun `失败数量按调用而非错误事件计数且进行状态优先`() {
        val steps = listOf(
            call("a", "1", "ssh_exec", 1_000), result("ar", "1", 1_400).copy(type = "error"),
            result("ar2", "1", 1_500).copy(type = "error"),
            call("b", "2", "read_file", 1_600), result("br", "2", 2_000).copy(type = "error"),
        )
        val summary = toolGroupSummary(toolInvocations(steps))
        assertThat(summary.failedCount).isEqualTo(2)
        assertThat(summary.status).isEqualTo(ToolSummaryStatus.FAILED)
        assertThat(summary.durationMs).isEqualTo(1_000L)
        assertThat(toolGroupSummary(toolInvocations(steps + call("c", "3", "web_search", 2_100))).status)
            .isEqualTo(ToolSummaryStatus.RUNNING)
    }

    @Test
    fun `空工具组没有运行状态耗时或虚构调用`() {
        val summary = toolGroupSummary(emptyList())
        assertThat(summary.parts).isEmpty()
        assertThat(summary.categories).isEmpty()
        assertThat(summary.status).isEqualTo(ToolSummaryStatus.EMPTY)
        assertThat(summary.count).isEqualTo(0)
        assertThat(summary.failedCount).isEqualTo(0)
        assertThat(summary.runningAction).isNull()
        assertThat(summary.durationMs).isNull()
    }

    @Test
    fun `媒体动作按结果中的媒体个数计数，旧结果按调用计`() {
        fun ref(path: String, kind: MediaKind) = MediaRef(path, "application/octet-stream", kind, 1)
        val steps = listOf(
            call("g", "gen", "image_generation", 1000),
            result("g-r", "gen", 1400).copy(media = listOf(
                ref("/media/a.png", MediaKind.IMAGE), ref("/media/b.png", MediaKind.IMAGE), ref("/media/c.png", MediaKind.IMAGE),
            )),
            call("v", "view", "view_media", 1500),
            result("v-r", "view", 1700).copy(media = listOf(ref("/media/a.png", MediaKind.IMAGE), ref("/media/d.mp4", MediaKind.VIDEO))),
            call("legacy", "old", "image_generation", 1800), result("legacy-r", "old", 1900),
        )
        val summary = toolGroupSummary(toolInvocations(steps))
        assertThat(summary.categories).containsExactly(ToolSummaryCategory.MEDIA)
        assertThat(summary.parts).containsExactly(
            ToolSummaryPart(ToolSummaryAction.IMAGE, 4), ToolSummaryPart(ToolSummaryAction.MEDIA_VIEW, 2),
        ).inOrder()
        assertThat(summary.count).isEqualTo(3)
    }

    @Test
    fun `失败的媒体调用不计入产出数量，只计入失败`() {
        fun ref(path: String) = MediaRef(path, "image/png", MediaKind.IMAGE, 1)
        fun error(id: String, callId: String, time: Long) =
            ExecutionStep(id, "error", toolCallId = callId, content = "HTTP 400 unknown_parameter", timestamp = time)
        val failedOnly = toolGroupSummary(toolInvocations(listOf(call("g", "gen", "generate_image", 1000), error("g-r", "gen", 1400))))
        assertThat(failedOnly.parts).containsExactly(ToolSummaryPart(ToolSummaryAction.IMAGE, 0))
        assertThat(failedOnly.failedCount).isEqualTo(1)

        val mixed = toolGroupSummary(toolInvocations(listOf(
            call("g", "gen", "generate_image", 1000), error("g-r", "gen", 1400),
            call("g2", "gen2", "generate_image", 1500), result("g2-r", "gen2", 1900).copy(media = listOf(ref("/media/a.png"))),
            call("v", "view", "view_media", 2000), error("v-r", "view", 2100),
        )))
        assertThat(mixed.parts).containsExactly(
            ToolSummaryPart(ToolSummaryAction.IMAGE, 1), ToolSummaryPart(ToolSummaryAction.MEDIA_VIEW, 0),
        ).inOrder()
        assertThat(mixed.failedCount).isEqualTo(2)
    }

    @Test
    fun `转入后台的生图与下载不计产出，单独计数`() {
        val started = """{"runtime":"in_app","job_id":"job-7","state":"running","guidance":"任务已登记","yielded_after_seconds":20}"""
        fun yielded(name: String, callId: String, time: Long) = listOf(
            call("$callId-c", callId, name, time),
            result("$callId-r", callId, time + 20_000).copy(content = started),
        )
        val invocations = toolInvocations(yielded("generate_image", "gen", 1000) + yielded("media_fetch", "fetch", 2000))
        assertThat(invocations.map(::backgroundedJobId)).containsExactly("job-7", "job-7").inOrder()
        val summary = toolGroupSummary(invocations)
        assertThat(summary.parts).containsExactly(
            ToolSummaryPart(ToolSummaryAction.IMAGE, 0), ToolSummaryPart(ToolSummaryAction.MEDIA_FETCH, 0),
        ).inOrder()
        assertThat(summary.backgroundedCount).isEqualTo(2)
        assertThat(summary.failedCount).isEqualTo(0)

        // 前台完成的生图带媒体，任务查询工具与普通文本结果都不算转入后台。
        val done = toolInvocations(listOf(
            call("g", "gen", "generate_image", 1000),
            result("g-r", "gen", 1400).copy(content = started, media = listOf(MediaRef("/media/a.png", "image/png", MediaKind.IMAGE, 1))),
            call("w", "wait", "job_wait", 1500), result("w-r", "wait", 1600).copy(content = started),
            call("t", "text", "generate_image", 1700), result("t-r", "text", 1800),
        ))
        assertThat(done.map(::backgroundedJobId)).containsExactly(null, null, null).inOrder()
        assertThat(toolGroupSummary(done).backgroundedCount).isEqualTo(0)
    }

    private fun completedInvocations(vararg names: String) = toolInvocations(names.flatMapIndexed { index, name ->
        val start = 1_000L + index * 500L
        listOf(call("c$index", "$index", name, start), result("r$index", "$index", start + 400))
    })

    @Test
    fun `审批决定不能增加工具数量或阻止工具完成`() {
        val invocations = toolInvocations(listOf(
            ExecutionStep("approval", "intervention_result", content = "已批准"),
            call("a", "one", "write_file", 1000), result("r", "one", 1400),
        ))
        assertThat(invocations).hasSize(1)
        assertThat(invocations.single().complete).isTrue()
        assertThat(toolGroupDurationMs(invocations)).isEqualTo(400)
    }

    @Test
    fun `参数和结果按调用标识配对且同名多次调用不会合并`() {
        val steps = listOf(
            call("a", "one", "search", 1000), call("b", "two", "search", 1500),
            result("b-result", "two", 1700), result("a-result", "one", 2400),
        )
        val invocations = toolInvocations(steps)
        assertThat(invocations).hasSize(2)
        assertThat(invocations[0].durationMs).isEqualTo(1400)
        assertThat(invocations[1].durationMs).isEqualTo(200)
        assertThat(toolGroupDurationMs(invocations)).isEqualTo(1400)
    }

    @Test
    fun `重复事件不增加调用数量`() {
        val start = call("a", "one", "read_file", 1000)
        val end = result("end", "one", 1200)
        val invocations = toolInvocations(listOf(start, start, end, end))
        assertThat(invocations).hasSize(1)
        assertThat(invocations.single().steps).hasSize(2)
    }

    @Test
    fun `旧结果用步骤标识配对且不重复计数`() {
        val start = call("legacy", null, "read_file", 1000)
        val invocations = toolInvocations(listOf(start, result("res_legacy", null, 1400)))
        assertThat(invocations).hasSize(1)
        assertThat(invocations.single().name).isEqualTo("read_file")
        assertThat(invocations.single().durationMs).isEqualTo(400)
    }

    @Test
    fun `名称相同的无标识历史调用按顺序配对`() {
        val invocations = toolInvocations(listOf(
            call("first", null, "search", 1000), call("second", null, "search", 1500),
            result("r1", null, 1800).copy(toolName = "search"),
            result("r2", null, 2000).copy(toolName = "search"),
        ))
        assertThat(invocations.map { it.durationMs }).containsExactly(800L, 500L).inOrder()
    }

    @Test
    fun `缺少调用或结果时间时不猜测完成耗时`() {
        assertThat(toolGroupDurationMs(toolInvocations(listOf(result("r", null, 1800))))).isNull()
        assertThat(toolGroupDurationMs(toolInvocations(listOf(call("s", null, "search", 1000))))).isNull()
        val reversed = listOf(call("s", "one", "search", 2000), result("r", "one", 1000))
        assertThat(toolGroupDurationMs(toolInvocations(reversed))).isNull()
    }

    @Test
    fun `进行中的工具组显示从首个调用起已用时且不接受倒退时钟`() {
        val invocations = toolInvocations(listOf(call("s", "one", "search", 1000)))
        assertThat(toolGroupDurationMs(invocations, nowMs = 2600)).isEqualTo(1600)
        assertThat(toolGroupDurationMs(invocations, nowMs = 900)).isNull()
    }

    @Test
    fun `工具信封缺失名称时从嵌套function与tool_name取名`() {
        val function = ExecutionStep("a", "tool_call", toolArgs = """{"function":{"name":"web_search","arguments":{}}}""")
        val named = ExecutionStep("b", "tool_result", data = """{"tool_name":"read_file"}""")
        assertThat(inferredToolName(function)).isEqualTo("web_search")
        assertThat(inferredToolName(named)).isEqualTo("read_file")
    }

    @Test
    fun `普通参数的name和损坏JSON不会冒充工具名`() {
        val parameter = ExecutionStep("a", "tool_call", toolArgs = """{"name":"笔记.md"}""")
        val invalid = ExecutionStep("b", "tool_call", data = "[invalid")
        assertThat(inferredToolName(parameter)).isNull()
        assertThat(inferredToolName(invalid)).isNull()
    }

    @Test
    fun `调用结果中的真实名称可用于显示且失败保持可观察`() {
        val start = call("a", "one", "", 1000)
        val end = result("r", "one", 1200).copy(type = "error", toolName = "web_fetch")
        val invocation = toolInvocations(listOf(start, end)).single()
        assertThat(invocation.name).isEqualTo("web_fetch")
        assertThat(invocation.failed).isTrue()
        assertThat(invocation.complete).isTrue()
        assertThat(invocation.durationMs).isEqualTo(200)
    }

    @Test
    fun `关键数据变化只解析结果对象中的字符串数组`() {
        assertThat(parseRuntimeChanges(null)).isEmpty()
        assertThat(parseRuntimeChanges("完成")).isEmpty()
        assertThat(parseRuntimeChanges("[\"databases/a.db\"]")).isEmpty()
        assertThat(parseRuntimeChanges("{\"exit_code\":0}")).isEmpty()
        assertThat(parseRuntimeChanges("{\"runtime_changes\":\"databases/a.db\"}")).isEmpty()
        assertThat(parseRuntimeChanges("{\"runtime_changes\":[\"databases/a.db (replaced)\",\"\",3,null]}"))
            .containsExactly("databases/a.db (replaced)", "3")
    }

    @Test
    fun `命令与后台任务结果的关键数据变化合并去重，其它工具不报告`() {
        fun withChanges(id: String, callId: String, vararg paths: String) = result(id, callId, 2_000).copy(
            content = "{\"runtime_changes\":[${paths.joinToString(",") { "\"$it\"" }}]}",
        )
        val invocations = toolInvocations(listOf(
            call("a", "1", "shell_exec", 1_000), withChanges("ar", "1", "shared_prefs/x.xml (deleted)"),
            call("b", "2", "job_status", 1_100), withChanges("br", "2", "shared_prefs/x.xml (deleted)", "files/agent_skills/s (added)"),
            call("c", "3", "read_file", 1_200), withChanges("cr", "3", "databases/a.db (replaced)"),
        ))
        assertThat(invocations.last().runtimeChanges).isEmpty()
        assertThat(toolGroupRuntimeChanges(invocations))
            .containsExactly("shared_prefs/x.xml (deleted)", "files/agent_skills/s (added)").inOrder()
    }

    private fun call(id: String, callId: String?, name: String, time: Long): ExecutionStep =
        ExecutionStep(id, "tool_call", toolName = name, toolCallId = callId, toolArgs = "{}", timestamp = time)

    private fun result(id: String, callId: String?, time: Long): ExecutionStep =
        ExecutionStep(id, "tool_result", toolCallId = callId, content = "完成", timestamp = time)

    @org.junit.Test
    fun `工具耗时不足一秒显示毫秒否则显示一位小数的秒`() {
        com.google.common.truth.Truth.assertThat(toolDurationText(999)).isEqualTo(
            ToolDurationText(com.promenar.infinity.R.string.chat_tool_duration, listOf(999L)),
        )
        com.google.common.truth.Truth.assertThat(toolDurationText(1_400)).isEqualTo(
            ToolDurationText(com.promenar.infinity.R.string.chat_tool_duration_seconds, listOf("1.4")),
        )
    }
}
