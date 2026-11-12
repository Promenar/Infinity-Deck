package com.promenar.infinity.data.mediagen

import com.promenar.infinity.data.mediagen.cloud.CloudMediaModel
import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProductionMediaPlanningTest {
    private fun template(slots: List<ComfySlot> = listOf(ComfySlot("1", "frames", SlotKind.FRAMES))) = ComfyTemplate("h3",
        Json.parseToJsonElement("""{"1":{"class_type":"Generator","inputs":{"frames":121}}}""").jsonObject, slots,
        frameBase = 1, frameStep = 4, locked = true, capabilities = MediaCapabilities(setOf(GenerationMode.TEXT)), hostId = "host", group = MediaGroup.VIDEO)
    @Test fun `Comfy读取显式帧数且不推测帧率与时长`() {
        val specs = comfyPlanningSpecs(template())
        assertEquals(121, specs.frames); assertNull(specs.durationSeconds)
        assertEquals(GenerationSpecs(), comfyPlanningSpecs(template(emptyList())))
    }
    @Test fun `Comfy错误帧数在预览适配时拒绝`() {
        val invalid = template().copy(workflow = Json.parseToJsonElement("""{"1":{"class_type":"Generator","inputs":{"frames":122}}}""").jsonObject)
        assertThrows(IllegalArgumentException::class.java) { comfyPlanningSpecs(invalid) }
    }
    @Test fun `MiniMax不接受原始参考音频且本地视频不能进入云端提交`() {
        val speechSettings = mediaModelPreset("speech-02-hd")!!
        val speech = CloudMediaModel("p", "speech-02-hd", "https://example.invalid", speechSettings)
        val request = MediaGenerationRequest("speech", GenerationMode.SPEECH, "你好", listOf(MediaInput("voice.wav", InputKind.AUDIO, InputRole.REFERENCE_VOICE)), "source", sessionId = "s", outputDir = "audio")
        assertFalse(validateCloudShotPlan(speech, MediaChannel("speech", MediaGroup.AUDIO, speechSettings.capabilities), request).isEmpty())
        val videoSettings = mediaModelPreset("doubao-seedance-2-0")!!
        val video = CloudMediaModel("p", "doubao-seedance-2-0", "https://example.invalid", videoSettings)
        val videoRequest = request.copy(channelId = "video", mode = GenerationMode.REFERENCE, inputs = listOf(MediaInput("local.mp4", InputKind.VIDEO, InputRole.REFERENCE)), specs = GenerationSpecs(durationSeconds = 5))
        assertFalse(validateCloudShotPlan(video, MediaChannel("video", MediaGroup.VIDEO, videoSettings.capabilities), videoRequest).isEmpty())
    }
    @Test fun `配置指纹覆盖实际模型选项单价与工作流`() {
        val settings = mediaModelPreset("MiniMax-H3")!!
        val model = CloudMediaModel("p", "MiniMax-H3", "https://example.invalid", settings)
        assertNotEquals(cloudConfigurationFingerprint(model), cloudConfigurationFingerprint(model.copy(modelId = "another")))
        assertNotEquals(cloudConfigurationFingerprint(model), cloudConfigurationFingerprint(model.copy(settings = settings.copy(options = settings.options.copy(defaultResolution = "1080P")))))
        val priced = model.copy(settings = settings.copy(tariff = com.promenar.infinity.data.mediagen.cloud.MediaTariff("CNY", listOf(
            com.promenar.infinity.data.mediagen.cloud.MediaRate(com.promenar.infinity.data.mediagen.cloud.BillingUnit.OUTPUT_SECONDS, "1")))))
        assertNotEquals(cloudConfigurationFingerprint(model), cloudConfigurationFingerprint(priced))
        assertNotEquals(comfyConfigurationFingerprint(template(), "http://example.invalid"), comfyConfigurationFingerprint(template().copy(name = "模板二"), "http://example.invalid"))
    }
    @Test fun `解锁模板与不匹配的本地槽位在提交前拒绝`() {
        val request = MediaGenerationRequest("comfy:h3", GenerationMode.TEXT, "镜头", emptyList(), "source", sessionId = "s", outputDir = "takes")
        assertFalse(validateComfyShotPlan(template().copy(locked = false), request).isEmpty())
        assertFalse(validateComfyShotPlan(template(), request).isEmpty())
        val valid = template(listOf(ComfySlot("1", "prompt", SlotKind.PROMPT), ComfySlot("1", "frames", SlotKind.FRAMES))).copy(
            workflow = Json.parseToJsonElement("""{"1":{"class_type":"Generator","inputs":{"frames":121,"prompt":"文字"}}}""").jsonObject)
        assertTrue(validateComfyShotPlan(valid, request.copy(specs = GenerationSpecs(frames = 121))).isEmpty())
    }

    @Test fun `对齐后的分辨率必须在真实后端白名单内`() {
        val template = template(listOf(ComfySlot("1", "prompt", SlotKind.PROMPT), ComfySlot("1", "width", SlotKind.WIDTH), ComfySlot("1", "height", SlotKind.HEIGHT))).copy(
            workflow = Json.parseToJsonElement("""{"1":{"class_type":"Generator","inputs":{"width":1050,"height":1050,"prompt":"文字"}}}""").jsonObject,
            capabilities = MediaCapabilities(setOf(GenerationMode.TEXT), resolutions = listOf("1024x1024")))
        val specs = comfyPlanningSpecs(template)
        assertEquals(1056, specs.width)
        val request = MediaGenerationRequest("comfy:h3", GenerationMode.TEXT, "镜头", emptyList(), "source", specs, "s", "takes")
        assertFalse(validateComfyShotPlan(template, request).isEmpty())
    }
    @Test fun `排队期间场解锁后不可付费提交`() = kotlinx.coroutines.test.runTest {
        val store = com.promenar.infinity.data.production.PlanningStore()
        val source = com.promenar.infinity.data.production.ProductionSourceRef(store.production.id, "S1E01", "SC01-01").encode()
        val request = MediaGenerationRequest("c", GenerationMode.TEXT, "镜头", emptyList(), source, sessionId = "s", outputDir = "takes")
        assertTrue(productionRequestAvailable(request, store))
        store.production = store.production.copy(seasons = store.production.seasons.map { it.copy(episodes = it.episodes.map { ep -> ep.copy(scenes = ep.scenes.map { sc -> sc.copy(storyboardLocked = false) }) }) })
        assertFalse(productionRequestAvailable(request, store))
    }

}
