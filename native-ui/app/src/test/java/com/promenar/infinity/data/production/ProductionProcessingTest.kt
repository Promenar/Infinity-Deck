package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

internal class ProgressStore : ProductionWorkStore {
    var snapshot = reviewFixture().let { it.copy(production = it.production.copy(experimental = true,
        assets = it.production.assets + Asset("music", AssetKind.SOUND, "配乐"))) }
    var writes = 0
    var conflictNext = false
    override suspend fun load(sessionId: String) = snapshot
    override suspend fun apply(sessionId: String, snapshot: ProductionSnapshot, action: ProductionUserAction): ProductionSnapshot {
        if (conflictNext) { conflictNext = false; throw ProductionRevisionConflictException() }
        return ProductionUserEngine.apply(this.snapshot, snapshot.revision, action, 100).also { this.snapshot = it; writes++ }
    }
    fun shot() = snapshot.production.requiredShot("S1E01", "SC01-01")
}
internal class ProgressQueue : MediaGenerationQueue {
    val tasks = MutableStateFlow<List<MediaTask>>(emptyList())
    val canceled = mutableListOf<String>()
    override suspend fun submit(request: MediaGenerationRequest): MediaTask = MediaTask("q${tasks.value.size}", request).also { tasks.value += it }
    override fun observeTasks() = tasks
    override fun observeTask(id: String) = tasks.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun cancel(id: String) { canceled += id; tasks.value = tasks.value.map { if (it.id == id) it.copy(state = MediaTaskState.CANCELED) else it } }
    fun completeLast() { tasks.value = tasks.value.mapIndexed { index, task -> if (index != tasks.value.lastIndex) task else task.copy(state = MediaTaskState.COMPLETED, outputPaths = listOf("${task.request.outputDir}/seg-${task.request.productionContext?.segmentIndex ?: 0}.mp4")) } }
}
internal class ProgressRegistry(vararg val channels: MediaChannel) : MediaChannelRegistry {
    override fun observeChannels() = MutableStateFlow(channels.toList())
    override fun observeOrder(group: MediaGroup) = MutableStateFlow(channels.filter { it.group == group }.map { it.id })
    override suspend fun setOrder(group: MediaGroup, channelIds: List<String>) = Unit
    override suspend fun channel(id: String) = channels.firstOrNull { it.id == id }
}
internal fun progressTemplate(id: String = "up", role: TemplateRole = TemplateRole.DERIVE, host: String = "host"): ComfyTemplate {
    val music = role == TemplateRole.GENERATE
    return ComfyTemplate(id, Json.parseToJsonElement("""{"1":{"class_type":"Test","inputs":{"video":"x.mp4","scale":2,"prompt":"雨夜","seconds":3,"seed":1,"out":"x"}}}""").jsonObject,
        if (music) listOf(ComfySlot("1", "prompt", SlotKind.PROMPT), ComfySlot("1", "seconds", SlotKind.DURATION), ComfySlot("1", "seed", SlotKind.SEED), ComfySlot("1", "out", SlotKind.OUTPUT, outputKind = InputKind.AUDIO))
        else listOf(ComfySlot("1", "video", SlotKind.INPUT_VIDEO), ComfySlot("1", "scale", SlotKind.PARAM, param = "scale"), ComfySlot("1", "out", SlotKind.OUTPUT, outputKind = InputKind.VIDEO)),
        locked = true, capabilities = MediaCapabilities(setOf(if (music) GenerationMode.MUSIC else GenerationMode.REFERENCE)), hostId = host,
        group = if (music) MediaGroup.AUDIO else MediaGroup.VIDEO, role = role, operation = if (music) null else TemplateOperation("超分"),
        params = if (music) emptyList() else listOf(TemplateParam("scale", "倍率", TemplateParamType.NUMBER, JsonPrimitive(2), min = 1.0, max = 4.0, integer = true)))
}

