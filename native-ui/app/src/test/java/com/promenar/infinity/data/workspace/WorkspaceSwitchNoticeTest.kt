package com.promenar.infinity.data.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.DefaultChatGenerationContentStrategy
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.ui.chat.manager.ContextBuilder
import com.promenar.infinity.ui.chat.manager.ContextBuilderParams
import com.promenar.infinity.ui.chat.buildPipelineGroups
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 工作区切换提示只在聊天流中可见；模型始终获得当前工作区与历史路径重验说明。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceSwitchNoticeTest {
    private val notice = WorkspaceSwitchNotice.create("已从“会话私有工作区”切换到“演示项目”", now = 3)
    private val messages = listOf(
        Message("u1", MessageRole.USER, "第一问", createdAt = 1),
        Message("a1", MessageRole.ASSISTANT, "第一答", createdAt = 2),
        notice,
        Message("u2", MessageRole.USER, "第二问", createdAt = 4),
    )

    @Test
    fun `提示独立成组且不并入前后的助手回复`() {
        val groups = buildPipelineGroups(messages + Message("a2", MessageRole.ASSISTANT, "第二答", createdAt = 5))
        assertThat(groups.map { it.messages.map(Message::id) })
            .containsExactly(listOf("u1"), listOf("a1"), listOf(notice.id), listOf("u2"), listOf("a2")).inOrder()
        assertThat(groups[2].isNotice).isTrue()
        assertThat(groups.filter { it.isNotice }).hasSize(1)
        assertThat(Message("s", MessageRole.SYSTEM, "其它系统消息").isWorkspaceSwitchNotice).isFalse()
    }

    @Test
    fun `协议消息跳过提示，系统提示词提醒历史路径可能属于之前的工作区`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val strategy = DefaultChatGenerationContentStrategy(
            context.getSharedPreferences("notice-${UUID.randomUUID()}", Context.MODE_PRIVATE), null,
        )
        val session = Session("s1", "agent", messages = messages)
        val protocol = strategy.buildProtocolMessages(session, "系统", "u2")
        assertThat(protocol.map { it.content }).containsExactly("系统", "第一问", "第一答", "第二问").inOrder()

        suspend fun prompt(session: Session, workspace: SessionWorkspace = SessionWorkspace.Private) = ContextBuilder().buildContext(
            ContextBuilderParams("s1", "第二问", assistantMsgId = "m", session = session,
                availableToolNames = setOf("read_file"), workspace = workspace),
        ).finalSystemPrompt
        val privatePrompt = prompt(session)
        val revalidation = "Paths, uuids and hashes are scoped to the current workspace. After switching workspaces, list or read files again before reusing earlier values."
        assertThat(privatePrompt).contains("This conversation has a private workspace.")
        assertThat(privatePrompt).contains(revalidation)
        assertThat(prompt(session.copy(messages = messages - notice))).isEqualTo(privatePrompt)

        val directory = SessionWorkspace.Directory(
            WorkspaceEntity("demo", WorkspaceEntity.KIND_PROJECT, "演示项目", "demo", 0),
            File(context.filesDir, "demo"),
        )
        val selectedPrompt = prompt(session, directory)
        assertThat(selectedPrompt).contains("### Current Workspace")
        assertThat(selectedPrompt).contains("user-selected workspace \"演示项目\"")
        assertThat(selectedPrompt).contains(revalidation)
        assertThat(prompt(session.copy(messages = messages - notice), directory)).isEqualTo(selectedPrompt)
    }
}
