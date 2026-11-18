package com.promenar.infinity.data.session

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.media.annotation.ImageAnnotationContext
import com.promenar.infinity.domain.media.ImageAnnotation
import com.promenar.infinity.domain.media.ImageAnnotationKind
import org.junit.Test
import com.promenar.infinity.data.model.*

class BranchSessionPlannerTest {
    @Test
    fun `分支复制保留独立轮次上下文原文且不改写内容中的消息标识`() {
        val raw = "  <context>m1 /项目/甲\r\n🧪</context>\n"
        val original = Message("m1", MessageRole.USER, "用户正文", turnContext = raw).toEntity("source")
        val assistant = Message("m2", MessageRole.ASSISTANT, "助手正文", status = "success", requestInstruction = raw).toEntity("source")
        val result = BranchSessionPlanner.plan(session(), listOf(original, assistant), "m2", "branch", 2) { "copy-$it" }
        val copied = (result as BranchSessionPlanResult.Ready).plan.messages.first()
        assertThat(copied.id).isEqualTo("copy-m1")
        assertThat(copied.turnContext!!.toByteArray(Charsets.UTF_8)).isEqualTo(raw.toByteArray(Charsets.UTF_8))
        assertThat(copied.toDomain().turnContext).isEqualTo(raw)
        assertThat(copied.content).isEqualTo("用户正文")
        assertThat(original.turnContext).isEqualTo(raw)
        val copiedAssistant = (result as BranchSessionPlanResult.Ready).plan.messages.last()
        assertThat(copiedAssistant.requestInstruction!!.toByteArray(Charsets.UTF_8)).isEqualTo(raw.toByteArray(Charsets.UTF_8))
        assertThat(copiedAssistant.content).isEqualTo("助手正文")
    }

    @Test
    fun `分支引用来源在复制范围内时改写否则保留且只有引用的用户消息可分支`() {
        val quotes = listOf(MessageQuote("m1", "来源", "批注"), MessageQuote("outside", "外部来源", "外部批注"))
        val messages = listOf(message("m1", "assistant", "来源"),
            Message("m2", MessageRole.USER, "", quotes = quotes).toEntity("source"),
            message("m3", "assistant", "不复制"))
        val result = BranchSessionPlanner.plan(session(), messages, "m2", "branch", 2) { "copy-$it" }
        val plan = (result as BranchSessionPlanResult.Ready).plan
        assertThat(plan.messages.map { it.id }).containsExactly("copy-m1", "copy-m2").inOrder()
        assertThat(plan.messages.last().toDomain().quotes).containsExactly(
            MessageQuote("copy-m1", "来源", "批注"), MessageQuote("outside", "外部来源", "外部批注"),
        ).inOrder()
        assertThat(plan.messages.last().content).isEmpty()
    }

    @Test
    fun `历史消息克隆不继承派发关系worktree与自动处理暂停`() {
        val source = session().copy(parentSessionId = "parent", branchPlanJson = "{}",
            worktreeDirectory = "/project/branch", worktreeBranch = "feature", worktreeBase = "abc",
            parentSessionDeleted = true, sessionAutoWakeCount = 8, sessionAutoProcessingPaused = true)
        val result = BranchSessionPlanner.plan(source, listOf(message("m1", "user", "问题")), "m1", "clone", 2) { "copy-$it" }
        val clone = (result as BranchSessionPlanResult.Ready).plan.session
        assertThat(clone.parentSessionId).isNull()
        assertThat(clone.branchPlanJson).isNull()
        assertThat(clone.worktreeDirectory).isNull()
        assertThat(clone.worktreeBranch).isNull()
        assertThat(clone.worktreeBase).isNull()
        assertThat(clone.parentSessionDeleted).isFalse()
        assertThat(clone.sessionAutoWakeCount).isEqualTo(0)
        assertThat(clone.sessionAutoProcessingPaused).isFalse()
    }

    @Test
    fun `分支保留已固定运行模式和未确定旧模式`() {
        listOf(null, "LINUX", "BUILTIN").forEach { mode ->
            val result = BranchSessionPlanner.plan(session().copy(runMode = mode),
                listOf(message("m1", "user", "问题")), "m1", "branch", 2) { "copy-$it" }
            assertThat((result as BranchSessionPlanResult.Ready).plan.session.runMode).isEqualTo(mode)
        }
    }