class ProductionProcessingTest {
    @Test fun `能力只按首帧模式和已预检派生模板判断跨主机`() {
        val first = MediaChannel("v", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.FIRST_FRAME), minDurationSeconds = 2, maxDurationSeconds = 8))
        val a = progressTemplate("a", host = "one"); val b = progressTemplate("b", host = "two"); val missing = progressTemplate("missing")
        val result = resolveShotOperations(first, listOf(first), listOf(a, b, missing, progressTemplate("music", TemplateRole.GENERATE))) { it.id != "missing" }
        assertThat(result.extension?.maxSegments).isEqualTo(8)
        assertThat(result.extension?.minDurationSeconds).isEqualTo(2)
        assertThat(result.derivations.map { it.templateId }).containsExactly("a", "b")
        assertThat(result.derivations.first().params.single().key).isEqualTo("scale")
        val text = first.copy(capabilities = first.capabilities.copy(modes = setOf(GenerationMode.TEXT)))
        assertThat(resolveShotOperations(text, listOf(text), emptyList()) { true }.extension).isNull()
        assertThat(resolveShotOperations(null, listOf(first), emptyList()) { true }.extension).isNull()
    }
    @Test fun `派生冻结来源参数指纹实际规格并可再次派生`() = runTest {
        val store = ProgressStore(); val queue = ProgressQueue(); val template = progressTemplate()
        val processor = DefaultProductionProcessing(store, ProgressRegistry(template.channel()), queue, { listOf(ProductionTemplate(template, true)) }, { _, _ -> GenerationSpecs() }, { _, _ -> true })
        val first = processor.derive("s", "S1E01", "SC01-01", "t1", "up", mapOf("scale" to JsonPrimitive(4)))
        val actual = MediaSpec(3840, 2160, 24.0, 3.0)
        val completed = first.copy(state = MediaTaskState.COMPLETED, outputPaths = listOf("p/S1E01/takes/derived.mp4"))
        store.snapshot = store.snapshot.copy(production = requireNotNull(registerProductionTask(store.snapshot.production, completed, 30, mapOf(completed.outputPaths.single() to actual))))
        val derived = store.shot().takes.last()
        assertThat(derived.derivedFrom).isEqualTo(TakeDerivation("t1", "超分", "up", mapOf("scale" to JsonPrimitive(4))))
        assertThat(derived.spec).isEqualTo(actual)
        assertThat(derived.planSnapshot?.templateFingerprint).isEqualTo(template.fingerprint())
        assertThat(derived.planSnapshot?.params).isEqualTo(first.request.params)
        val second = processor.derive("s", "S1E01", "SC01-01", derived.id, "up")
        assertThat(second.request.productionContext?.sourceTakeId).isEqualTo(derived.id)
        assertThat(second.request.params["scale"]).isEqualTo(JsonPrimitive(2))
        assertThat(registerProductionTask(store.snapshot.production, completed, 40, mapOf(completed.outputPaths.single() to actual))).isEqualTo(store.snapshot.production)
    }
    @Test fun `配乐缺省最新剪辑时长四版种子不同并登记资产版本`() = runTest {
        val store = ProgressStore(); val queue = ProgressQueue(); val template = progressTemplate("music", TemplateRole.GENERATE)
        val latest = store.snapshot.production.cuts.single().copy(id = "later", number = 2, createdAt = 20, clips = listOf(CutClip("S1E01", "SC01-01", seconds = 7.5)))
        store.snapshot = store.snapshot.copy(production = store.snapshot.production.copy(cuts = store.snapshot.production.cuts + latest))
        val processor = DefaultProductionProcessing(store, ProgressRegistry(template.channel()), queue, { listOf(ProductionTemplate(template, true)) }, { _, _ -> GenerationSpecs() }, { _, _ -> true }, seed = { 12 })
        val tasks = processor.score("s", "music")
        assertThat(tasks).hasSize(4)
        assertThat(tasks.map { it.request.specs.seed }).containsExactly(12L, 13L, 14L, 15L)
        assertThat(tasks.map { it.request.specs.durationSecondsExact }.distinct()).containsExactly(7.5)
        var p = store.snapshot.production
        tasks.forEachIndexed { index, task ->
            val path = "p/assets/music/$index.wav"
            p = requireNotNull(registerProductionTask(p, task.copy(state = MediaTaskState.COMPLETED, outputPaths = listOf(path)), 30, mapOf(path to MediaSpec(durationSeconds = 7.48))))
        }
        val asset = p.assets.first { it.id == "music" }
        assertThat(asset.versions.map { it.seed }).containsExactly(12L, 13L, 14L, 15L)
        assertThat(asset.versions.map { it.durationSeconds }.distinct()).containsExactly(7.48)
        assertThat(asset.status).isEqualTo(AssetStatus.REVIEW)
        val locked = asset.copy(currentVersion = 1, status = AssetStatus.LOCKED)
        assertThat(locked.addProductionVersion(AssetVersion(5, "p/assets/music/5.wav", 40)).currentVersion).isEqualTo(1)
        assertThat(locked.addProductionVersion(AssetVersion(5, "p/assets/music/5.wav", 40)).status).isEqualTo(AssetStatus.LOCKED)
    }
    @Test fun `未通过预检的处理明确列出可用项`() = runTest {
        val store = ProgressStore(); val good = progressTemplate("good"); val bad = progressTemplate("bad")
        val processor = DefaultProductionProcessing(store, ProgressRegistry(), ProgressQueue(), { listOf(ProductionTemplate(good, true), ProductionTemplate(bad, false)) }, { _, _ -> GenerationSpecs() }, { _, _ -> true })
        val failure = try { processor.derive("s", "S1E01", "SC01-01", "t1", "bad"); null } catch (e: IllegalArgumentException) { e }
        assertThat(failure?.message).contains("good")
    }
}

