package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.PRODUCTION_COST_ALERT_COUNT
import org.junit.Test

class SystemPromptSectionsMediaTest {
    @Test fun `工作区执行与媒体工具单独可用都提供媒体约定`() {
        listOf("read_file", "write_file", "list_files", "shell_exec", "python_exec", "run_script", "exec_js", "python_sandbox", "termux_exec", "ssh_exec", "ssh_session_open", "ssh_session_send", "image_generation", "generate_image", "media_fetch", "view_media").forEach { name ->
            val prompt = SystemPromptSections.toolGuidance(setOf(name), false)
            assertThat(prompt).contains("### Media")
            assertThat(prompt).contains("media/<yyyy-MM-dd>/")
            assertThat(prompt).contains("![说明](路径)")
            assertThat(prompt).contains("[名称](路径)")
            assertThat(prompt).contains("base64")
            assertThat(prompt).contains("遵循运行环境段的成果文件落点")
        }
    }
    @Test fun `成果与媒体使用项目子目录且工具缺省落点要求显式覆盖`() {
        val runtime = SystemPromptSections.runtimeEnvironment(null)
        assertThat(runtime).contains("遵循项目规则放入工作区内合适的子目录")
        assertThat(runtime).contains("未设定工作区时放入会话文件夹")
        assertThat(SystemPromptSections.toolGuidance(setOf("write_file"), false))
            .contains("已设定工作区时，创建成果须显式指定工作区子目录")
    }
    @Test fun `媒体分节说明默认单列与gallery、carousel块的用法`() {
        val prompt = SystemPromptSections.toolGuidance(setOf("read_file"), false)
        val media = prompt.substringAfter("### Media")
        assertThat(media).contains("默认逐条显示")
        assertThat(media).contains("图片网格用 ```gallery")
        assertThat(media).contains("按顺序滑动查看用 ```carousel")
        assertThat(media).contains("每行一条 `![说明](路径)`")
        assertThat(media).contains("逐图讲解时单独展示")
        assertThat(media).contains("https://workspace-media.infinity.invalid/workspace/")
        assertThat(media).contains("逐段 URL 编码")
    }
    @Test fun `无媒体相关工具与generic不增加分节`() {
        listOf(emptySet(), setOf("calculator"), setOf("web_fetch"), setOf("get_plan")).forEach {
            assertThat(SystemPromptSections.toolGuidance(it, false)).doesNotContain("### Media")
        }
        assertThat(SystemPromptSections.toolGuidance(setOf("media_fetch", "view_media"), true)).doesNotContain("### Media")
    }
    @Test fun `查看与下载指引仅在提供对应工具时出现`() {
        val basic = SystemPromptSections.toolGuidance(setOf("read_file"), false)
        assertThat(basic).doesNotContain("`view_media`")
        assertThat(basic).doesNotContain("`media_fetch`")
        val full = SystemPromptSections.toolGuidance(setOf("view_media", "media_fetch"), false)
        listOf("`view_media`", "`frames`", "`interval_seconds`", "重看指定时段", "`media_fetch`", "工具步骤中已展示的媒体也可引用").forEach { assertThat(full).contains(it) }
    }
    @Test fun `工作区文件与计划说明与已锁定基线一致`() {
        val actual = SystemPromptSections.toolGuidance(setOf("read_file", "initialize_plan"), false)
        val expected = javaClass.getResource("/media-d-tool-guidance-before.txt")!!.readText()
        assertThat(actual.substringAfter("### Workspace Files").substringBefore("\n### Media").trimEnd())
            .isEqualTo(expected.substringAfter("### Workspace Files").trimEnd())
    }

