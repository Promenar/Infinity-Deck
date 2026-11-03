package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationFailureCodec
import com.promenar.infinity.ui.common.status.UiStatusNotice
import java.io.File
import org.junit.jupiter.api.Test

class GenerationFailureNoticeTest {

    @Test
    fun `稳定失败码只映射对应资源`() {
        val expected = mapOf(
            GenerationFailureCode.NETWORK to R.string.generation_failure_network,
            GenerationFailureCode.AUTH to R.string.generation_failure_auth,
            GenerationFailureCode.RATE_LIMIT to R.string.generation_failure_rate_limit,
            GenerationFailureCode.QUOTA to R.string.generation_failure_quota,
            GenerationFailureCode.TIMEOUT to R.string.generation_failure_timeout,
            GenerationFailureCode.INVALID_REQUEST to R.string.generation_failure_invalid_request,
            GenerationFailureCode.CONTEXT_LIMIT to R.string.chat_document_error_invalid_estimate,
            GenerationFailureCode.OUTPUT_LIMIT to R.string.generation_failure_output_limit,
            GenerationFailureCode.CONTENT_FILTERED to R.string.generation_failure_content_filtered,
            GenerationFailureCode.SERVER to R.string.generation_failure_server,
            GenerationFailureCode.BUSY to R.string.generation_failure_busy,
            GenerationFailureCode.PERSISTENCE to R.string.generation_failure_persistence,
            GenerationFailureCode.TOOL_BUDGET_EXHAUSTED to R.string.generation_failure_tool_budget,
            GenerationFailureCode.TAILNET_UNAVAILABLE to R.string.generation_failure_tailnet_failed,
            GenerationFailureCode.RUN_MODE_REQUIRED to R.string.run_mode_required,
            GenerationFailureCode.LINUX_UNAVAILABLE to R.string.run_mode_linux_unavailable,
            GenerationFailureCode.EMPTY_REPLY to R.string.exec_steer_empty_reply,
            GenerationFailureCode.UNKNOWN to R.string.generation_failure_unknown,
        )

        val actual = GenerationFailureCode.entries.associateWith { code ->
            GenerationFailureNotice.template(
                GenerationFailureNotice.from(GenerationFailure(code)),
            ).resourceId
        }

        assertThat(actual).containsExactlyEntriesIn(expected)
    }

    @Test
    fun `上下文超限只接受完整的数值参数`() {
        val resolved = GenerationFailureNotice.template(
            GenerationFailureNotice.from(
                GenerationFailure(
                    GenerationFailureCode.CONTEXT_LIMIT,
                    mapOf(
                        GenerationFailure.KEY_REQUIRED_TOKENS to "9000",
                        GenerationFailure.KEY_AVAILABLE_TOKENS to "8000",
                    ),
                ),
            ),
        )

        assertThat(resolved.resourceId).isEqualTo(R.string.chat_document_error_over_capacity)
        assertThat(resolved.args).containsExactly(9000L, 8000L).inOrder()
    }

    @Test
    fun `未携带完整文档的上下文超限引导压缩而不是移除文档`() {
        fun resolve(documents: String) = GenerationFailureNotice.template(
            GenerationFailureNotice.from(
                GenerationFailure(
                    GenerationFailureCode.CONTEXT_LIMIT,
                    mapOf(
                        GenerationFailure.KEY_REQUIRED_TOKENS to "230000",
                        GenerationFailure.KEY_AVAILABLE_TOKENS to "224134",
                        GenerationFailure.KEY_FULL_CONTEXT_DOCUMENTS to documents,
                    ),
                ),
            ),
        )

        val conversation = resolve("false")
        assertThat(conversation.resourceId).isEqualTo(R.string.chat_context_error_over_capacity)
        assertThat(conversation.args).containsExactly(230000L, 224134L).inOrder()
        assertThat(resolve("true").resourceId).isEqualTo(R.string.chat_document_error_over_capacity)
    }

    @Test
    fun `上下文超限按原因区分当前轮、窗口未知与上游超限`() {
        fun resolve(reason: String, documents: String = "true") = GenerationFailureNotice.template(
            GenerationFailureNotice.from(
                GenerationFailure(
                    GenerationFailureCode.CONTEXT_LIMIT,
                    mapOf(
                        GenerationFailure.KEY_CONTEXT_LIMIT_REASON to reason,
                        GenerationFailure.KEY_REQUIRED_TOKENS to "312000",
                        GenerationFailure.KEY_AVAILABLE_TOKENS to "118000",
                        GenerationFailure.KEY_FULL_CONTEXT_DOCUMENTS to documents,
                    ),
                ),
            ),
        )

        val turnDocuments = resolve("CURRENT_TURN")
        assertThat(turnDocuments.resourceId).isEqualTo(R.string.chat_context_error_turn_documents)
        assertThat(turnDocuments.args).containsExactly(312000L, 118000L).inOrder()
        assertThat(resolve("CURRENT_TURN", "false").resourceId).isEqualTo(R.string.chat_context_error_turn)
        assertThat(resolve("UNKNOWN_WINDOW").resourceId).isEqualTo(R.string.chat_document_error_unknown_capacity)
        assertThat(resolve("UNKNOWN_WINDOW").args).isEmpty()
        assertThat(resolve("UPSTREAM").resourceId).isEqualTo(R.string.chat_context_error_upstream)
        assertThat(resolve("TOTAL_INPUT").resourceId).isEqualTo(R.string.chat_document_error_over_capacity)
        assertThat(resolve("TOTAL_INPUT", "false").resourceId).isEqualTo(R.string.chat_context_error_over_capacity)
    }

