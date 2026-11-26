package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.mediagen.InputRole as MediaRole
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.jsonObject
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
        val speech = MediaChannel("speech", MediaGroup.SPEECH, MediaCapabilities(setOf(GenerationMode.SPEECH), maxAudio = 1))
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
    @Test fun `云端预置声线配音通道无需参考语音文件即可生成`() = runTest {
        edit { it.copy(dialogue = listOf(ShotDialogue("lin", "门开着", "低声", DialogueAudioSource.DUBBED))) }
        val speech = MediaChannel("speech", MediaGroup.SPEECH, MediaCapabilities(setOf(GenerationMode.SPEECH)))
        val preview = planner(PlanningRegistry(planningChannel(), speech)).preview("s", "S1E01", "SC01-01")
        assertNull(preview.blockedReason)
        val submitted = planner(PlanningRegistry(planningChannel(), speech)).generate("s", "S1E01", "SC01-01") as ShotGenerateResult.Submitted
        assertEquals(1, submitted.audioTaskIds.size)
        assertTrue(queue.tasks.value.last().request.inputs.isEmpty())
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

    @Test fun `取消任务释放候选容量并允许独立生成新任务`() = runTest {
        edit { shot -> shot.copy(takes = List(29) { Take("take-$it", "takes/$it.mp4", TakeSource("服务"), 1) }) }
        val planner = planner()
        val first = planner.generate("s", "S1E01", "SC01-01") as ShotGenerateResult.Submitted
        assertTrue(planner.preview("s", "S1E01", "SC01-01").blockedReason.orEmpty().contains("候选容量不足"))
        assertTrue(planner.generate("s", "S1E01", "SC01-01") is ShotGenerateResult.Rejected)
        assertEquals(1, queue.tasks.value.size)

        queue.cancel(first.taskId)
        assertNull(planner.preview("s", "S1E01", "SC01-01").blockedReason)
        val second = planner.generate("s", "S1E01", "SC01-01") as ShotGenerateResult.Submitted
        assertNotEquals(first.taskId, second.taskId)
        assertEquals(listOf(MediaTaskState.CANCELED, MediaTaskState.QUEUED), queue.tasks.value.map { it.state })
        assertEquals(29, shot().takes.size)
    }

    @Test fun `预览与提交镜头及配音均不调用后端估价`() = runTest {
        edit { it.copy(dialogue = listOf(ShotDialogue("lin", "门开着", audioSource = DialogueAudioSource.DUBBED))) }
        val speech = MediaChannel("speech", MediaGroup.SPEECH, MediaCapabilities(setOf(GenerationMode.SPEECH), maxAudio = 1))
        val estimatedChannels = mutableListOf<String>()
        val backend = object : MediaGenerationBackend {
            override fun handles(channel: MediaChannel) = true
            override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest): Money? {
                estimatedChannels += channel.id
                error("规划阶段不得估价")
            }
            override suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest): String = error("测试仅检查规划入队")
            override suspend fun poll(channel: MediaChannel, remoteId: String): RemoteStatus = error("测试仅检查规划入队")
            override suspend fun download(channel: MediaChannel, request: MediaGenerationRequest, remoteId: String, outputs: List<RemoteOutput>): List<String> = error("测试仅检查规划入队")
            override fun actualCost(channel: MediaChannel, usage: Map<String, Long>): Money? = null
        }
        val planner = DefaultShotGenerationPlanner(store, PlanningRegistry(planningChannel(), speech), queue, files,
            CachedCharacterCompositor(files), backendFor = { backend }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val preview = planner.preview("s", "S1E01", "SC01-01")
        assertNull(preview.blockedReason)
        assertNull(preview.estimate)
        assertEquals(1, preview.audioPlans.size)
        assertTrue(estimatedChannels.isEmpty())

        val submitted = planner.generate("s", "S1E01", "SC01-01") as ShotGenerateResult.Submitted
        assertEquals(1, submitted.audioTaskIds.size)
        assertEquals(listOf(GenerationMode.REFERENCE, GenerationMode.SPEECH), queue.tasks.value.map { it.request.mode })
        assertTrue(estimatedChannels.isEmpty())
    }

    @Test fun `拼合期间规划变化后拒绝按过期快照提交`() = runTest {
        val codec = HorizontalImageCodec { parts -> store.revision++; parts.first() }
        val planner = DefaultShotGenerationPlanner(store, PlanningRegistry(planningChannel(pack = CharacterReferenceMode.COMPOSITE)), queue, files, CachedCharacterCompositor(files, codec), ioDispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(planner.generate("s", "S1E01", "SC01-01") is ShotGenerateResult.Rejected)
        assertTrue(queue.tasks.value.isEmpty())
    }

    @Test fun `仅文生通道时参考镜保留输入不报超限或整数时长并给出缺通道提示`() = runTest {
        val textOnly = MediaChannel("h3-t2v", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.TEXT)))
        edit { it.copy(durationSeconds = 4.5, inputs = listOf(ShotInput(InputRole.REFERENCE, "ref/pier.png", label = "码头母图"))) }
        val planner = planner(PlanningRegistry(textOnly))
        val preview = planner.preview("s", "S1E01", "SC01-01")
        assertNull(preview.channel)
        assertEquals(listOf("ref/pier.png", "ref/front.png", "ref/back.png"), preview.inputs.map { it.path })
        assertEquals(listOf(1, 2, 3), preview.inputs.map { it.number })
        assertTrue(preview.overflow.isEmpty())
        val blocked = preview.blockedReason.orEmpty()
        assertTrue(blocked.contains("没有支持当前模式的通道"))
        assertFalse(blocked.contains("超出通道上限"))
        assertFalse(blocked.contains("durationSeconds"))
        assertTrue(planner.generate("s", "S1E01", "SC01-01") is ShotGenerateResult.Rejected)
        assertTrue(queue.tasks.value.isEmpty())
    }

    @Test fun `角色未拆造型时回退使用版本图且不与显式输入重复挂载`() = runTest {
        files.bytes["ref/char-v2.png"] = byteArrayOf(9)
        store.production = store.production.copy(assets = store.production.assets.map { asset ->
            if (asset.id != "lin") asset else asset.copy(
                looks = emptyList(),
                versions = listOf(AssetVersion(2, "ref/char-v2.png", 2)),
                currentVersion = 2,
            )
        })
        edit { it.copy(appearances = listOf(ShotAppearance("lin", "default", emptyList()))) }
        val result = planner().preview("s", "S1E01", "SC01-01")
        assertNull(result.blockedReason)
        assertEquals(listOf("ref/char-v2.png", "ref/pier.png"), result.inputs.map { it.path })

        // 显式输入已包含版本图时不再重复添加。
        edit { it.copy(inputs = listOf(ShotInput(InputRole.REFERENCE, "ref/char-v2.png", label = "显式角色图"))) }
        val deduped = planner().preview("s", "S1E01", "SC01-01")
        assertNull(deduped.blockedReason)
        assertEquals(listOf("ref/char-v2.png", "ref/pier.png"), deduped.inputs.map { it.path })
    }

    @Test fun `项目默认视频通道优先于全局顺序并可被单镜头通道覆写`() = runTest {
        val c1 = planningChannel("global-first")
        val c2 = planningChannel("project-default")
        val c3 = planningChannel("shot-override")
        val registry = PlanningRegistry(c1, c2, c3)
        store.production = store.production.copy(defaultVideoChannelId = "project-default")
        val fromProject = planner(registry).preview("s", "S1E01", "SC01-01")
        assertEquals("project-default", fromProject.channel?.id)
        assertTrue(fromProject.channelIsDefault)

        edit { it.copy(channelId = "shot-override") }
        val fromShot = planner(registry).preview("s", "S1E01", "SC01-01")
        assertEquals("shot-override", fromShot.channel?.id)
        assertFalse(fromShot.channelIsDefault)
    }

    @Test fun `双模入参按镜头所选通道能力优先适配整卡与分张并支持同项目混排`() = runTest {
        files.bytes["ref/coat-sheet.png"] = byteArrayOf(11)
        files.bytes["ref/pier-wide.png"] = byteArrayOf(12)
        files.bytes["ref/pier-facade.png"] = byteArrayOf(13)
        store.production = store.production.copy(
            // 即使旧清单带有项目级 SEPARATE，单镜头选择 COMPOSITE 通道时仍应优先遵循通道能力。
            referenceInputMode = ReferenceInputMode.SEPARATE,
            assets = listOf(
                Asset(
                    id = "lin",
                    kind = AssetKind.CHARACTER,
                    name = "林晚",
                    status = AssetStatus.LOCKED,
                    looks = listOf(
                        CharacterLook(
                            id = "coat",
                            name = "风衣",
                            sheetPath = "ref/coat-sheet.png",
                            slots = listOf(
                                ReferenceSlot("front", "正面", "ref/front.png"),
                                ReferenceSlot("back", "背面", "ref/back.png"),
                            ),
                            locked = true,
                        ),
                    ),
                ),
                Asset(
                    id = "pier",
                    kind = AssetKind.LOCATION,
                    name = "码头",
                    versions = listOf(AssetVersion(1, "ref/pier.png", 1)),
                    currentVersion = 1,
                    status = AssetStatus.LOCKED,
                    referenceViews = listOf(
                        ReferenceSlot("wide", "全景", "ref/pier-wide.png"),
                        ReferenceSlot("facade", "门头", "ref/pier-facade.png"),
                    ),
                    viewsLocked = true,
                ),
            ),
        )
        val sheetChannel = planningChannel("cloud:seedance", pack = CharacterReferenceMode.COMPOSITE)
        val separateChannel = planningChannel("comfy:h3_ref", pack = CharacterReferenceMode.SEPARATE)
        val mixedRegistry = PlanningRegistry(separateChannel, sheetChannel)

        // 镜头指向整卡通道时，直接使用 look.sheetPath 与 location.currentVersionPath，不触发拼合也不取分张图
        edit { it.copy(channelId = "cloud:seedance") }
        val sheetPreview = planner(mixedRegistry).preview("s", "S1E01", "SC01-01")
        assertNull(sheetPreview.blockedReason)
        assertEquals(listOf("ref/coat-sheet.png", "ref/pier.png"), sheetPreview.inputs.map { it.path })

        // 同一项目内把镜头切到分张工作流通道后，自动使用分张槽位与自由视角组
        edit { it.copy(channelId = "comfy:h3_ref") }
        val separatePreview = planner(mixedRegistry).preview("s", "S1E01", "SC01-01")
        assertNull(separatePreview.blockedReason)
        assertEquals(
            listOf("ref/back.png", "ref/front.png", "ref/pier-wide.png", "ref/pier-facade.png"),
            separatePreview.inputs.map { it.path },
        )

        // 若造型仅登记整卡而无分张单图，在分张工作流通道下阻断提示 mode_mismatch_separate
        store.production = store.production.copy(
            assets = store.production.assets.map { asset ->
                if (asset.id != "lin") asset else asset.copy(
                    looks = listOf(CharacterLook("coat", "风衣", sheetPath = "ref/coat-sheet.png", locked = true)),
                )
            },
        )
        val blockedPreview = planner(mixedRegistry).preview("s", "S1E01", "SC01-01")
        assertTrue(blockedPreview.blockedReason.orEmpty().contains("需要分张单视图"))
        assertTrue(planner(mixedRegistry).generate("s", "S1E01", "SC01-01") is ShotGenerateResult.Rejected)
    }

    @Test fun `referenceOrder未命中或为空时默认按front_side_back顺序打包`() = runTest {
        edit { it.copy(appearances = listOf(ShotAppearance("lin", "coat", listOf("back", "side", "front")))) }
        val pollutedOrder = planningChannel().let { ch ->
            ch.copy(capabilities = ch.capabilities.copy(referenceOrder = listOf("ref_image_0", "ref_image_1", "ref_image_2")))
        }
        val emptyOrder = planningChannel().let { ch ->
            ch.copy(capabilities = ch.capabilities.copy(referenceOrder = emptyList()))
        }
        listOf(pollutedOrder, emptyOrder).forEach { channel ->
            val preview = planner(PlanningRegistry(channel)).preview("s", "S1E01", "SC01-01")
            assertNull(preview.blockedReason)
            assertEquals(listOf("ref/front.png", "ref/side.png", "ref/back.png", "ref/pier.png"), preview.inputs.map { it.path })
        }
    }

    @Test fun `支持LAST_FRAME模式规划与H3模板fps换算及显式frames覆盖`() = runTest {
        files.bytes["ref/last.png"] = byteArrayOf(21)
        val l2vChannel = MediaChannel(
            "comfy:h3_l2va",
            MediaGroup.VIDEO,
            MediaCapabilities(setOf(GenerationMode.LAST_FRAME), promptFormat = PromptFormat.H3),
        )
        val l2vTemplate = com.promenar.infinity.domain.mediagen.ComfyTemplate(
            "h3_l2va",
            kotlinx.serialization.json.Json.parseToJsonElement("""{"1":{"class_type":"Gen","inputs":{"prompt":"","last":"","frames":124}},"2":{"class_type":"Save","inputs":{}}}""").jsonObject,
            listOf(
                com.promenar.infinity.domain.mediagen.ComfySlot("1", "prompt", com.promenar.infinity.domain.mediagen.SlotKind.PROMPT),
                com.promenar.infinity.domain.mediagen.ComfySlot("1", "last", com.promenar.infinity.domain.mediagen.SlotKind.LAST_FRAME),
                com.promenar.infinity.domain.mediagen.ComfySlot("1", "frames", com.promenar.infinity.domain.mediagen.SlotKind.FRAMES),
                com.promenar.infinity.domain.mediagen.ComfySlot("2", "videos", com.promenar.infinity.domain.mediagen.SlotKind.OUTPUT),
            ),
            frameBase = 5,
            frameStep = 17,
            locked = true,
            capabilities = l2vChannel.capabilities,
            hostId = "host",
            group = MediaGroup.VIDEO,
            maxFrames = 362,
            fps = 24.0,
        )
        val l2vPlanner = DefaultShotGenerationPlanner(
            store,
            PlanningRegistry(l2vChannel),
            queue,
            files,
            CachedCharacterCompositor(files, HorizontalImageCodec { it.first() }),
            specsFor = { _, shot -> com.promenar.infinity.data.mediagen.comfyPlanningSpecs(l2vTemplate, shot) },
            templateFor = { l2vTemplate },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        edit {
            it.copy(
                mode = ShotMode.LAST_FRAME,
                durationSeconds = 4.5,
                appearances = emptyList(),
                locationAssetId = null,
                inputs = listOf(ShotInput(InputRole.LAST_FRAME, "ref/last.png", label = "尾帧")),
            )
        }
        val derived = l2vPlanner.preview("s", "S1E01", "SC01-01")
        assertNull(derived.blockedReason)
        assertEquals(107, derived.specs.frames)
        assertTrue("按镜头时长换算：107 帧" in derived.warnings)
        assertEquals(listOf(com.promenar.infinity.domain.mediagen.InputRole.LAST_FRAME), derived.inputs.map { it.role })

        val overridden = l2vPlanner.preview("s", "S1E01", "SC01-01", 141)
        assertNull(overridden.blockedReason)
        assertEquals(141, overridden.specs.frames)
        assertTrue("已指定帧数：141 帧" in overridden.warnings)

        val invalidGrid = l2vPlanner.preview("s", "S1E01", "SC01-01", 142)
        assertNotNull(invalidGrid.blockedReason)

        edit { it.copy(inputs = listOf(ShotInput(InputRole.FIRST_FRAME, "ref/front.png"))) }
        assertNotNull(l2vPlanner.preview("s", "S1E01", "SC01-01").blockedReason)
    }

    @Test fun `分张模式显式inputs已包含角色槽位图时不再重复挂载且保留显式顺序`() = runTest {
        files.bytes["ref/face.png"] = byteArrayOf(31)
        files.bytes["ref/sword.png"] = byteArrayOf(32)
        store.production = store.production.copy(
            assets = store.production.assets.map { asset ->
                if (asset.id != "lin") asset else asset.copy(
                    looks = asset.looks.map { look ->
                        look.copy(slots = look.slots + ReferenceSlot("face", "面部近景", "ref/face.png"))
                    },
                )
            } + Asset(
                id = "sword",
                kind = AssetKind.PROP,
                name = "惊澜剑",
                status = AssetStatus.LOCKED,
                referenceViews = listOf(ReferenceSlot("concept", "概念图", "ref/sword.png")),
                viewsLocked = true,
            ),
        )
        edit {
            it.copy(
                appearances = listOf(ShotAppearance("lin", "coat", listOf("front", "side", "face", "back"))),
                propAssetIds = listOf("sword"),
                inputs = listOf(
                    ShotInput(InputRole.REFERENCE, "ref/front.png", label = "P1 正面", assetId = "lin"),
                    ShotInput(InputRole.REFERENCE, "ref/side.png", label = "P2 侧面", assetId = "lin"),
                    ShotInput(InputRole.REFERENCE, "ref/face.png", label = "P3 面部", assetId = "lin"),
                    ShotInput(InputRole.REFERENCE, "ref/back.png", label = "P4 背面", assetId = "lin"),
                    ShotInput(InputRole.REFERENCE, "ref/sword.png", label = "P5 惊澜剑", assetId = "sword"),
                    ShotInput(InputRole.REFERENCE, "ref/pier.png", label = "P6 码头", assetId = "pier"),
                ),
            )
        }
        val preview = planner(PlanningRegistry(planningChannel(max = 6))).preview("s", "S1E01", "SC01-01")
        assertNull(preview.blockedReason)
        assertTrue(preview.overflow.isEmpty())
        assertEquals(
            listOf("ref/front.png", "ref/side.png", "ref/face.png", "ref/back.png", "ref/sword.png", "ref/pier.png"),
            preview.inputs.map { it.path },
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6), preview.inputs.map { it.number })
    }

}