    @Test fun `默认落点提示区分技能挂载与用户挂载且支持运行模式呈现`() {
        val workspace = com.promenar.infinity.data.workspace.SessionWorkspace.Directory(
            com.promenar.infinity.data.local.db.entity.WorkspaceEntity("ws", "project", "项目", "project", 1), java.io.File("/data/user/0/app/files/projects/project"))
        val skills = com.promenar.infinity.domain.storage.ExternalMount("builtin:skills", "skills", "builtin_skills", "agent_skills", "s", 0)
        val assets = com.promenar.infinity.domain.storage.ExternalMount("assets", "素材", "real_path", "/assets", "s", 0)
        val main = SystemPromptSections.toolGuidance(setOf("canvas_create", "media_fetch"), false, workspace, mounts = listOf(skills),
            builtinWorkspacePath = "/data/user/0/app/files/projects/project",
            builtinPrivateWorkspacePath = "/data/user/0/app/files/session_workspaces/s")
        assertThat(main).contains("默认保存在主工作区（`/data/user/0/app/files/projects/project`）")
        assertThat(main).contains("通过 `/data/user/0/app/files/session_workspaces/s`")
        val privatePrompt = SystemPromptSections.toolGuidance(setOf("canvas_create", "media_fetch"), false, workspace, mounts = listOf(skills, assets),
            builtinWorkspacePath = "/data/user/0/app/files/projects/project",
            builtinPrivateWorkspacePath = "/data/user/0/app/files/session_workspaces/s")
        assertThat(privatePrompt).contains("默认保存在会话专用空间（`/data/user/0/app/files/session_workspaces/s`）")
        assertThat(privatePrompt).contains("切换主工作区不会搬动镜像")
        val linux = SystemPromptSections.toolGuidance(setOf("canvas_create"), false, workspace, mounts = listOf(assets),
            runMode = com.promenar.infinity.domain.model.SessionRunMode.LINUX,
            filePathPresenter = com.promenar.infinity.data.workspace.WorkspaceFilePathPresenter { target, path, _ ->
                if (target == com.promenar.infinity.data.workspace.WorkspaceDefaultFileDestination.SESSION_PRIVATE) "/home/session" + path.trimEnd('/') else path
            })
        assertThat(linux).contains("默认保存在会话专用空间（`/home/session`）")
        assertThat(linux).doesNotContain("通过 `/session`")
    }
    @Test fun `Linux持久模式的默认落点和私有入口不泄漏内置session路径`() {
        val workspace = com.promenar.infinity.data.workspace.SessionWorkspace.Directory(
            com.promenar.infinity.data.local.db.entity.WorkspaceEntity("ws", "project", "项目", "project", 1), java.io.File("project"))
        val prompt = SystemPromptSections.toolGuidance(setOf("media_fetch"), false, workspace,
            runMode = com.promenar.infinity.domain.model.SessionRunMode.LINUX)
        assertThat(prompt).contains("默认保存在主工作区（`/workspace`）")
        assertThat(prompt).contains("通过 `/home/session`")
        assertThat(prompt).doesNotContain("`/session`")
    }

    @Test fun `产物发布提示按工具可用性出现`() {
        assertThat(SystemPromptSections.toolGuidance(setOf("media_fetch", "artifact_publish"), false)).contains("artifact_publish")
        assertThat(SystemPromptSections.toolGuidance(setOf("media_fetch"), false)).doesNotContain("artifact_publish")
        assertThat(SystemPromptSections.toolGuidance(setOf("artifact_publish"), true)).doesNotContain("artifact_publish")
    }

    @Test fun `媒体引用明确禁止转成相对路径或放进Markdown表格并指引ComfyUI提交器契约`() {
        val prompt = SystemPromptSections.toolGuidance(setOf("read_file", "comfy_run_workflow"), false)
        assertThat(prompt).contains("禁用 `../`、base64 与表格里的媒体引用")
        assertThat(prompt).contains("references/comfyui-submitters.md")
    }

    @Test fun `视频制作指引独立成节且覆盖短片试验片与例外边界`() {
        val production = SystemPromptSections.toolGuidance(setOf("production_generate"), false)
        assertThat(production).contains("### 视频与影视制作（制作面板）")
        assertThat(production).contains("production_define")
        assertThat(production).contains("production_attach")
        assertThat(production).contains("动作试验片")
        assertThat(production).contains("无需建项")
        assertThat(production).contains("生成达 $PRODUCTION_COST_ALERT_COUNT 次时先告知用户再继续")
        assertThat(production).contains("references/production.md")
        val previs = SystemPromptSections.toolGuidance(setOf("production_previs"), false)
        assertThat(previs).doesNotContain("references/production.md")
    }

}