    @Test
    fun `只有空回复失败显示重试与换模型`() {
        assertThat(GenerationFailureNotice.isEmptyReply(GenerationFailureNotice.from(GenerationFailure(GenerationFailureCode.EMPTY_REPLY)))).isTrue()
        assertThat(GenerationFailureNotice.isEmptyReply(GenerationFailureNotice.from(GenerationFailure(GenerationFailureCode.NETWORK)))).isFalse()
        assertThat(GenerationFailureNotice.isEmptyReply(GenerationFailureNotice.fromPersisted("@infinity/failure:EMPTY_REPLY"))).isTrue()
    }

    @Test
    fun `限流只在正数重试秒数存在时使用带参数资源`() {
        val withRetry = GenerationFailureNotice.template(
            GenerationFailureNotice.from(GenerationFailure.rateLimited(37)),
        )
        val withoutRetry = GenerationFailureNotice.template(
            GenerationFailureNotice.from(GenerationFailure.rateLimited(null)),
        )
        val invalidRetry = GenerationFailureNotice.template(
            GenerationFailureNotice.from(
                GenerationFailure(
                    GenerationFailureCode.RATE_LIMIT,
                    mapOf(GenerationFailure.KEY_RETRY_AFTER_SECONDS to "-1"),
                ),
            ),
        )

        assertThat(withRetry.resourceId).isEqualTo(R.string.generation_failure_rate_limit_retry)
        assertThat(withRetry.args).containsExactly(37)
        assertThat(withoutRetry.resourceId).isEqualTo(R.string.generation_failure_rate_limit)
        assertThat(withoutRetry.args).isEmpty()
        assertThat(invalidRetry.resourceId).isEqualTo(R.string.generation_failure_rate_limit)
        assertThat(invalidRetry.args).isEmpty()
    }

    @Test
    fun `诊断信息异常原因和任意裸字符串都不会进入展示参数`() {
        val secretTechnical = "provider=internal session=secret-session"
        val secretCause = IllegalStateException("raw upstream exception")
        val resolved = GenerationFailureNotice.template(
            GenerationFailureNotice.from(
                GenerationFailure(
                    code = GenerationFailureCode.SERVER,
                    formatArgs = mapOf("unexpected" to "do-not-display"),
                    technical = secretTechnical,
                    cause = secretCause,
                ),
            ),
        )
        val rawFallback = GenerationFailureNotice.template(
            GenerationFailureNotice.fromPersisted("裸露的旧错误 provider-internal"),
        )

        assertThat(resolved.resourceId).isEqualTo(R.string.generation_failure_server)
        assertThat(resolved.args).isEmpty()
        assertThat(resolved.args.joinToString()).doesNotContain(secretTechnical)
        assertThat(resolved.args.joinToString()).doesNotContain(secretCause.message!!)
        assertThat(rawFallback.resourceId).isEqualTo(R.string.generation_failure_unknown)
        assertThat(rawFallback.args).isEmpty()
    }

    @Test
    fun `持久化信封可恢复而非信封损坏信封和fixture稳定码安全回落`() {
        val envelope = GenerationFailureCodec.encode(GenerationFailure.rateLimited(12))

        assertThat(
            GenerationFailureNotice.template(
                GenerationFailureNotice.fromPersisted(envelope),
            ),
        ).isEqualTo(
            com.promenar.infinity.ui.common.status.ResolvedStatus(
                R.string.generation_failure_rate_limit_retry,
                listOf(12),
            ),
        )

        listOf(
            null,
            "",
            "NETWORK",
            "@infinity/failure:NOT_A_REAL_CODE",
            "@infinity/failure:RATE_LIMIT?retryAfterSeconds=%ZZ",
            "@infinity/failure:RATE_LIMIT?",
            "@infinity/failure:RATE_LIMIT?technical=provider-private",
            "@infinity/failure:RATE_LIMIT?retryAfterSeconds=1&retryAfterSeconds=2",
        ).forEach { raw ->
            val resolved = GenerationFailureNotice.template(
                GenerationFailureNotice.fromPersisted(raw),
            )
            assertThat(resolved.resourceId).isEqualTo(R.string.generation_failure_unknown)
            assertThat(resolved.args).isEmpty()
        }
    }

    @Test
    fun `未知notice code由安全通用资源兜底`() {
        val resolved = GenerationFailureNotice.template(
            UiStatusNotice(
                severity = com.promenar.infinity.ui.common.status.NoticeSeverity.Error,
                code = "generation.failure.future_code",
                formatArgs = listOf("internal-code"),
                technical = "internal-technical",
            ),
        )

        assertThat(resolved.resourceId).isEqualTo(R.string.generation_failure_unknown)
        assertThat(resolved.args).isEmpty()
    }

