package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.knowledge.KnowledgeAccessPreview
import com.promenar.infinity.data.knowledge.KnowledgeAccessTarget
import com.promenar.infinity.data.knowledge.KnowledgeDocumentLocationKind
import com.promenar.infinity.data.knowledge.KnowledgeSearchData
import com.promenar.infinity.data.knowledge.KnowledgeSearchHit
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.ui.chat.components.KnowledgeHitPathLine
import com.promenar.infinity.ui.chat.components.KnowledgeMountOption
import com.promenar.infinity.ui.chat.components.knowledgeHitPathLine
import com.promenar.infinity.ui.chat.components.knowledgeMountOption
import com.promenar.infinity.ui.chat.components.parseKnowledgeSearchData
import com.promenar.infinity.ui.chat.components.toDetails
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class KnowledgeSearchUiLogicTest {
    private val data = KnowledgeSearchData(
        query = "签名配置",
        references = listOf(RagReference(id = "r1", content = "片段", source = "部署手册.md")),
        results = listOf(
            KnowledgeSearchHit("r1", "K1", pathType = KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_UNMOUNTED, requiresAccess = true),
        ),
    )

    @Test
    fun `运行中的知识检索显示为进行中步骤`() {
        val step = knowledgeSearchStep(invocation(result = null))!!
        assertThat(step.running).isTrue()
        assertThat(step.data).isNull()
        assertThat(step.callId).isEqualTo("call")
    }

    @Test
    fun `完成的知识检索解析结构化结果并按引用 id 对应编号`() {
        val step = knowledgeSearchStep(invocation(result = Json.encodeToString(data)))!!
        assertThat(step.running).isFalse()
        assertThat(step.data).isEqualTo(data)
        val details = step.data!!.toDetails()
        assertThat(details.query).isEqualTo("签名配置")
        assertThat(details.hits.getValue("r1").citationId).isEqualTo("K1")
    }

    @Test
    fun `无结构化数据、失败或其它工具交给通用工具摘要`() {
        assertThat(knowledgeSearchStep(invocation(result = ""))).isNull()
        assertThat(knowledgeSearchStep(invocation(result = "{坏数据"))).isNull()
        assertThat(knowledgeSearchStep(invocation(result = null, error = true))).isNull()
        assertThat(knowledgeSearchStep(invocation(result = Json.encodeToString(data), name = "memory_search"))).isNull()
        assertThat(parseKnowledgeSearchData(null)).isNull()
    }

    @Test
    fun `读取路径按位置类型给出路径或不可读原因`() {
        fun hit(kind: KnowledgeDocumentLocationKind?, path: String? = null) = KnowledgeSearchHit("r", "K1", pathType = kind, path = path)
        assertThat(knowledgeHitPathLine(hit(KnowledgeDocumentLocationKind.WORKSPACE, "/workspace/a.md")))
            .isEqualTo(KnowledgeHitPathLine(isPath = true, text = "/workspace/a.md"))
        assertThat(knowledgeHitPathLine(hit(KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_MOUNTED, "/mnt/knowledge/a.md"))!!.text)
            .isEqualTo("/mnt/knowledge/a.md")
        assertThat(knowledgeHitPathLine(hit(KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_MOUNTED, " "))).isNull()
        assertThat(knowledgeHitPathLine(hit(KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_UNMOUNTED))!!.messageRes)
            .isEqualTo(R.string.knowledge_search_path_unmounted)
        assertThat(knowledgeHitPathLine(hit(KnowledgeDocumentLocationKind.SNIPPET_ONLY))!!.messageRes)
            .isEqualTo(R.string.knowledge_search_path_snippet_only)
        assertThat(knowledgeHitPathLine(hit(null))).isNull()
    }

    @Test
    fun `访问授权审批从授权目标解码知识库与挂载路径`() {
        val target = KnowledgeAccessTarget("root", "默认知识库", 128, "knowledge", "/mnt/knowledge")
        val preview = KnowledgeAccessPreview("s1", allowed = true, knowledgeBases = listOf(target))
        val access = knowledgeAccessApproval(REQUEST_KNOWLEDGE_ACCESS_TOOL, Json.encodeToString(preview), "  需要读部署手册全文  ")!!
        assertThat(access.reason).isEqualTo("需要读部署手册全文")
        assertThat(access.targets).containsExactly(target)
        assertThat(access.allowed).isTrue()
        assertThat(access.deniedReason).isNull()
        assertThat(access.readWrite).isFalse()
    }

    @Test
    fun `读写模式授权目标正确识别为可读写审批状态`() {
        val target = KnowledgeAccessTarget("root", "默认知识库", 11, "knowledge", "/mnt/knowledge", readOnly = false)
        val preview = KnowledgeAccessPreview("s1", allowed = true, knowledgeBases = listOf(target), accessMode = "read_write")
        val access = knowledgeAccessApproval(REQUEST_KNOWLEDGE_ACCESS_TOOL, Json.encodeToString(preview), "需要新建并归档笔记")!!
        assertThat(access.allowed).isTrue()
        assertThat(access.readWrite).isTrue()
        assertThat(access.targets.single().readOnly).isFalse()
    }

    @Test
    fun `访问授权预览被拒时保留原因，其它工具或坏目标不走专用卡片`() {
        val denied = KnowledgeAccessPreview("s1", allowed = false, reason = "知识库文件访问已关闭")
        val access = knowledgeAccessApproval(REQUEST_KNOWLEDGE_ACCESS_TOOL, Json.encodeToString(denied), " ")!!
        assertThat(access.reason).isNull()
        assertThat(access.deniedReason).isEqualTo("知识库文件访问已关闭")
        assertThat(knowledgeAccessApproval("read_file", Json.encodeToString(denied), null)).isNull()
        assertThat(knowledgeAccessApproval(REQUEST_KNOWLEDGE_ACCESS_TOOL, "session:s1", null)).isNull()
        assertThat(knowledgeAccessApproval(REQUEST_KNOWLEDGE_ACCESS_TOOL, null, null)).isNull()
    }

    @Test
    fun `挂载知识库入口在已挂载或不允许时置灰`() {
        fun target(alias: String, mounted: Boolean) = KnowledgeAccessTarget(alias, alias, 1, alias, "/mnt/$alias", alreadyMounted = mounted)
        assertThat(knowledgeMountOption(KnowledgeAccessPreview("s", allowed = true, knowledgeBases = listOf(target("knowledge", false), target("docs", true)))))
            .isEqualTo(KnowledgeMountOption.Available(listOf("/mnt/knowledge")))
        assertThat(knowledgeMountOption(KnowledgeAccessPreview("s", allowed = true, knowledgeBases = listOf(target("knowledge", true)))))
            .isEqualTo(KnowledgeMountOption.Mounted)
        assertThat(knowledgeMountOption(KnowledgeAccessPreview("s", allowed = false, reason = "x")))
            .isEqualTo(KnowledgeMountOption.Unavailable)
        assertThat(knowledgeMountOption(KnowledgeAccessPreview("s", allowed = true)))
            .isEqualTo(KnowledgeMountOption.Unavailable)
    }

    private fun invocation(result: String?, error: Boolean = false, name: String = KNOWLEDGE_SEARCH_TOOL) = ToolInvocationPresentation(
        listOfNotNull(
            ExecutionStep(id = "call", type = "tool_call", toolName = name, toolCallId = "call", toolArgs = """{"query":"签名配置"}"""),
            if (error) ExecutionStep(id = "err", type = "error", toolName = name, toolCallId = "call", content = "失败") else null,
            result?.let { ExecutionStep(id = "res", type = "tool_result", toolName = name, toolCallId = "call", content = "ok", data = it.ifEmpty { null }) },
        ),
    )
}