    @Test
    fun `分支只复制到目标消息并清空运行态与工作区`() {
        val source = session()
        val messages = listOf(
            message("m1", "user", "问题", files = "[{\"name\":\"history.md\"}]"),
            message("m2", "assistant", "回答", parentMessageId = "m1", status = "success"),
            message("m3", "user", "后续"),
        )

        val result = BranchSessionPlanner.plan(
            source = source,
            sourceMessages = messages,
            targetMessageId = "m2",
            newSessionId = "branch-session",
            now = 200L,
            newMessageId = { original -> "copy-$original" },
        )

        assertThat(result).isInstanceOf(BranchSessionPlanResult.Ready::class.java)
        val plan = (result as BranchSessionPlanResult.Ready).plan
        assertThat(plan.session.id).isEqualTo("branch-session")
        assertThat(plan.session.modelId).isEqualTo(source.modelId)
        assertThat(plan.session.customPrompt).isEqualTo(source.customPrompt)
        assertThat(plan.session.ragOptions).isEqualTo(source.ragOptions)
        assertThat(plan.session.inferenceParams).isEqualTo(source.inferenceParams)
        assertThat(plan.session.options).isEqualTo(source.options)
        assertThat(plan.session.activeMcpServerIds).isEqualTo(source.activeMcpServerIds)
        assertThat(plan.session.activeSkillIds).isEqualTo(source.activeSkillIds)
        assertThat(plan.session.draft).isNull()
        assertThat(plan.session.scrollOffset).isNull()
        assertThat(plan.session.loopStatus).isEqualTo("idle")
        assertThat(plan.session.pendingIntervention).isNull()
        assertThat(plan.session.approvalRequest).isNull()
        assertThat(plan.session.activeTask).isNull()
        assertThat(plan.session.stats).isNull()
        assertThat(plan.session.workspacePath).isNull()
        assertThat(plan.session.workspaceRootUuid).isNull()
        assertThat(plan.session.activeTaskTreeId).isNull()
        assertThat(plan.messages.map { it.id }).containsExactly("copy-m1", "copy-m2").inOrder()
        assertThat(plan.messages.map { it.sessionId }).containsExactly("branch-session", "branch-session")
        assertThat(plan.messages[0].files).isEqualTo(messages[0].files)
        assertThat(plan.messages[1].parentMessageId).isEqualTo("copy-m1")
        assertThat(plan.messages[1].pendingApprovalToolIds).isNull()
        assertThat(plan.messages[1].ragProgress).isNull()
        assertThat(plan.messages[1].ragReferencesLoading).isEqualTo(0)
        assertThat(plan.messages[1].planningTask).isNull()
        assertThat(plan.messages[1].layoutHeight).isNull()
        assertThat(plan.messages.map { it.tokens }).containsExactly(null, null)
        assertThat(plan.messages.map { it.isArchived }).containsExactly(0, 0)
        assertThat(plan.messages.map { it.vectorizationStatus }).containsExactly(null, null)
    }