    @Test
    fun `服务商原因只作为补充说明并能从历史信封还原`() {
        val failure = com.promenar.infinity.domain.generation.GenerationFailure.rateLimited(30).let {
            it.copy(formatArgs = it.formatArgs + (com.promenar.infinity.domain.generation.GenerationFailure.KEY_PROVIDER_MESSAGE to "当前分组上游负载已饱和"))
        }
        val live = GenerationFailureNotice.from(failure)
        assertThat(live.detail).isEqualTo("当前分组上游负载已饱和")
        assertThat(GenerationFailureNotice.template(live).args).containsExactly(30)

        val persisted = GenerationFailureNotice.fromPersisted(com.promenar.infinity.domain.generation.GenerationFailureCodec.encode(failure))
        assertThat(persisted.detail).isEqualTo("当前分组上游负载已饱和")
        assertThat(persisted.code).isEqualTo(live.code)
        assertThat(GenerationFailureNotice.fromPersisted("@infinity/failure:SERVER").detail).isNull()
    }

    @Test
    fun `中英文生成失败资源key完全一致`() {
        val english = resourceFile("values/strings.xml").readText()
        val chinese = resourceFile("values-zh-rCN/strings.xml").readText()
        val generationKey = Regex("<string name=\"(generation_failure_[^\"]+)\"")
        val englishKeys = generationKey.findAll(english).map { it.groupValues[1] }.toSet()
        val chineseKeys = generationKey.findAll(chinese).map { it.groupValues[1] }.toSet()

        assertThat(englishKeys).containsExactlyElementsIn(REQUIRED_RESOURCE_KEYS)
        assertThat(chineseKeys).containsExactlyElementsIn(englishKeys)
    }

    @Test
    fun `tailnet 不可用按原因映射登录、超时与连接失败说明`() {
        fun resource(reason: String?) = GenerationFailureNotice.template(
            GenerationFailureNotice.from(
                GenerationFailure(
                    GenerationFailureCode.TAILNET_UNAVAILABLE,
                    formatArgs = reason?.let { mapOf(GenerationFailure.KEY_TAILNET_REASON to it) }.orEmpty(),
                ),
            ),
        ).resourceId
        assertThat(resource("NEEDS_LOGIN")).isEqualTo(R.string.generation_failure_tailnet_needs_login)
        assertThat(resource("DISABLED")).isEqualTo(R.string.generation_failure_tailnet_disabled)
        assertThat(resource("TIMEOUT")).isEqualTo(R.string.generation_failure_tailnet_timeout)
        assertThat(resource("FAILED")).isEqualTo(R.string.generation_failure_tailnet_failed)
        assertThat(resource("unexpected")).isEqualTo(R.string.generation_failure_tailnet_failed)
    }

    @Test
    fun `历史消息中的 tailnet 失败还原出登录或重试入口，其它失败没有入口`() {
        fun kind(raw: String?) = GenerationFailureNotice.tailnetKind(GenerationFailureNotice.fromPersisted(raw))
        assertThat(kind("@infinity/failure:TAILNET_UNAVAILABLE?tailnetReason=NEEDS_LOGIN"))
            .isEqualTo(TailnetFailureKind.NEEDS_LOGIN)
        assertThat(kind("@infinity/failure:TAILNET_UNAVAILABLE?tailnetReason=DISABLED"))
            .isEqualTo(TailnetFailureKind.DISABLED)
        assertThat(kind("@infinity/failure:TAILNET_UNAVAILABLE?tailnetReason=TIMEOUT"))
            .isEqualTo(TailnetFailureKind.UNREACHABLE)
        assertThat(kind("@infinity/failure:TAILNET_UNAVAILABLE")).isEqualTo(TailnetFailureKind.UNREACHABLE)
        assertThat(kind("@infinity/failure:NETWORK")).isNull()
        assertThat(kind("raw provider text")).isNull()
    }

    private fun resourceFile(relativePath: String): File {
        val root = File(System.getProperty("user.dir") ?: ".")
        val appRoot = if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        return appRoot.resolve("src/main/res/$relativePath")
    }

    private companion object {
        val REQUIRED_RESOURCE_KEYS = setOf(
            "generation_failure_provider_detail",
            "generation_failure_network",
            "generation_failure_auth",
            "generation_failure_rate_limit",
            "generation_failure_rate_limit_retry",
            "generation_failure_quota",
            "generation_failure_timeout",
            "generation_failure_invalid_request",
            "generation_failure_output_limit",
            "generation_failure_content_filtered",
            "generation_failure_server",
            "generation_failure_busy",
            "generation_failure_persistence",
            "generation_failure_tool_budget",
            "generation_failure_tailnet_disabled",
            "generation_failure_tailnet_needs_login",
            "generation_failure_tailnet_failed",
            "generation_failure_tailnet_timeout",
            "generation_failure_unknown",
        )
    }
}
