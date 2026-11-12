package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.mediagen.InputRole as MediaRole
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShotGenerationPlannerTest {
    private val store = PlanningStore()
    private val files = PlanningFiles()
    private val queue = PlanningQueue()
    private fun TestScope.planner(registry: MediaChannelRegistry = PlanningRegistry(planningChannel())) = DefaultShotGenerationPlanner(store, registry, queue, files,
        CachedCharacterCompositor(files, HorizontalImageCodec { parts -> parts.reduce { a, b -> a + b } }), ioDispatcher = StandardTestDispatcher(testScheduler))
    private fun shot() = store.production.seasons.single().episodes.single().scenes.single().shots.single()
    private fun edit(transform: (Shot) -> Shot) { store.production = store.production.mapShot("S1E01", "SC01-01", transform) }

    @Test fun `默认通道取视频组首个支持当前模式的通道`() = runTest {
        val unsupported = planningChannel("no").copy(capabilities = MediaCapabilities(setOf(GenerationMode.TEXT)))
        val registry = PlanningRegistry(unsupported, planningChannel("chosen"), planningChannel("later"))
        assertEquals("chosen", planner(registry).preview("s", "S1E01", "SC01-01").channel?.id)
        assertTrue(planner(registry).preview("s", "S1E01", "SC01-01").channelIsDefault)
        edit { it.copy(channelId = "later") }
        assertFalse(planner(registry).preview("s", "S1E01", "SC01-01").channelIsDefault)
        edit { it.copy(channelId = "missing") }
        assertNull(planner(registry).preview("s", "S1E01", "SC01-01").channel)
    }
    @Test fun `分张按通道顺序只包含选中参考位并接场景`() = runTest {
        val result = planner().preview("s", "S1E01", "SC01-01")
        assertEquals(listOf("ref/back.png", "ref/front.png", "ref/pier.png"), result.inputs.map { it.path })
        assertEquals(listOf(1, 2, 3), result.inputs.map { it.number })
        result.inputs.forEach { assertTrue(result.prompt.contains("图${it.number}")) }
        assertTrue(result.warnings.isNotEmpty())
        assertNull(result.blockedReason)
    }
    @Test fun `合图按默认参考位顺序且预览无写入`() = runTest {
        val result = planner(PlanningRegistry(planningChannel(pack = CharacterReferenceMode.COMPOSITE))).preview("s", "S1E01", "SC01-01")
        assertEquals(listOf("ref/front.png", "ref/back.png"), result.inputs.first().compositeOf)
        assertNull(result.inputs.first().path)
        assertEquals(0, files.writes)
    }
    @Test fun `超限清单保留图片与语音且提交拒绝`() = runTest {
        edit { it.copy(dialogue = listOf(ShotDialogue("lin", "谁在那里", "紧张"))) }
        val channel = planningChannel(max = 1).let { it.copy(capabilities = it.capabilities.copy(maxAudio = 0)) }
        val planner = planner(PlanningRegistry(channel))
        val preview = planner.preview("s", "S1E01", "SC01-01")
        assertEquals(listOf("ref/front.png", "ref/pier.png", "ref/voice.wav"), preview.overflow.map { it.path })
        assertTrue((planner.generate("s", "S1E01", "SC01-01") as ShotGenerateResult.Rejected).reason.contains("ref/voice.wav"))
        assertTrue(queue.tasks.value.isEmpty())
    }
    @Test fun `用户改写同时用于预览和实际入队且重新编译是显式操作`() = runTest {
        edit { it.copy(prompt = "用户指定的文本", promptUserEdited = true) }
        val planner = planner()
        assertEquals("用户指定的文本", planner.preview("s", "S1E01", "SC01-01").prompt)
        planner.generate("s", "S1E01", "SC01-01")
        assertEquals("用户指定的文本", queue.tasks.value.single().request.prompt)
        assertTrue(shot().promptUserEdited)
        val compiled = planner.recompilePrompt("s", "S1E01", "SC01-01")
        assertEquals(compiled, shot().prompt)
        assertFalse(shot().promptUserEdited)
    }
    @Test fun `未锁场阻断而试验项目允许`() = runTest {
        store.production = store.production.copy(seasons = store.production.seasons.map { it.copy(episodes = it.episodes.map { ep -> ep.copy(scenes = ep.scenes.map { sc -> sc.copy(storyboardLocked = false) }) }) })
        assertNotNull(planner().preview("s", "S1E01", "SC01-01").blockedReason)
        store.production = store.production.copy(experimental = true)
        assertNull(planner().preview("s", "S1E01", "SC01-01").blockedReason)
    }
    @Test fun `实际合图路径与来源引用写入队列`() = runTest {
        val result = planner(PlanningRegistry(planningChannel(pack = CharacterReferenceMode.COMPOSITE))).generate("s", "S1E01", "SC01-01") as ShotGenerateResult.Submitted
        val request = queue.tasks.value.single().request
        assertEquals(result.taskId, queue.tasks.value.single().id)
        assertEquals("production:p%2F1/episode:S1E01/shot:SC01-01", request.sourceRef)
        assertEquals("productions/fog/S1E01/takes", request.outputDir)
        assertTrue(request.inputs.first().location.startsWith("productions/fog/derived/character-sheets/"))
        assertEquals(1, files.writes)
    }
    @Test fun `首尾帧按职责排序且缺帧阻断`() = runTest {
        edit { it.copy(mode = ShotMode.FIRST_LAST, inputs = listOf(ShotInput(InputRole.LAST_FRAME, "ref/back.png"), ShotInput(InputRole.FIRST_FRAME, "ref/front.png"))) }
        val preview = planner().preview("s", "S1E01", "SC01-01")
        assertEquals(listOf(MediaRole.FIRST_FRAME, MediaRole.LAST_FRAME), preview.inputs.map { it.role })
        assertNull(preview.blockedReason)
        edit { it.copy(inputs = it.inputs.take(1)) }
        assertNotNull(planner().preview("s", "S1E01", "SC01-01").blockedReason)
    }
    @Test fun `配音提交独立语音任务并排除原生对白`() = runTest {
        edit { it.copy(dialogue = listOf(ShotDialogue("lin", "门开着", "低声", DialogueAudioSource.DUBBED))) }
        val speech = MediaChannel("speech", MediaGroup.AUDIO, MediaCapabilities(setOf(GenerationMode.SPEECH), maxAudio = 1))
        val result = planner(PlanningRegistry(planningChannel(), speech)).generate("s", "S1E01", "SC01-01") as ShotGenerateResult.Submitted
        assertEquals(2, queue.tasks.value.size)
        val audio = queue.tasks.value.last()
        assertEquals(GenerationMode.SPEECH, audio.request.mode)
        assertEquals("门开着", audio.request.prompt)
        assertEquals(listOf(MediaInput("ref/voice.wav", InputKind.AUDIO, MediaRole.REFERENCE_VOICE)), audio.request.inputs)
        assertTrue(audio.request.sourceRef.endsWith("/dialogue:0"))
        assertEquals(listOf(audio.id), result.audioTaskIds)
        assertFalse(queue.tasks.value.first().request.prompt.contains("门开着"))
    }
    @Test fun `未选择参考位不隐式补入默认图片`() = runTest {
        edit { it.copy(appearances = listOf(ShotAppearance("lin", "coat", emptyList()))) }
        val result = planner().preview("s", "S1E01", "SC01-01")
        assertEquals(listOf("ref/pier.png"), result.inputs.map { it.path })
    }
    @Test fun `无参考语音能力的配音通道在预览阻断`() = runTest {
        edit { it.copy(dialogue = listOf(ShotDialogue("lin", "门开着", audioSource = DialogueAudioSource.DUBBED))) }
        val speech = MediaChannel("speech", MediaGroup.AUDIO, MediaCapabilities(setOf(GenerationMode.SPEECH)))
        val preview = planner(PlanningRegistry(planningChannel(), speech)).preview("s", "S1E01", "SC01-01")
        assertNotNull(preview.blockedReason)
        assertTrue(queue.tasks.value.isEmpty())
    }
    @Test fun `显式关键帧锚点道具及声音按类型打包`() = runTest {
        files.bytes["ref/prop.png"] = byteArrayOf(7)
        files.bytes["ref/keyframe.png"] = byteArrayOf(8)
        store.production = store.production.copy(assets = store.production.assets + Asset("letter", AssetKind.PROP, "信封", versions = listOf(AssetVersion(1, "ref/prop.png", 1)), currentVersion = 1))
        edit { it.copy(inputs = listOf(ShotInput(InputRole.ANCHOR, "ref/keyframe.png", atSeconds = 2.0)), propAssetIds = listOf("letter")) }
        val result = planner().preview("s", "S1E01", "SC01-01")
        assertEquals("ref/keyframe.png", result.inputs.first().path)
        assertEquals("2.0 秒", result.inputs.first().detail)
        assertEquals("ref/prop.png", result.inputs.last().path)
    }

    @Test fun `模板规格在预览提交与候选快照保持一致`() = runTest {
        val registry = PlanningRegistry(planningChannel("comfy:video"))
        val planner = DefaultShotGenerationPlanner(store, registry, queue, files, CachedCharacterCompositor(files, HorizontalImageCodec { it.first() }),
            specsFor = { _, _ -> com.promenar.infinity.domain.mediagen.GenerationSpecs(frames = 121) }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val preview = planner.preview("s", "S1E01", "SC01-01")
        assertEquals(121, preview.specs.frames)
        assertNull(preview.specs.durationSeconds)
        assertTrue("时长由锁定工作流决定：121 帧" in preview.warnings)
        planner.generate("s", "S1E01", "SC01-01")
        assertEquals(preview.specs, queue.tasks.value.single().request.specs)
    }

    @Test fun `模板未给出帧数时时长提示显示模板名称`() = runTest {
        val planner = DefaultShotGenerationPlanner(store, PlanningRegistry(planningChannel("comfy:video")), queue, files, CachedCharacterCompositor(files, HorizontalImageCodec { it.first() }),
            labelOf = { "H3 冒烟" }, ioDispatcher = StandardTestDispatcher(testScheduler))
        assertTrue("时长由锁定工作流决定：H3 冒烟" in planner.preview("s", "S1E01", "SC01-01").warnings)
    }

    @Test fun `候选已满和云端非整数时长在入队前阻断`() = runTest {
        edit { shot -> shot.copy(takes = List(30) { Take("take-$it", "takes/$it.mp4", TakeSource("service"), 1) }) }
        assertTrue(planner().generate("s", "S1E01", "SC01-01") is ShotGenerateResult.Rejected)
        assertTrue(queue.tasks.value.isEmpty())
        edit { it.copy(takes = emptyList(), durationSeconds = 5.5) }
        assertNotNull(planner().preview("s", "S1E01", "SC01-01").blockedReason)
    }

    @Test fun `拼合期间规划变化后拒绝按过期快照提交`() = runTest {
        val codec = HorizontalImageCodec { parts -> store.revision++; parts.first() }
        val planner = DefaultShotGenerationPlanner(store, PlanningRegistry(planningChannel(pack = CharacterReferenceMode.COMPOSITE)), queue, files, CachedCharacterCompositor(files, codec), ioDispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(planner.generate("s", "S1E01", "SC01-01") is ShotGenerateResult.Rejected)
        assertTrue(queue.tasks.value.isEmpty())
    }

}