class ProductionCapacityRegressionTest {
    @Test fun `已完成链的段任务不额外占候选活动链只预留一个`() = runTest {
        val store = ProgressStore(); val queue = ProgressQueue()
        val p = store.snapshot.production
        val chain = ShotChain("c", ChainState.PLANNED, listOf(ChainSegment(0, 3.0)), createdAt = 0, updatedAt = 0)
        queue.tasks.value = List(8) { i -> MediaTask("seg$i", MediaGenerationRequest("video", GenerationMode.TEXT, "云", emptyList(), ProductionSourceRef("p", "S1E01", "SC01-01").encode(), productionContext = ProductionMediaContext(chainId = "c", segmentIndex = i)), state = MediaTaskState.COMPLETED, outputPaths = listOf("p/$i.mp4")) }
        assertThat(occupiedShotCandidates(p, "S1E01", "SC01-01", queue)).isEqualTo(2)
        val active = p.mapShot("S1E01", "SC01-01") { it.copy(chains = listOf(chain)) }
        assertThat(occupiedShotCandidates(active, "S1E01", "SC01-01", queue)).isEqualTo(3)
    }
    @Test fun `双输出派生在二十九个候选时拒绝提交`() = runTest {
        val store = ProgressStore(); val queue = ProgressQueue()
        store.snapshot = store.snapshot.copy(production = store.snapshot.production.mapShot("S1E01", "SC01-01") { shot -> shot.copy(takes = List(29) { Take("t$it", "p/$it.mp4", TakeSource("源"), 1) }) })
        val t = progressTemplate().let { it.copy(slots = it.slots + ComfySlot("1", "another", SlotKind.OUTPUT, outputKind = InputKind.VIDEO)) }
        val service = DefaultProductionProcessing(store, ProgressRegistry(t.channel()), queue, { listOf(ProductionTemplate(t, true)) }, { _, _ -> GenerationSpecs() }, { _, _ -> true })
        val error = try { service.derive("s", "S1E01", "SC01-01", "t1", "up"); null } catch (e: IllegalArgumentException) { e }
        assertThat(error?.message).contains("容量不足")
        assertThat(queue.tasks.value).isEmpty()
    }
}

class ProductionTemplatePlanningRegressionTest {
    @Test fun `纯派生模板可无提示词槽位并校验实际参数`() {
        val template = progressTemplate()
        val request = MediaGenerationRequest(template.channelId, GenerationMode.REFERENCE, "超分", listOf(MediaInput("p/1.mp4", InputKind.VIDEO, com.promenar.infinity.domain.mediagen.InputRole.REFERENCE)), "source", params = mapOf("scale" to JsonPrimitive(4)))
        assertThat(com.promenar.infinity.data.mediagen.validateComfyShotPlan(template, request)).isEmpty()
        assertThat(com.promenar.infinity.data.mediagen.validateComfyShotPlan(template, request.copy(params = mapOf("unknown" to JsonPrimitive(1))))).isNotEmpty()
    }
    @Test fun `小数秒数遵守通道时长范围且不接受非有限值`() {
        val channel = MediaChannel("music", MediaGroup.AUDIO, MediaCapabilities(setOf(GenerationMode.MUSIC), minDurationSeconds = 2, maxDurationSeconds = 10))
        val request = MediaGenerationRequest("music", GenerationMode.MUSIC, "雨夜", emptyList(), "source", specs = GenerationSpecs(durationSecondsExact = 3.5))
        assertThat(validateRequest(channel, request).valid).isTrue()
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 1.5, 10.5).forEach { seconds ->
            assertThat(validateRequest(channel, request.copy(specs = GenerationSpecs(durationSecondsExact = seconds))).specErrors).contains("durationSecondsExact")
        }
    }
}
