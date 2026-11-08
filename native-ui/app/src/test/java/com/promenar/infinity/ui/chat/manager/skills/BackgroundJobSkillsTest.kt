package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.exec.*
import com.promenar.infinity.data.jobs.*
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test

class BackgroundJobSkillsTest {
    @Test fun `工具风险和会话隔离与列表不含日志`()=runBlocking<Unit> {
        val root=Files.createTempDirectory("jobs-skill").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val manager=BackgroundJobManager(scope,BackgroundJobStore(File(root,"store")),File(root,"runs"),
            BuiltinCommandRunner(listOf("/usr/bin/perl","-e","setpgrp(0,0); exec @ARGV"),"/bin/sh",ProcessGroupSignaller{pid,sig->ProcessBuilder("/bin/kill","-$sig","-$pid").start().waitFor()},{File(root,"scratch")},killGraceMillis=100,runtimeDataMonitor=RuntimeDataMonitor(root)),
            prepareWorkspace={BackgroundJobLocation(root,root,false,false)},prepareCommand={_,source,_,location,minutes->PreparedBackgroundCommand(BuiltinCommandRequest(listOf("/bin/sh","-c",source),location.commandDirectory,mapOf("PATH" to "/usr/bin:/bin"),timeoutSeconds=minutes*60))})
        try {
            val tools=backgroundJobTools(manager).associateBy{it.id}
            assertThat(tools.values.map{it.risk}).containsExactly(ToolRisk.SESSION_STATE,ToolRisk.SESSION_STATE,ToolRisk.SESSION_STATE,ToolRisk.SESSION_STATE,ToolRisk.PROCESS)
            val waitSchema = Json.parseToJsonElement(tools.getValue("job_wait").parametersSchema).jsonObject
            assertThat(waitSchema.getValue("properties").jsonObject.keys).containsAtLeast("until_output", "until_file")
            val own=context("own");val other=context("other")
            val skill = File(root,"files/agent_skills/foo/SKILL.md").apply { parentFile.mkdirs(); writeText("before") }
            val result=runBackgroundExec("shell_exec","""{"type":"object","properties":{"command":{"type":"string"},$BACKGROUND_EXEC_SCHEMA},"required":["command"],"additionalProperties":false}""",buildJsonObject{put("command","printf changed >> '${skill.path}'");put("background",true)},own,manager,com.promenar.infinity.domain.jobs.BackgroundJobRunner.SHELL,"command")
            assertThat(result.status).isEqualTo("success")
            val id=Json.parseToJsonElement(result.content).jsonObject.getValue("job_id").jsonPrimitive.content
            val rejected=tools.getValue("job_status").execute(buildJsonObject{put("job_id",id)},other)
            assertThat(rejected.content).contains("JOB_NOT_FOUND")
            val listed=tools.getValue("job_list").execute(buildJsonObject{},own)
            assertThat(listed.content).doesNotContain("log_tail")
            val badWait=tools.getValue("job_wait").execute(buildJsonObject{put("job_id",id);put("timeout_seconds",601)},own)
            assertThat(badWait.status).isEqualTo("error")
            val waited = tools.getValue("job_wait").execute(buildJsonObject{put("job_id",id);put("timeout_seconds",5)},own)
            assertThat(Json.parseToJsonElement(waited.content).jsonObject.getValue("runtime_changes").jsonArray.map { it.jsonPrimitive.content })
                .containsExactly("files/agent_skills/foo/SKILL.md")
            val status = tools.getValue("job_status").execute(buildJsonObject{put("job_id",id)},own)
            assertThat(status.content).contains("runtime_changes")
            assertThat(Json.parseToJsonElement(status.content).jsonObject).containsKey("last_output_seconds_ago")
            assertThat(Json.parseToJsonElement(waited.content).jsonObject.getValue("wait_reason").jsonPrimitive.content).isEqualTo("terminal")
        } finally{scope.cancel();root.deleteRecursively()}
    }
    @Test fun `后台布尔字符串可转换且错误指出具体字段`() = runBlocking<Unit> {
        val schema = """{"type":"object","properties":{"command":{"type":"string"},$BACKGROUND_EXEC_SCHEMA},"required":["command"],"additionalProperties":false}"""
        val converted = runBackgroundExec("shell_exec", schema, buildJsonObject { put("command", "true"); put("background", "True") }, context("own"), null, com.promenar.infinity.domain.jobs.BackgroundJobRunner.SHELL, "command")
        assertThat(converted.content).contains("JOB_UNAVAILABLE")
        val invalid = runBackgroundExec("shell_exec", schema, buildJsonObject { put("command", "true"); put("background", true); put("max_runtime_minutes", "bad") }, context("own"), null, com.promenar.infinity.domain.jobs.BackgroundJobRunner.SHELL, "command")
        assertThat(invalid.content).contains("max_runtime_minutes")
        assertThat(invalid.content).contains("整数")
        assertThat(invalid.content).contains("字符串")
    }

