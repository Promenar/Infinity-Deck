package com.promenar.infinity.domain.trigger

import com.google.common.truth.Truth.assertThat
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.Test

class SystemTriggerMessageTest {
    private val firedAt = OffsetDateTime.of(2026, 10, 23, 10, 30, 0, 0, ZoneOffset.ofHours(8))

    @Test fun `后台任务结束与完成通信使用步骤边界投递`() {
        assertThat(SystemTurnKind.entries.filter { it.usesSteeringDelivery }).containsExactly(
            SystemTurnKind.JOB_EXIT, SystemTurnKind.SUBAGENT_DONE, SystemTurnKind.SESSION_MESSAGE,
        )
    }

    @Test fun `合并完成通知的全部送达标识可往返且旧消息兼容`() {
        val request = SystemTurnRequest("s", SystemTurnKind.SUBAGENT_DONE, "sa_a", "结论", "正文",
            sequence = 2, deliveryIds = listOf("sa_a:2", "sa_b:1"), consumerAssistantId = "assistant")
        assertThat(SystemTriggerMessage.parse(SystemTriggerMessage.compose(request, firedAt))!!.deliveryIds)
            .containsExactly("sa_a:2", "sa_b:1").inOrder()
        assertThat(SystemTriggerMessage.parse(SystemTriggerMessage.compose(request, firedAt))!!.consumerAssistantId).isEqualTo("assistant")
        val legacy = request.copy(deliveryIds = emptyList(), consumerAssistantId = null)
        assertThat(SystemTriggerMessage.parse(SystemTriggerMessage.compose(legacy, firedAt))!!.deliveryIds).isEmpty()
        assertThat(SystemTriggerMessage.parse(SystemTriggerMessage.compose(legacy, firedAt))!!.consumerAssistantId).isNull()
    }

    @Test
    fun `标记行与正文可以往返解析`() {
        val request = SystemTurnRequest(
            sessionId = "s",
            kind = SystemTurnKind.SCHEDULE,
            sourceId = "sch_1",
            title = "检查构建",
            body = "看看 CI 是否通过\n第二行",
            sequence = 3,
            catchUp = true,
        )
        val content = SystemTriggerMessage.compose(request, firedAt)

        assertThat(content.lines().first()).startsWith("<system_trigger kind=\"schedule\"")
        val parsed = SystemTriggerMessage.parse(content)!!
        assertThat(parsed.kind).isEqualTo(SystemTurnKind.SCHEDULE)
        assertThat(parsed.sourceId).isEqualTo("sch_1")
        assertThat(parsed.title).isEqualTo("检查构建")
        assertThat(parsed.sequence).isEqualTo(3)
        assertThat(parsed.catchUp).isTrue()
        assertThat(parsed.firedAt).isEqualTo(firedAt)
        assertThat(parsed.body).isEqualTo("看看 CI 是否通过\n第二行")
    }

    @Test
    fun `标题中的引号、尖括号与换行被转义且标记行保持单行`() {
        val title = "echo \"a<b>&c\"\nrm"
        val request = SystemTurnRequest("s", SystemTurnKind.JOB_EXIT, "job_1", title, "退出码 0")
        val content = SystemTriggerMessage.compose(request, firedAt)

        assertThat(content.lines()).hasSize(2)
        val parsed = SystemTriggerMessage.parse(content)!!
        assertThat(parsed.title).isEqualTo(title)
        assertThat(parsed.sequence).isNull()
        assertThat(parsed.catchUp).isFalse()
    }

    @Test
    fun `空正文只有标记行`() {
        val content = SystemTriggerMessage.compose(SystemTurnRequest("s", SystemTurnKind.SCHEDULE, "x", "t", ""), firedAt)
        assertThat(content).doesNotContain("\n")
        assertThat(SystemTriggerMessage.parse(content)!!.body).isEmpty()
    }

    @Test
    fun `普通消息与残缺标记返回 null`() {
        listOf(
            "你好",
            "<system_trigger kind=\"unknown\" source=\"x\" fired_at=\"2026-10-23T10:30:00+08:00\"/>",
            "<system_trigger kind=\"schedule\" fired_at=\"2026-10-23T10:30:00+08:00\"/>",
            "<system_trigger kind=\"schedule\" source=\"x\" fired_at=\"昨天\"/>",
            "前缀 <system_trigger kind=\"schedule\" source=\"x\" fired_at=\"2026-10-23T10:30:00+08:00\"/>",
        ).forEach { assertThat(SystemTriggerMessage.parse(it)).isNull() }
    }
}
