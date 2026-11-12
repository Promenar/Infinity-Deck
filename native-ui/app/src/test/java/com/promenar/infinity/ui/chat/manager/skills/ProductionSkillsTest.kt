package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.*
import com.promenar.infinity.data.repository.ProductionRepository
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class ProductionSkillsTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
        override val assistantMessageId = "m"
    }
    @Test fun `参数错误返回结构化错误与风险分类`() = runBlocking {
        val repo = mockk<ProductionRepository>()
        val result = ProductionDefineSkill(repo).execute(buildJsonObject { put("title", "影片") }, context)
        assertThat(result.status).isEqualTo("error")
        val error = Json.parseToJsonElement(result.content).jsonObject
        assertThat(error.keys).containsAtLeast("error", "message", "suggestion")
        assertThat(ProductionGetSkill(repo).risk).isEqualTo(ToolRisk.SAFE_READ)
        assertThat(ProductionUpdateSkill(repo).risk).isEqualTo(ToolRisk.SESSION_STATE)
    }
    @Test fun `五个工具调用对应仓库并返回结果`() = runBlocking {
        val repo = mockk<ProductionRepository>(relaxed = true)
        val p = Production("p", "项目", ProductionKind.FILM, "productions/p", createdAt = 1, updatedAt = 1)
        val snapshot = ProductionSnapshot(p, 2, ManifestSync.InSync)
        io.mockk.coEvery { repo.get("s") } returns snapshot
        io.mockk.coEvery { repo.define("s", any(), any(), any(), any(), any()) } returns snapshot
        io.mockk.coEvery { repo.attach("s", any()) } returns snapshot
        io.mockk.coEvery { repo.update("s", "m", any()) } returns snapshot
        val cases = listOf(
            ProductionDefineSkill(repo) to """{"title":"项目","kind":"film"}""",
            ProductionAttachSkill(repo) to """{"manifest_path":"productions/p/production.json"}""",
            ProductionGetSkill(repo) to """{"scope":"overview"}""",
            ProductionUpdateSkill(repo) to """{"operations":[{"op":"set_preview_enabled","enabled":true}]}""",
            ProductionRemoveSkill(repo) to "{}",
        )
        cases.forEach { (skill, args) -> assertThat(skill.execute(Json.parseToJsonElement(args).jsonObject, context).status).isEqualTo("success") }
        io.mockk.verify { repo.recordContextRead("s", "m", 2, "p") }
        io.mockk.coVerify { repo.unbind("s") }
    }

    @Test fun `按范围读取并限制输出字节`() {
        val shot = Shot("SC01-01", "镜头", durationSeconds = 1.0, prompt = "字".repeat(7000))
        val p = Production("p", "项目", ProductionKind.SERIES, "productions/p", createdAt = 1, updatedAt = 1,
            seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "集", scenes = listOf(Scene("SC01", 1, "场", shots = listOf(shot))))))))
        val snap = ProductionSnapshot(p, 2, ManifestSync.InSync)
        val many = snap.copy(production = p.copy(assets = List(500) { Asset("a$it", AssetKind.PROP, "字".repeat(120)) }))
        val limited = productionGetResult(many, "assets", null, null)
        assertThat(limited.toByteArray(Charsets.UTF_8).size).isAtMost(24 * 1024)
        assertThat(Json.parseToJsonElement(limited).jsonObject.getValue("truncated").jsonPrimitive.boolean).isTrue()
        for (scope in listOf("overview", "episode", "shot", "assets")) {
            val text = productionGetResult(snap, scope, "S1E01", "SC01-01")
            assertThat(text.toByteArray(Charsets.UTF_8).size).isAtMost(24 * 1024)
            assertThat(Json.parseToJsonElement(text).jsonObject.getValue("revision").jsonPrimitive.long).isEqualTo(2)
        }
    }

    @Test fun `变更请求可读取且错误范围给出提示`() {
        val request = ShotChangeRequest("r", "S1E01", "SC01", "SC01-01", mapOf("title" to ShotFieldChange(JsonPrimitive("旧"), JsonPrimitive("新"))), ChangeSource.AGENT)
        val p = Production("p", "项目", ProductionKind.FILM, "p", createdAt = 1, updatedAt = 1, changeRequests = listOf(request))
        val snap = ProductionSnapshot(p, 1, ManifestSync.InSync)
        assertThat(productionGetResult(snap, "changes", null, null)).contains("SC01-01")
        val error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { productionGetResult(snap, "unknown", null, null) }
        assertThat(error.message).contains("changes")
    }

    @Test fun `读取结果包含默认状态字段`() {
        val p = Production("p", "项目", ProductionKind.FILM, "productions/p", createdAt = 1, updatedAt = 1,
            seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "集", scenes = listOf(
                Scene("SC01", 1, "场", shots = listOf(Shot("SC01-01", "镜头", durationSeconds = 1.0))),
            ))))))
        val shot = Json.parseToJsonElement(productionGetResult(ProductionSnapshot(p, 3, ManifestSync.InSync), "shot", "S1E01", "SC01-01"))
            .jsonObject.getValue("content").jsonObject
        assertThat(shot.getValue("status").jsonPrimitive.content).isEqualTo("todo")
        assertThat(shot.getValue("mode").jsonPrimitive.content).isEqualTo("text")
        val overview = Json.parseToJsonElement(productionGetResult(ProductionSnapshot(p, 3, ManifestSync.InSync), "overview", null, null))
            .jsonObject.getValue("content").jsonObject
        assertThat(overview.getValue("stages").jsonArray.first().jsonObject.getValue("status").jsonPrimitive.content).isEqualTo("not_started")
    }

    @Test fun `制作读取返回阶段次数镜头资产提醒和最近三轮未结事项`() {
        val take = Take("t", "p/t.mp4", TakeSource("服务"), 1)
        val shot = Shot("SC01-01", "镜头", durationSeconds = 1.0, takes = listOf(take))
        val target = ReviewTarget.take("S1E01", "SC01-01", "t")
        val entry = ReviewEntry(target, ReviewKind.REJECT, "原因", 1, round = 1, sentAt = 2, sentRevision = 2)
        val p = Production("p", "项目", ProductionKind.FILM, "p", createdAt = 1, updatedAt = 2,
            seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "集", scenes = listOf(Scene("SC01", 1, "场", shots = listOf(shot))))))),
            assets = listOf(Asset("a", AssetKind.PROP, "道具", versions = listOf(AssetVersion(1, "p/a.png", 1)))),
            reviews = listOf(entry), nextReviewRound = 6,
            reviewRounds = (5 downTo 1).map { ReviewRound(it, it.toLong(), if (it == 1) listOf(entry) else emptyList(), it.toLong()) })
        val tasks = List(6) { com.promenar.infinity.domain.mediagen.MediaTask("task$it",
            com.promenar.infinity.domain.mediagen.MediaGenerationRequest("channel", com.promenar.infinity.domain.mediagen.GenerationMode.TEXT, "描述", emptyList(), ProductionSourceRef("p", "S1E01", "SC01-01").encode()),
            state = com.promenar.infinity.domain.mediagen.MediaTaskState.FAILED, error = "失败") } + List(6) {
            com.promenar.infinity.domain.mediagen.MediaTask("asset$it", com.promenar.infinity.domain.mediagen.MediaGenerationRequest("channel", com.promenar.infinity.domain.mediagen.GenerationMode.TEXT, "描述", emptyList(), ProductionAssetSourceRef("p", "a").encode()), state = com.promenar.infinity.domain.mediagen.MediaTaskState.COMPLETED)
        }
        val canceled = com.promenar.infinity.domain.mediagen.MediaTask("canceled", com.promenar.infinity.domain.mediagen.MediaGenerationRequest("channel", com.promenar.infinity.domain.mediagen.GenerationMode.TEXT, "描述", emptyList(), ProductionSourceRef("p", "S1E01", "SC01-01").encode()), state = com.promenar.infinity.domain.mediagen.MediaTaskState.CANCELED, error = "升级后取消")
        val foreign = tasks.first().copy(id = "foreign", request = tasks.first().request.copy(sourceRef = ProductionSourceRef("foreign", "S1E01", "SC01-01").encode()))
        val snapshot = ProductionSnapshot(p, 10, ManifestSync.InSync)
        fun content(scope: String) = Json.parseToJsonElement(productionGetResult(snapshot, scope, "S1E01", "SC01-01", tasks + canceled + foreign)).jsonObject.getValue("content")
        val overview = content("overview").jsonObject
        assertThat(overview.getValue("generation_counts").jsonObject.getValue("generation").jsonObject.getValue("failed").jsonPrimitive.int).isEqualTo(6)
        assertThat(overview.getValue("alert_shots").jsonArray.single().jsonObject.getValue("shot_code").jsonPrimitive.content).isEqualTo("SC01-01")
        assertThat(overview.getValue("alert_assets").jsonArray.single().jsonObject.getValue("asset_id").jsonPrimitive.content).isEqualTo("a")
        assertThat(overview.keys).doesNotContain("reviewDrafts")
        assertThat(content("shot").jsonObject.getValue("generation_count").jsonObject.getValue("total").jsonPrimitive.int).isEqualTo(7)
        assertThat(content("assets").jsonArray.single().jsonObject.getValue("generation_count").jsonObject.getValue("total").jsonPrimitive.int).isEqualTo(7)
        val changes = content("changes").jsonObject
        assertThat(changes.keys).containsExactly("change_requests", "review_rounds", "open_items")
        assertThat(changes.getValue("review_rounds").jsonArray.map { it.jsonObject.getValue("number").jsonPrimitive.int }).containsExactly(5, 4, 3).inOrder()
        val items = changes.getValue("open_items").jsonArray.map { it.jsonObject }
        assertThat(items.map { it.getValue("kind").jsonPrimitive.content }).containsAtLeast("failed_task", "canceled_task", "rejected", "cost_alert")
        assertThat(items.any { it["task_id"] == JsonPrimitive("foreign") }).isFalse()
        assertThat(items.first { it["kind"] == JsonPrimitive("rejected") }.getValue("target").jsonObject.keys).contains("take_id")
    }

    @Test fun `制作读取工具使用真实任务供应入口`() = runBlocking {
        val repo = mockk<ProductionRepository>(relaxed = true)
        val p = Production("p", "项目", ProductionKind.FILM, "p", createdAt = 1, updatedAt = 1)
        io.mockk.coEvery { repo.get("s") } returns ProductionSnapshot(p, 1, ManifestSync.InSync)
        var reads = 0
        val skill = ProductionGetSkill(repo) { reads++; emptyList() }
        assertThat(skill.execute(buildJsonObject { put("scope", "changes") }, context).status).isEqualTo("success")
        assertThat(reads).isEqualTo(1)
        assertThat(skill.description).contains("open_items")
        assertThat(ProductionUpdateSkill(repo).description).doesNotContain("set_budget")
    }
}