    @Test fun `会话工具描述包含Linux终端交互契约`() {
        val tool = com.promenar.infinity.data.remote.protocol.ProtocolTool(
            function = com.promenar.infinity.data.remote.protocol.ProtocolToolFunction("shell_exec", "在本机用系统 /system/bin/sh 执行命令", "{}"),
            sourceId = "builtin", runtimeToolId = "shell_exec",
        )
        val linux = com.promenar.infinity.data.generation.SessionRunModeToolDefinitions.forSession(tool, com.promenar.infinity.domain.model.SessionRunMode.LINUX).function.description
        assertThat(linux).doesNotContain("/system/bin/sh")
        listOf("伪终端", "15", "job_input", "apk add", "/workspace", "/mnt/<别名>").forEach { assertThat(linux).contains(it) }
        assertThat(com.promenar.infinity.data.generation.SessionRunModeToolDefinitions.forSession(tool, com.promenar.infinity.domain.model.SessionRunMode.BUILTIN).function.description).contains("/system/bin/sh")
    }

    @Test fun `缺少后台服务时不会执行媒体请求`() = runBlocking {
        var executed = false
        val result = runMediaJob("media_fetch", buildJsonObject {}, context("own"), null, 15, "notify", "background", "下载媒体") {
            executed = true; com.promenar.infinity.data.model.ToolResult("test", "unexpected")
        }
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("后台任务服务不可用")
        assertThat(executed).isFalse()
    }

