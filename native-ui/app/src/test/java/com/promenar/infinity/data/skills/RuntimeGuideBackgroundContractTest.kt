package com.promenar.infinity.data.skills

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.chat.manager.SystemPromptSections
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.ActivateSkillTool
import com.promenar.infinity.ui.chat.manager.skills.ReadSkillFileTool
import com.promenar.infinity.ui.chat.manager.skills.ShellExecSkill
import com.promenar.infinity.ui.chat.manager.skills.backgroundJobTools
import com.promenar.infinity.ui.chat.manager.skills.sshTools
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 持久进程说明的分层契约：常驻上下文只放选用规则与指向，细节在内置指南的附带文件中按需读取。
 */
class RuntimeGuideBackgroundContractTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assets = runtimeGuideMainDirectory().resolve("assets")
    private val guideDirectory = assets.resolve(BuiltinSkillCatalog.ASSET_DIR)
    private val backgroundReference = "references/background-processes.md"
    private val executionReference = "references/command-execution.md"
    private val catalog by lazy { BuiltinSkillCatalog(
        FileSkillCatalog(temporary.newFolder("user"), InMemoryPreferences(), temporary.newFolder("state"), Dispatchers.IO,
            System::currentTimeMillis, SkillCatalogFiles(JvmBoundRealPathFileSystem())),
    ) { assets.resolve(it).readText() } }
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath = "/"
        override val workspaceRootUuid = "root"
    }

    @Test fun `附带文件白名单与打包目录一致且按字典序`() {
        val packaged = guideDirectory.walkTopDown().filter { it.isFile && it.name != "SKILL.md" && !it.name.startsWith(".") }
            .map { it.relativeTo(guideDirectory).invariantSeparatorsPath }.toList()
        assertThat(BuiltinSkillCatalog.REFERENCE_FILES).containsExactlyElementsIn(packaged)
        assertThat(BuiltinSkillCatalog.REFERENCE_FILES).isInOrder()
        assertThat(BuiltinSkillCatalog.REFERENCE_FILES).containsAtLeast(backgroundReference, executionReference)
    }

    @Test fun `附带文件可按名称加相对路径或完整虚拟路径读取且白名单外拒绝`() = runTest {
        val skill = catalog.scan(null).builtin.single()
        assertThat(skill.files).isEqualTo(BuiltinSkillCatalog.REFERENCE_FILES)
        for (relative in BuiltinSkillCatalog.REFERENCE_FILES) {
            val expected = guideDirectory.resolve(relative).readText()
            val byName = ReadSkillFileTool(catalog).execute(buildJsonObject {
                put("name", BuiltinSkillCatalog.NAME); put("path", relative)
            }, context)
            assertThat(byName.status).isEqualTo("success")
            assertThat(byName.content).isEqualTo(expected)
            val byPath = ReadSkillFileTool(catalog).execute(buildJsonObject {
                put("path", "${BuiltinSkillCatalog.VIRTUAL_DIR}/$relative")
            }, context)
            assertThat(byPath.content).isEqualTo(expected)
        }
        for (path in listOf("references/missing.md", "references/../SKILL.md", "references")) {
            assertWithMessage(path).that(runCatching { catalog.readFile(skill, path) }.isFailure).isTrue()
        }
    }

    @Test fun `激活内置指南时列出附带文件并给出读取方式`() = runTest {
        val activated = ActivateSkillTool(catalog).execute(buildJsonObject { put("name", BuiltinSkillCatalog.NAME) }, context).content
        BuiltinSkillCatalog.REFERENCE_FILES.forEach { assertThat(activated).contains("- $it") }
        assertThat(activated).contains("read_skill_file(name=\"${BuiltinSkillCatalog.NAME}\"")
        assertThat(activated).doesNotContain("Script directory in the shell")
    }

    @Test fun `指南首层给出持久进程规则与指向且细节不进入正文`() {
        val document = SkillMarkdownParser.parse(guideDirectory.resolve("SKILL.md").readText())
        assertThat(document.metadata.description).contains("后台任务")
        assertThat(document.metadata.description).contains("定时任务")
        val section = document.body.substringAfter("## 命令执行与后台任务").substringBefore("\n## ")
        listOf("background: true", "job_status", "job_wait", "job_input", "job_stop", "job_list", "schedule_create",
            "nohup", "setsid", "tmux", "设置 → 后台与定时", backgroundReference, executionReference)
            .forEach { assertWithMessage(it).that(section).contains(it) }
        assertThat(section.length).isAtMost(1000)
        // 参数取值、状态枚举等细节只出现在附带文件。
        listOf("max_runtime_minutes", "until_output", "launch_confirmed").forEach { assertThat(document.body).doesNotContain(it) }
        assertThat(document.body.length).isAtMost(12_500)
    }

    @Test fun `后台任务参考覆盖全部任务工具状态结束动作与定时工具`() {
        val reference = guideDirectory.resolve(backgroundReference).readText()
        backgroundJobTools(mockk()).forEach { assertWithMessage(it.name).that(reference).contains("`${it.name}`") }
        BackgroundJobState.entries.forEach { assertWithMessage(it.name).that(reference).contains("`${it.name.lowercase()}`") }
        BackgroundJobExitAction.entries.forEach { assertWithMessage(it.wireName).that(reference).contains("`${it.wireName}`") }
        listOf("schedule_create", "schedule_list", "schedule_pause", "schedule_resume", "schedule_cancel", "infinity-wake",
            "max_runtime_minutes", "until_output", "until_file", "keep_alive", "foreground_only", "launch_confirmed",
            "JOB_SESSION_LIMIT", "JOB_STOP_UNCONFIRMED", "systemd", "设置 → 后台与定时")
            .forEach { assertWithMessage(it).that(reference).contains(it) }
        assertThat(reference.length).isAtMost(8_000)
    }

    @Test fun `命令执行参考覆盖各命令工具结果字段与常见错误码`() {
        val reference = guideDirectory.resolve(executionReference).readText()
        listOf("shell_exec", "python_exec", "termux_exec", "ssh_exec", "ssh_session_open",
            "error_code", "guidance", "exit_code", "timed_out", "truncated", "head_removed", "paused_ms", "workspace_changes",
            "EXEC_STOPPED", "EXEC_INTERRUPTED", "EXEC_LINUX_UNAVAILABLE", "EXEC_MODE_CHANGED", "EXEC_WORKSPACE_UNAVAILABLE",
            "EXEC_PYTHON_UNAVAILABLE", "TERMUX_TIMEOUT", "TERMUX_INTEGRATION_DISABLED", "SSH_HOST_KEY_UNTRUSTED",
            "SECRET_CAPTURE_BACKGROUND_UNSUPPORTED", "JOB_NOT_FOUND", "JOB_UNAVAILABLE")
            .forEach { assertWithMessage(it).that(reference).contains(it) }
        assertThat(reference.length).isAtMost(8_000)
    }

    @Test fun `系统提示在有命令工具与任务工具时给出一行选用规则并指向参考文件`() {
        val pointer = "read_skill_file(name=\"${BuiltinSkillCatalog.NAME}\", path=\"$backgroundReference\")"
        for (runMode in SessionRunMode.entries) for (tool in listOf("shell_exec", "python_exec", "termux_exec", "ssh_exec")) {
            val prompt = SystemPromptSections.toolGuidance(setOf(tool, "job_wait"), generic = false, runMode = runMode)
            val line = prompt.lines().single { pointer in it }
            listOf("background:true", "nohup", "setsid", "tmux", "schedule_create").forEach { assertWithMessage("$runMode/$tool/$it").that(line).contains(it) }
            assertThat(line.length).isAtMost(260)
        }
        assertThat(SystemPromptSections.toolGuidance(setOf("ssh_exec"), generic = false)).doesNotContain(pointer)
        assertThat(SystemPromptSections.toolGuidance(setOf("job_wait"), generic = false)).doesNotContain(pointer)
        assertThat(SystemPromptSections.toolGuidance(setOf("shell_exec", "job_wait"), generic = true)).doesNotContain(pointer)
    }

    @Test fun `工具说明给出状态取值完整日志做法前台运行要求与远端长期服务做法`() {
        val jobs = backgroundJobTools(mockk()).associateBy { it.name }
        val status = jobs.getValue("job_status").description
        BackgroundJobState.entries.forEach { assertThat(status).contains(it.name.lowercase()) }
        assertThat(status).contains("工作区文件")
        assertThat(jobs.getValue("job_wait").parametersSchema).contains("SSH 任务不支持")
        val ssh = sshTools(mockk(relaxed = true), null).single { it.name == "ssh_exec" }.description
        listOf("systemd", "tmux", "nohup").forEach { assertThat(ssh).contains(it) }
        val shell = ShellExecSkill(mockk(relaxed = true)).description
        assertThat(shell).contains("主命令须保持前台运行")
        assertThat(shell).contains("不要自行守护化")
    }
}
