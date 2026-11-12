package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.mediagen.MediaTaskState
import org.junit.jupiter.api.Test

class ProductionReviewMessageTest {
    private val strings = object : ReviewMessageStrings {
        override fun text(key: String, vararg args: Any): String = when (key) {
            "intro" -> "这是用户在制作面板里的一轮审核，逐条处理，并用 production_update 写明处理结果。"
            "title" -> "制作审核 · 第 ${args[0]} 轮"
            "approve" -> "批准"
            "reject" -> "否决"
            "suggest" -> "建议"
            "open" -> "未结事项"
            "overflow" -> "共 ${args[0]} 条，用 production_get 的 changes 范围读取。"
            "episode" -> "第 ${args[0]} 集 · ${args[1]}"
            "shot" -> "${args[0]} / ${args[1]} · ${args[2]}"
            "take" -> "${args[0]} / 候选 ${args[1]}"
            "asset" -> "${args[0]} / 版本 ${args[1]}"
            "script" -> "${args[0]} / 剧本"
            "scene" -> "${args[0]} / ${args[1]} · ${args[2]}"
            "cut" -> "${args[0]} / 剪辑 v${args[1]}"
            "change" -> "${args[0]} / 改动：${args[1]}"
            "project" -> "项目 · ${args[0]}"
            "field_title" -> "标题"
            "failed" -> "失败 ${args[0]} ${args[1]} ${args[2]}"
            "canceled" -> "升级取消 ${args[0]} ${args[1]}"
            "rejected" -> "待处理 ${args[0]}"
            "cost" -> "提醒 ${args[0]} ${args[1]}"
            else -> error(key)
        }
    }

    @Test fun `没有未发送意见则不拼装消息包括只有已发送结论`() {
        val initial = reviewFixture()
        assertThat(ProductionReviewMessage.compose(initial.production, emptyList(), strings)).isNull()
        var s = reviewApply(initial, ProductionUserAction.Review(ReviewTarget.script("S1E01"), ReviewKind.APPROVE, null))
        s = reviewApply(s, ProductionUserAction.MarkReviewSent(1, 30))
        assertThat(ProductionReviewMessage.compose(s.production, emptyList(), strings)).isNull()
    }

    @Test fun `按批准否决建议分组并列清楚对象和原因允许空原因`() {
        var s = reviewFixture()
        listOf(
            ProductionUserAction.Review(ReviewTarget.take("S1E01", "SC01-01", "t1"), ReviewKind.REJECT, "光线太暗"),
            ProductionUserAction.Review(ReviewTarget.assetVersion("char", 2), ReviewKind.APPROVE, null),
            ProductionUserAction.Review(ReviewTarget.script("S1E01"), ReviewKind.APPROVE, "  "),
            ProductionUserAction.Review(ReviewTarget.scene("S1E01", "SC01"), ReviewKind.SUGGEST, "增加雨声"),
            ProductionUserAction.Review(ReviewTarget.cut("c"), ReviewKind.SUGGEST, "停顿更长"),
            ProductionUserAction.Review(ReviewTarget.change("r"), ReviewKind.SUGGEST, "确认标题"),
        ).forEach { s = reviewApply(s, it) }
        val message = ProductionReviewMessage.compose(s.production, emptyList(), strings)!!
        assertThat(message).startsWith("这是用户在制作面板里的一轮审核")
        assertThat(message).contains("production_update")
        assertThat(message).contains("制作审核 · 第 1 轮")
        assertThat(message.indexOf("\n批准\n")).isLessThan(message.indexOf("\n否决\n"))
        assertThat(message.indexOf("\n否决\n")).isLessThan(message.indexOf("\n建议\n"))
        assertThat(message).contains("第 1 集 · 雾港 / SC01-01 · 交信 / 候选 1: 光线太暗")
        assertThat(message).contains("林晚 / 版本 2\n")
        assertThat(message).contains("雾港 / 剧本\n")
        assertThat(message).contains("SC01 · 码头 夜: 增加雨声")
        assertThat(message).contains("雾港 / 剪辑 v1: 停顿更长")
        assertThat(message).contains("改动：标题: 确认标题")
        assertThat(message).doesNotContain("null")
    }

    @Test fun `未结事项有上限并给出完整条数与读取工具`() {
        val s = reviewApply(reviewFixture(), ProductionUserAction.Review(ReviewTarget.project(), ReviewKind.SUGGEST, "先修失败"))
        val tasks = List(20) { reviewTask("task$it", MediaTaskState.FAILED, error = "错误") }
        val items = productionOpenItems(s.production, tasks, productionCostStats(s.production, tasks))
        val message = ProductionReviewMessage.compose(s.production, tasks, strings)!!
        assertThat(message).contains("未结事项")
        assertThat(message.lines().count { it.startsWith("- 失败") }).isEqualTo(ProductionReviewMessage.MAX_OPEN_ITEMS)
        assertThat(message).contains("共 ${items.size} 条")
        assertThat(message).contains("production_get 的 changes")
        assertThat(message).doesNotContain("task12 ")
    }
}