    @Test fun `进程内媒体让出后查询结果与停止`() = runBlocking {
        val root = Files.createTempDirectory("job-media").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val woke = CompletableDeferred<String>()
        val dispatcher = object : com.promenar.infinity.domain.trigger.SystemTurnDispatcher {
            override suspend fun request(request: com.promenar.infinity.domain.trigger.SystemTurnRequest): com.promenar.infinity.domain.trigger.SystemTurnOutcome {
                woke.complete(request.body); return com.promenar.infinity.domain.trigger.SystemTurnOutcome.Completed
            }
        }
        val manager = BackgroundJobManager(scope, BackgroundJobStore(File(root, "store")), File(root, "runs"),
            BuiltinCommandRunner(listOf("/usr/bin/perl", "-e", "setpgrp(0,0); exec @ARGV"), "/bin/sh", ProcessGroupSignaller { _, _ -> }, { File(root, "scratch") }),
            prepareWorkspace = { BackgroundJobLocation(root, root, false, false) },
            prepareCommand = { _, _, _, _, _ -> error("不应调用命令执行器") }, completion = BackgroundJobCompletion({ dispatcher }))
        val ctx = context("own")
        val media = com.promenar.infinity.domain.media.MediaRef("/media/a.png", "image/png", com.promenar.infinity.domain.media.MediaKind.IMAGE, 4)
        val expected = com.promenar.infinity.data.model.ToolResult("fake", "图片 /media/a.png", data = "元数据", media = listOf(media))
        try {
            val immediate = runMediaJob("generate_image", buildJsonObject {}, ctx, manager, 1, "wake", "run_in_background", "生成图片") { expected }
            assertThat(immediate).isEqualTo(expected)
            val release = CompletableDeferred<Unit>()
            val started = runMediaJob("generate_image", buildJsonObject { put("yield_after_seconds", 1) }, ctx, manager, 1, "wake", "run_in_background", "生成图片") {
                release.await(); expected
            }
            val json = Json.parseToJsonElement(started.content).jsonObject
            assertThat(json.getValue("runtime").jsonPrimitive.content).isEqualTo("in_app")
            assertThat(json).containsKey("yielded_after_seconds")
            val jobId = json.getValue("job_id").jsonPrimitive.content
            release.complete(Unit)
            val tools = backgroundJobTools(manager).associateBy { it.id }
            val args = buildJsonObject { put("job_id", jobId); put("timeout_seconds", 5) }
            val waited = tools.getValue("job_wait").execute(args, ctx)
            val status = tools.getValue("job_status").execute(buildJsonObject { put("job_id", jobId) }, ctx)
            for (result in listOf(waited, status)) {
                assertThat(result.content).contains(expected.content)
                assertThat(result.media).isEqualTo(expected.media)
                assertThat(result.data).isEqualTo(expected.data)
            }
            assertThat(withTimeout(5_000) { woke.await() }).contains(expected.content)
            val continued = CompletableDeferred<Unit>()
            val handedOff = CompletableDeferred<String>()
            val turn = launch {
                val returned = runMediaJob("media_fetch", buildJsonObject { put("yield_after_seconds", 1); put("on_exit", "none") }, ctx, manager, 1, "none", "background", "下载媒体") {
                    continued.await(); expected
                }
                handedOff.complete(Json.parseToJsonElement(returned.content).jsonObject.getValue("job_id").jsonPrimitive.content)
                awaitCancellation()
            }
            val handoffId = withTimeout(5_000) { handedOff.await() }
            turn.cancelAndJoin()
            continued.complete(Unit)
            assertThat(manager.waitFor("own", handoffId, 5).record.state).isEqualTo("EXITED")
            val cancelled = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val second = runMediaJob("media_fetch", buildJsonObject { put("background", true) }, ctx, manager, 1, "none", "background", "下载媒体") {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            val secondId = Json.parseToJsonElement(second.content).jsonObject.getValue("job_id").jsonPrimitive.content
            withTimeout(5_000) { entered.await() }
            manager.stop("own", secondId)
            withTimeout(5_000) { cancelled.await() }
            assertThat(manager.status("own", secondId).record.state).isEqualTo("STOPPED")
            val foregroundEntered = CompletableDeferred<Unit>()
            val foregroundCancelled = CompletableDeferred<Unit>()
            val foreground = launch {
                runMediaJob("media_fetch", buildJsonObject { put("yield_after_seconds", 0) }, ctx, manager, 1, "none", "background", "下载媒体") {
                    foregroundEntered.complete(Unit)
                    try { awaitCancellation() } finally { foregroundCancelled.complete(Unit) }
                }
            }
            withTimeout(5_000) { foregroundEntered.await() }
            foreground.cancelAndJoin()
            withTimeout(5_000) { foregroundCancelled.await() }
            val failed = runMediaJob("media_fetch", buildJsonObject { put("background", true); put("on_exit", "none") }, ctx, manager, 1, "none", "background", "下载媒体") {
                com.promenar.infinity.data.model.ToolResult("failure", "保存失败：工作区不可用", "error")
            }
            val failedId = Json.parseToJsonElement(failed.content).jsonObject.getValue("job_id").jsonPrimitive.content
            val failureStatus = manager.waitFor("own", failedId, 5).record
            assertThat(failureStatus.state).isEqualTo("FAILED")
            assertThat(failureStatus.toolResult?.content).isEqualTo("保存失败：工作区不可用")
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    private fun context(id:String)=object:SkillExecutionContext {override val sessionId=id;override val agentId="a";override val workspacePath:String?=null;override val workspaceRootUuid="root"}
}