    @Test
    fun `不存在或仍在生成的目标消息不能分支`() {
        val source = session()
        val generating = message("m1", "assistant", "partial", status = "streaming")
        val retrieving = message("m2", "assistant", "partial", ragReferencesLoading = 1)

        assertThat(
            BranchSessionPlanner.plan(source, listOf(generating), "missing", "new", 2L) { it },
        ).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotFound))
        assertThat(
            BranchSessionPlanner.plan(source, listOf(generating), "m1", "new", 2L) { it },
        ).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
        assertThat(
            BranchSessionPlanner.plan(source, listOf(retrieving), "m2", "new", 2L) { it },
        ).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
    }

    @Test
    fun `目标稳定但前缀仍有生成或审批状态时拒绝分支`() {
        val source = session()
        val stableTarget = message("m2", "user", "后续", parentMessageId = "m1")

        assertThat(
            BranchSessionPlanner.plan(
                source,
                listOf(message("m1", "assistant", "partial", status = "streaming"), stableTarget),
                "m2",
                "new",
                2L,
            ) { it },
        ).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
        assertThat(
            BranchSessionPlanner.plan(
                source,
                listOf(
                    message("m1", "assistant", "answer", pendingApprovalToolIds = "[\"call-1\"]"),
                    stableTarget,
                ),
                "m2",
                "new",
                2L,
            ) { it },
        ).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
    }

    @Test
    fun `工具调用与结果必须成对落在分支前缀中`() {
        val source = session()
        val assistant = message(
            "m1",
            "assistant",
            "",
            toolCalls = "[{\"id\":\"call-1\",\"name\":\"search\",\"arguments\":\"{}\"}]",
        )
        val tool = message("m2", "tool", "result", toolCallId = "call-1")

        assertThat(
            BranchSessionPlanner.plan(source, listOf(assistant), "m1", "new", 2L) { it },
        ).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
        assertThat(
            BranchSessionPlanner.plan(source, listOf(assistant, tool), "m2", "new", 2L) { it },
        ).isInstanceOf(BranchSessionPlanResult.Ready::class.java)
    }

    @Test
    fun `从助手建分支延伸到同一轮中配对工具结果的首个稳定边界`() {
        val user = message("m1", "user", "问题")
        val assistant = Message("m2", MessageRole.ASSISTANT, "开始读取",
            toolCalls = listOf(ToolCall("call-1", "read_file", "{}"))).toEntity("source")
        val tool = message("m3", "tool", "结果", parentMessageId = "m2", toolCallId = "call-1")
        val later = message("m4", "assistant", "总结")
        val next = message("m5", "user", "下一轮")

        val result = BranchSessionPlanner.plan(session(), listOf(user, assistant, tool, later, next), "m2", "new", 2) { "copy-$it" }
        val plan = (result as BranchSessionPlanResult.Ready).plan
        assertThat(plan.messages.map { it.id }).containsExactly("copy-m1", "copy-m2", "copy-m3").inOrder()
        assertThat(plan.messages.last().parentMessageId).isEqualTo("copy-m2")
        assertThat(plan.session.lastMessage).isEqualTo("开始读取")

        val acrossTurn = BranchSessionPlanner.plan(session(), listOf(user, assistant, next, tool), "m2", "new", 2) { it }
        assertThat(acrossTurn).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
    }

    @Test
    fun `同一工具调用出现重复结果时拒绝分支`() {
        val assistant = Message("m1", MessageRole.ASSISTANT, "读取两份文件",
            toolCalls = listOf(ToolCall("a", "read_file", "{}"), ToolCall("b", "read_file", "{}"))).toEntity("source")
        val first = message("m2", "tool", "第一份", toolCallId = "a")
        val duplicate = message("m3", "tool", "重复的第一份", toolCallId = "a")
        val second = message("m4", "tool", "第二份", toolCallId = "b")

        val result = BranchSessionPlanner.plan(session(), listOf(assistant, first, duplicate, second), "m1", "new", 2) { it }

        assertThat(result).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
    }

    @Test
    fun `编辑分支重排图片时对齐引用和标注并在清空图片后清空元数据`() {
        val first = WorkspaceDocumentReference("/甲.png", 0, 1)
        val second = WorkspaceDocumentReference("/乙.png", 0, 1)
        val mark = ImageAnnotation(ImageAnnotationKind.MARKUP, 4, 4, "/乙标注.png", second.path)
        val context = ImageAnnotationContext("source", linuxNamespace = false)
        val original = Message("m1", MessageRole.USER, "原问题", userImages = listOf("甲", "乙"),
            userImageReferences = listOf(first, second), userImageAnnotations = listOf(null, mark),
            userImageAnnotationContext = context).toEntity("source")

        fun cloneWith(images: List<String>): Message {
            val plan = BranchSessionPlanner.plan(session(), listOf(original), original.id, "branch", 2,
                editedTargetUser = EditedUserMessagePayload("新问题", userImages = images)) { "copy-$it" }
            return (plan as BranchSessionPlanResult.Ready).plan.messages.single().toDomain()
        }

        val reordered = cloneWith(listOf("乙", "甲"))
        assertThat(reordered.userImageReferences).containsExactly(second, first).inOrder()
        assertThat(reordered.userImageAnnotations).containsExactly(mark, null).inOrder()
        assertThat(reordered.userImageAnnotationContext).isEqualTo(context)
        val cleared = cloneWith(emptyList())
        assertThat(cleared.userImageReferences).isNull()
        assertThat(cleared.userImageAnnotations).isNull()
        assertThat(cleared.userImageAnnotationContext).isNull()
    }

    @Test
    fun `编辑分支截断到用户消息并清空附件和旧上下文且重映射引文`() {
        val reference = WorkspaceDocumentReference("/旧图.png", 0, 1)
        val document = MessageDocumentAttachment("doc", "旧文档", "text/plain", "原文", 6, "hash", 1)
        val original = Message("m2", MessageRole.USER, "旧问题", userImages = listOf("旧图"),
            userImageReferences = listOf(reference), userDocuments = listOf(document),
            quotes = listOf(MessageQuote("obsolete", "原引文", "")), turnContext = "旧轮次上下文").toEntity("source")
        val messages = listOf(message("m1", "assistant", "来源"), original,
            message("m3", "assistant", "生成中", status = "streaming"))
        val payload = EditedUserMessagePayload(
            "新问题", userImages = emptyList(), userImageReferences = emptyList(), userDocuments = emptyList(),
            quotes = listOf(MessageQuote("m1", "来源", "新批注")), userImageAnnotations = emptyList(),
        )

        val result = BranchSessionPlanner.plan(session(), messages, "m3", "new", 2, editedTargetUser = payload) { "copy-$it" }
        val plan = (result as BranchSessionPlanResult.Ready).plan
        assertThat(plan.messages.map { it.id }).containsExactly("copy-m1", "copy-m2").inOrder()
        val edited = plan.messages.last().toDomain()
        assertThat(edited.content).isEqualTo("新问题")
        assertThat(edited.userImages.orEmpty()).isEmpty()
        assertThat(edited.userImageReferences.orEmpty()).isEmpty()
        assertThat(edited.userDocuments.orEmpty()).isEmpty()
        assertThat(edited.userImageAnnotationContext).isNull()
        assertThat(edited.turnContext).isNull()
        assertThat(edited.quotes).containsExactly(MessageQuote("copy-m1", "来源", "新批注"))
        assertThat(plan.session.lastMessage).isEqualTo("新问题")
        assertThat(original.toDomain().userDocuments).containsExactly(document)
    }

    @Test
    fun `父消息不在复制前缀时拒绝分支`() {
        val result = BranchSessionPlanner.plan(
            session(),
            listOf(message("m2", "user", "orphan", parentMessageId = "missing")),
            "m2",
            "new",
            2L,
        ) { it }

        assertThat(result)
            .isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
    }

    private fun session() = SessionEntity(
        id = "source",
        agentId = "agent",
        title = "原会话",
        lastMessage = "旧尾注",
        time = "now",
        unread = 3,
        modelId = "model",
        customPrompt = "prompt",
        isPinned = 1,
        scrollOffset = 100.0,
        draft = "draft",
        executionMode = "manual",
        loopStatus = "waiting_for_approval",
        pendingIntervention = "pending",
        approvalRequest = "approval",
        ragOptions = "rag",
        inferenceParams = "inference",
        activeTask = "task",
        stats = "stats",
        options = "options",
        activeMcpServerIds = "mcp",
        activeSkillIds = "skills",
        workspacePath = "/old/workspace",
        workspaceRootUuid = "root",
        activeTaskTreeId = "tree",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun message(
        id: String,
        role: String,
        content: String,
        parentMessageId: String? = null,
        status: String? = null,
        files: String? = null,
        ragReferencesLoading: Int = 0,
        pendingApprovalToolIds: String? = null,
        toolCalls: String? = null,
        toolCallId: String? = null,
    ) = MessageEntity(
        id = id,
        sessionId = "source",
        role = role,
        content = content,
        status = status,
        parentMessageId = parentMessageId,
        files = files,
        tokens = "{\"input\":12,\"output\":34}",
        pendingApprovalToolIds = pendingApprovalToolIds,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
        ragProgress = "progress",
        ragReferencesLoading = ragReferencesLoading,
        planningTask = "planning",
        isArchived = 1,
        vectorizationStatus = "completed",
        layoutHeight = 88.0,
        createdAt = id.last().digitToInt().toLong(),
    )
}
