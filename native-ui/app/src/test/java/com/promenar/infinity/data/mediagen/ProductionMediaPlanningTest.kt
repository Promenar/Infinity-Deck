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
        assertFalse(validateCloudShotPlan(speech, MediaChannel("speech", MediaGroup.SPEECH, speechSettings.capabilities), request).isEmpty())
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

    @Test fun `带多档推荐预设的宽高槽位模板也允许自定义32对齐尺寸并拒绝越界宽高`() {
        val template = template(listOf(ComfySlot("1", "prompt", SlotKind.PROMPT), ComfySlot("1", "width", SlotKind.WIDTH), ComfySlot("1", "height", SlotKind.HEIGHT))).copy(
            workflow = Json.parseToJsonElement("""{"1":{"class_type":"Generator","inputs":{"width":1050,"height":1050,"prompt":"文字"}}}""").jsonObject,
            capabilities = MediaCapabilities(setOf(GenerationMode.TEXT), resolutions = listOf("768x768", "1024x1024")))
        val specs = comfyPlanningSpecs(template)
        assertEquals(1056, specs.width)
        val request = MediaGenerationRequest("comfy:h3", GenerationMode.TEXT, "镜头", emptyList(), "source", specs, "s", "takes")
        assertTrue(validateComfyShotPlan(template, request).isEmpty())

        val custom864 = MediaGenerationRequest("comfy:h3", GenerationMode.TEXT, "镜头", emptyList(), "source", GenerationSpecs(width = 864, height = 480), "s", "takes")
        assertTrue(validateComfyShotPlan(template, custom864).isEmpty())

        val tooLarge = MediaGenerationRequest("comfy:h3", GenerationMode.TEXT, "镜头", emptyList(), "source", GenerationSpecs(width = 4128, height = 480), "s", "takes")
        val errors = validateComfyShotPlan(template, tooLarge)
        assertFalse(errors.isEmpty())
        assertTrue(errors.single().contains("64～4096"))
    }

    @Test fun `单默认分辨率的宽高槽位模板允许自定义32对齐尺寸且失败时透出真实原因`() {
        val template = template(listOf(
            ComfySlot("1", "prompt", SlotKind.PROMPT),
            ComfySlot("1", "width", SlotKind.WIDTH),
            ComfySlot("1", "height", SlotKind.HEIGHT),
            ComfySlot("1", "frames", SlotKind.FRAMES),
        )).copy(
            workflow = Json.parseToJsonElement("""{"1":{"class_type":"Generator","inputs":{"width":1344,"height":768,"frames":121,"prompt":"文字"}}}""").jsonObject,
            capabilities = MediaCapabilities(setOf(GenerationMode.TEXT), resolutions = listOf("1344x768")),
        )
        for ((w, h) in listOf(864 to 480, 1920 to 1088)) {
            val shot = com.promenar.infinity.data.production.Shot("SC01-01", "镜头", durationSeconds = 5.0, specWidth = w, specHeight = h)
            val specs = comfyPlanningSpecs(template, shot)
            assertEquals(w, specs.width)
            assertEquals(h, specs.height)
            val filled = fillComfyWorkflow(
                template,
                mapOf(
                    SlotKind.PROMPT to listOf("镜头"),
                    SlotKind.WIDTH to listOf(w.toString()),
                    SlotKind.HEIGHT to listOf(h.toString()),
                    SlotKind.FRAMES to listOf("121"),
                ),
            )
            com.promenar.infinity.data.mediagen.comfy.validateFilledComfySpecs(template, filled)
            val request = MediaGenerationRequest("comfy:h3", GenerationMode.TEXT, "镜头", emptyList(), "source", specs, "s", "takes")
            assertTrue("期望 ${w}x${h} 通过校验", validateComfyShotPlan(template, request).isEmpty())
        }

        val tooSmall = MediaGenerationRequest("comfy:h3", GenerationMode.TEXT, "镜头", emptyList(), "source", GenerationSpecs(width = 32, height = 32, frames = 121), "s", "takes")
        val smallErrors = validateComfyShotPlan(template, tooSmall)
        assertTrue(smallErrors.single().contains("64～4096"))

        val unlockedErrors = validateComfyShotPlan(template.copy(locked = false), MediaGenerationRequest("comfy:h3", GenerationMode.TEXT, "镜头", emptyList(), "source", GenerationSpecs(width = 864, height = 480, frames = 121), "s", "takes"))
        assertEquals(listOf("模板尚未锁定"), unlockedErrors)

        val videoSettings = mediaModelPreset("doubao-seedance-2-0")!!
        val cloudVideo = CloudMediaModel("p", "doubao-seedance-2-0", "https://example.invalid", videoSettings)
        val localVideoReq = MediaGenerationRequest("video", GenerationMode.REFERENCE, "镜头", listOf(MediaInput("local.mp4", InputKind.VIDEO, InputRole.REFERENCE)), "source", GenerationSpecs(durationSeconds = 5), "s", "takes")
        assertEquals(listOf("云端视频参考需要已上传的素材"), validateCloudShotPlan(cloudVideo, MediaChannel("video", MediaGroup.VIDEO, videoSettings.capabilities), localVideoReq))
    }
    @Test fun `排队期间场解锁后不可付费提交`() = kotlinx.coroutines.test.runTest {
        val store = com.promenar.infinity.data.production.PlanningStore()
        val source = com.promenar.infinity.data.production.ProductionSourceRef(store.production.id, "S1E01", "SC01-01").encode()
        val request = MediaGenerationRequest("c", GenerationMode.TEXT, "镜头", emptyList(), source, sessionId = "s", outputDir = "takes")
        assertTrue(productionRequestAvailable(request, store))
        store.production = store.production.copy(seasons = store.production.seasons.map { it.copy(episodes = it.episodes.map { ep -> ep.copy(scenes = ep.scenes.map { sc -> sc.copy(storyboardLocked = false) }) }) })
        assertFalse(productionRequestAvailable(request, store))
    }

    @Test fun `配乐排队后资产删除或项目切换不再执行`() = kotlinx.coroutines.test.runTest {
        val store = com.promenar.infinity.data.production.PlanningStore()
        store.production = store.production.copy(assets = store.production.assets + com.promenar.infinity.data.production.Asset("music", com.promenar.infinity.data.production.AssetKind.SOUND, "配乐"))
        val request = MediaGenerationRequest("c", GenerationMode.MUSIC, "配乐", emptyList(), com.promenar.infinity.data.production.ProductionAssetSourceRef(store.production.id, "music").encode(), sessionId = "s", outputDir = "assets/music")
        assertTrue(productionRequestAvailable(request, store))
        store.production = store.production.copy(assets = store.production.assets.filterNot { it.id == "music" })
        assertFalse(productionRequestAvailable(request, store))
        store.production = store.production.copy(id = "other")
        assertFalse(productionRequestAvailable(request, store))
    }

    @Test fun `Comfy配置fps时按镜头时长对齐帧数并支持显式覆盖`() {
        val h3 = ComfyTemplate("h3",
            Json.parseToJsonElement("""{"1":{"class_type":"Generator","inputs":{"frames":124}}}""").jsonObject,
            listOf(ComfySlot("1", "frames", SlotKind.FRAMES)),
            frameBase = 5, frameStep = 17, maxFrames = 362, locked = true,
            capabilities = MediaCapabilities(setOf(GenerationMode.TEXT)), hostId = "host", group = MediaGroup.VIDEO, fps = 24.0)
        val shot = com.promenar.infinity.data.production.Shot("SC01-01", "镜头", durationSeconds = 4.5)
        assertEquals(107, comfyPlanningSpecs(h3, shot).frames)
        assertEquals(141, comfyPlanningSpecs(h3, shot.copy(durationSeconds = 5.88)).frames)
        assertEquals(124, comfyPlanningSpecs(h3, shot, framesOverride = 124).frames)
        assertThrows(IllegalArgumentException::class.java) { comfyPlanningSpecs(h3, shot, framesOverride = 125) }
    }

    @Test fun `单镜自定义宽高与云端分辨率作为正常生成参数生效且旧模板迁移宽高度默认为CONTENT`() {
        val wfTemplate = template(listOf(
            ComfySlot("1", "prompt", SlotKind.PROMPT),
            ComfySlot("1", "width", SlotKind.WIDTH),
            ComfySlot("1", "height", SlotKind.HEIGHT),
        )).copy(
            workflow = Json.parseToJsonElement("""{"1":{"class_type":"Generator","inputs":{"width":1344,"height":768,"prompt":"文字"}}}""").jsonObject,
        )
        val shot = com.promenar.infinity.data.production.Shot("SC01-01", "镜头", durationSeconds = 5.0, specWidth = 1920, specHeight = 1088, specResolution = "1080p")
        val comfySpecs = comfyPlanningSpecs(wfTemplate, shot)
        assertEquals(1920, comfySpecs.width)
        assertEquals(1088, comfySpecs.height)

        val migrated = ComfyWorkflow.fromLegacyTemplate(wfTemplate)
        assertEquals(FieldMutability.CONTENT, migrated.field("width")?.mutability)
        assertEquals(FieldMutability.CONTENT, migrated.field("height")?.mutability)

        val videoSettings = mediaModelPreset("doubao-seedance-2-0")!!
        val cloudModel = CloudMediaModel("p", "doubao-seedance-2-0", "https://example.invalid", videoSettings)
        val cloudSpecs = cloudPlanningSpecs(cloudModel, shot)
        assertEquals("1080p", cloudSpecs.resolution)
    }

}
