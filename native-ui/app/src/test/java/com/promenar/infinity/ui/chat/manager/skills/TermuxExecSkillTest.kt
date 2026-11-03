package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.termux.TermuxCommandRequest
import com.promenar.infinity.data.termux.TermuxCommandResult
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class TermuxExecSkillTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }
    private fun args(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test fun `前台托管结果保留尾部截断超时与错误状态`() {
        val record = com.promenar.infinity.data.jobs.BackgroundJobRecord("job", "s", "root", "TERMUX", "command",
            startedAtMillis = 0, maxRuntimeMinutes = 1, onExit = "none", logWorkspacePath = "jobs/job.log", runDirectory = "")
        val tail = com.promenar.infinity.data.jobs.JobLogTail("最后输出", true)
        val running = termuxForegroundResult(com.promenar.infinity.data.jobs.BackgroundJobStatus(record, tail))
        assertThat(running.stdoutTruncated).isTrue()
        assertThat(running.stdoutOriginalLength).isNull()
        assertThat(running.toolStatus).isEqualTo("success")
        val timeout = termuxForegroundResult(com.promenar.infinity.data.jobs.BackgroundJobStatus(record.copy(state = "TIMED_OUT"), tail))
        assertThat(timeout.timedOut).isTrue()
        assertThat(timeout.errorCode).isEqualTo("TERMUX_TIMEOUT")
        assertThat(timeout.toolStatus).isEqualTo("error")
        val failed = termuxForegroundResult(com.promenar.infinity.data.jobs.BackgroundJobStatus(record.copy(state = "EXITED", exitCode = 7), tail))
        assertThat(failed.toolStatus).isEqualTo("error")
    }

    @Test fun `工具为PROCESS且参数保留stdin默认超时并回传结构化结果`() = runTest {
        var captured: TermuxCommandRequest? = null
        val skill = TermuxExecSkill({ captured = it; TermuxCommandResult(stdout = "done", exitCode = 0) }, { true })
        assertThat(skill.risk).isEqualTo(ToolRisk.PROCESS)
        val result = skill.execute(args("""{"command":"cat","stdin":"text\\n","workdir":"/tmp"}"""), context)
        assertThat(captured!!.command).isEqualTo("cat")
        assertThat(captured!!.timeoutSeconds).isEqualTo(60)
        assertThat(captured!!.workdir).isEqualTo("/tmp")
        assertThat(captured!!.stdin).isEqualTo("text\\n")
        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("done")
    }

    @Test fun `关闭集成和非法参数不派发且超时作为工具错误回传`() = runTest {
        var dispatched = 0
        val runner: suspend (TermuxCommandRequest) -> TermuxCommandResult = {
            dispatched++
            TermuxCommandResult(errorCode = "TERMUX_TIMEOUT", timedOut = true)
        }
        val disabled = TermuxExecSkill(runner, { false })
        assertThat(disabled.execute(args("""{"command":"id"}"""), context).status).isEqualTo("error")
        val enabled = TermuxExecSkill(runner, { true })
        listOf("{}", """{"command":"id","timeout_seconds":601}""", """{"command":"id","stdin":42}""", """{"command":"id","host":"remote"}""").forEach {
            assertThat(enabled.execute(args(it), context).status).isEqualTo("error")
        }
        assertThat(dispatched).isEqualTo(0)
        val timeout = enabled.execute(args("""{"command":"id","timeout_seconds":600}"""), context)
        assertThat(dispatched).isEqualTo(1)
        assertThat(timeout.status).isEqualTo("error")
        assertThat(timeout.content).contains("TERMUX_TIMEOUT")
    }

    @Test fun `模型常见的空工作目录与波浪号按Termux主目录处理`() = runTest {
        val captured = mutableListOf<TermuxCommandRequest>()
        val skill = TermuxExecSkill({ captured += it; TermuxCommandResult(stdout = "ok\n", exitCode = 0, err = -1) }, { true })
        listOf(
            """{"command":"echo ok","workdir":""}""",
            """{"command":"echo ok","workdir":"  "}""",
            """{"command":"echo ok","workdir":"~"}""",
            """{"command":"echo ok","workdir":"${'$'}HOME"}""",
            """{"command":"echo ok","workdir":"","stdin":"","timeout_seconds":60}""",
        ).forEach { assertThat(skill.execute(args(it), context).status).isEqualTo("success") }
        assertThat(captured.map { it.workdir }).containsExactly(null, null, null, null, null)
        assertThat(captured.last().stdin).isEqualTo("")
        val nested = skill.execute(args("""{"command":"ls","workdir":"~/project"}"""), context)
        assertThat(nested.status).isEqualTo("success")
        assertThat(captured.last().workdir).isEqualTo("~/project")
    }

    @Test fun `参数无效时结果指明具体字段且不派发`() = runTest {
        var dispatched = 0
        val skill = TermuxExecSkill({ dispatched++; TermuxCommandResult(exitCode = 0, err = -1) }, { true })
        val relative = skill.execute(args("""{"command":"ls","workdir":"project"}"""), context)
        assertThat(relative.status).isEqualTo("error")
        assertThat(relative.content).contains("TERMUX_INVALID_ARGUMENTS")
        assertThat(relative.content).contains("工作目录")
        val timeout = skill.execute(args("""{"command":"ls","timeout_seconds":0}"""), context)
        assertThat(timeout.content).contains("超时")
        val blank = skill.execute(args("""{"command":"  "}"""), context)
        assertThat(blank.content).contains("命令")
        assertThat(dispatched).isEqualTo(0)
    }
}
