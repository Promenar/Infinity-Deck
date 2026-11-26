package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.mediagen.InputRole as MediaRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/** 文案由应用资源注入；测试及独立工具使用中文缺省值。 */
fun interface ShotPlanningMessages { fun text(key: String, detail: String): String }
val ChineseShotPlanningMessages = ShotPlanningMessages { key, detail -> when (key) {
    "missing_project" -> "当前会话没有制作项目。"
    "missing_shot" -> "集或镜头不存在：$detail"
    "unlocked" -> "场尚未锁定：$detail"
    "no_channel" -> "没有支持当前模式的通道：$detail"
    "no_channel_mode" -> "没有支持当前模式的通道"
    "missing_input" -> "输入不存在或类型不符：$detail"
    "mode_mismatch_separate" -> "当前视频通道需要分张单视图，但「$detail」仅登记了多视图资产卡，请补充单视图或切换入参模式"
    "overflow" -> "输入超出通道上限：$detail"
    "invalid_plan" -> "生成规划不完整：$detail"
    "unavailable_backend" -> "通道后端尚未配置：$detail"
    "failed" -> "生成规划提交失败，请核对输入与通道。"
    "reference_review" -> "参考包待审：$detail"
    "missing_voice" -> "配音缺少角色参考语音：$detail"
    "workflow_timing" -> "时长由锁定工作流决定：$detail"
    "workflow_timing_frames" -> "时长由锁定工作流决定：$detail 帧"
    "derived_frames" -> "按镜头时长换算：$detail 帧"
    "override_frames" -> "已指定帧数：$detail 帧"
    "plan_changed" -> "镜头规划已变化，请先预览。"
    "candidate_capacity" -> "候选容量不足：$detail"
    "audio_failed" -> "部分配音任务未入队：$detail"
    else -> detail
} }

class DefaultShotGenerationPlanner(
    private val store: ShotGenerationStore,
    private val registry: MediaChannelRegistry,
    private val queue: MediaGenerationQueue,
    private val files: ProductionWorkspaceFiles,
    private val compositor: CachedCharacterCompositor,
    private val backendFor: ((MediaChannel) -> MediaGenerationBackend?)? = null,
    private val specsFor: suspend (MediaChannel, Shot) -> GenerationSpecs = { channel, shot ->
        if (channel.id.startsWith("comfy:")) GenerationSpecs() else GenerationSpecs(durationSeconds = shot.durationSeconds.takeIf { it % 1.0 == 0.0 && it <= Int.MAX_VALUE }?.toInt())
    },
    private val validateBackend: suspend (MediaChannel, MediaGenerationRequest) -> List<String> = { _, _ -> emptyList() },
    private val expectedOutputs: suspend (MediaChannel) -> Int = { 1 },
    private val kindOf: suspend (String, String) -> InputKind? = { _, path -> mediaKindFromPath(path) },
    private val labelOf: suspend (MediaChannel) -> String = { it.id },
    private val messages: ShotPlanningMessages = ChineseShotPlanningMessages,
    private val templateFor: suspend (MediaChannel) -> ComfyTemplate? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ShotGenerationPlanner {
    private val submissions = ProductionSubmissionMutex
    private data class Plan(val snapshot: ProductionSnapshot, val shot: Shot, val preview: ShotPlanPreview)
    private fun text(key: String, detail: String = "") = messages.text(key, detail)

    override suspend fun preview(sessionId: String, episodeId: String, shotCode: String): ShotPlanPreview =
        preview(sessionId, episodeId, shotCode, null)

    override suspend fun preview(sessionId: String, episodeId: String, shotCode: String, frames: Int?): ShotPlanPreview = withContext(ioDispatcher) {
        plan(sessionId, episodeId, shotCode, framesOverride = frames).preview
    }

    private suspend fun plan(
        sessionId: String,
        episodeId: String,
        shotCode: String,
        compile: Boolean = false,
        params: Map<String, kotlinx.serialization.json.JsonPrimitive> = emptyMap(),
        framesOverride: Int? = null,
    ): Plan {
        val snapshot = store.load(sessionId) ?: throw IllegalArgumentException(text("missing_project"))
        val p = snapshot.production
        val scene = p.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId }?.scenes?.firstOrNull { sc -> sc.shots.any { it.code == shotCode } }
            ?: throw IllegalArgumentException(text("missing_shot", "$episodeId/$shotCode"))
        val shot = scene.shots.first { it.code == shotCode }
        val mode = shot.mode.toGenerationMode()
        val ordered = registry.observeChannels().first()
        val explicitChannelId = shot.channelId?.takeIf { it.isNotBlank() }
        val projectDefaultId = p.defaultVideoChannelId?.takeIf { it.isNotBlank() }
        val channel = when {
            explicitChannelId != null -> registry.channel(explicitChannelId)?.takeIf { it.group == MediaGroup.VIDEO && !it.capabilities.deriveOnly && mode in it.capabilities.modes }
            projectDefaultId != null -> registry.channel(projectDefaultId)?.takeIf { it.group == MediaGroup.VIDEO && !it.capabilities.deriveOnly && mode in it.capabilities.modes }
                ?: resolveDefaultChannel(MediaGroup.VIDEO, mode, ordered)
            else -> resolveDefaultChannel(MediaGroup.VIDEO, mode, ordered)
        }
        val inputMode = p.effectiveReferenceInputMode(channel)
        val issues = mutableListOf<String>()
        if (!p.experimental && !scene.storyboardLocked) issues += text("unlocked", scene.code)
        if (channel == null) issues += explicitChannelId?.let { text("no_channel", it) } ?: text("no_channel_mode", "")
        val warnings = shot.appearances.mapNotNull { appearance ->
            val asset = p.assets.find { it.id == appearance.characterAssetId } ?: return@mapNotNull null
            val look = asset.looks.find { it.id == appearance.lookId }
            val settled = look?.locked == true || asset.status == AssetStatus.LOCKED
            if (settled) null else asset.name
        }.distinct().map { text("reference_review", it) }.toMutableList()
        val packed = mutableListOf<PlannedInput>()
        suspend fun input(
            path: String?,
            title: String,
            detail: String,
            role: MediaRole = MediaRole.REFERENCE,
            expectedKind: InputKind? = null,
            isAnchor: Boolean = false,
            atSeconds: Double? = null,
        ) {
            if (path == null) { issues += text("missing_input", title); return }
            ProductionManifestCodec.validatePath(path)
            val kind = kindOf(sessionId, path)
            if (kind == null || expectedKind != null && kind != expectedKind || !files.isImageOrVideo(sessionId, path)) {
                issues += text("missing_input", "$title ($path)"); return
            }
            packed += PlannedInput(null, title, detail, kind, role, path, isAnchor = isAnchor, atSeconds = atSeconds)
        }
        val explicit = when (shot.mode) {
            ShotMode.FIRST_FRAME, ShotMode.LAST_FRAME, ShotMode.FIRST_LAST -> shot.inputs.sortedBy { when (it.role) { InputRole.FIRST_FRAME -> 0; InputRole.LAST_FRAME -> 1; InputRole.ANCHOR -> 2; else -> 3 } }
            else -> shot.inputs.sortedBy { if (it.role == InputRole.ANCHOR) 0 else 1 }
        }
        explicit.forEach { item ->
            val anchor = item.role == InputRole.ANCHOR
            input(
                path = item.path,
                title = item.label ?: localizedInputRoleLabel(item.role),
                detail = item.atSeconds?.let { "$it 秒" }.orEmpty(),
                role = when {
                    shot.mode == ShotMode.REFERENCE -> MediaRole.REFERENCE
                    item.role == InputRole.FIRST_FRAME -> MediaRole.FIRST_FRAME
                    item.role == InputRole.LAST_FRAME -> MediaRole.LAST_FRAME
                    else -> MediaRole.REFERENCE
                },
                isAnchor = anchor,
                atSeconds = if (anchor) item.atSeconds else null,
            )
        }
        if (shot.mode == ShotMode.REFERENCE) {
            shot.appearances.forEach { appearance ->
                val character = p.assets.find { it.id == appearance.characterAssetId && it.kind == AssetKind.CHARACTER }
                val look = character?.looks?.find { it.id == appearance.lookId }
                // 尚未拆分造型但已有版本图时，自动回退到版本图作为角色参考，并避免与显式输入重复。
                val fallbackPath = if (character != null && look == null && character.looks.isEmpty()) character.currentVersionPath() else null
                if (fallbackPath != null) {
                    if (packed.none { it.path == fallbackPath }) input(fallbackPath, character!!.name, "版本图", expectedKind = InputKind.IMAGE)
                    return@forEach
                }
                if (character == null || look == null) { issues += text("missing_input", "${appearance.characterAssetId}/${appearance.lookId}"); return@forEach }
                val sheetPath = look.sheetPath?.takeIf(String::isNotBlank)
                val selectedIds = appearance.referenceSlotIds
                val selected = selectedIds.mapNotNull { id -> look.slots.find { it.id == id }.also { if (it == null) issues += text("missing_input", "${character.name}/$id") } }
                val configuredOrder = if (channel == null || inputMode == ReferenceInputMode.SHEET) emptyList() else channel.capabilities.referenceOrder.map(::canonicalSlot)
                val slots = selected.sortedWith(
                    compareBy<ReferenceSlot> { slot -> configuredOrder.indexOf(canonicalSlot(slot.id)).let { if (it < 0) Int.MAX_VALUE else it } }
                        .thenBy { slot -> ProductionRules.defaultSlotOrder.indexOf(canonicalSlot(slot.id)).let { if (it < 0) Int.MAX_VALUE else it } }
                        .thenBy { it.id },
                )
                if (inputMode == ReferenceInputMode.SHEET) {
                    if (sheetPath != null) {
                        if (packed.none { it.path == sheetPath }) input(sheetPath, "${character.name} · ${look.name}", "多视图资产卡", expectedKind = InputKind.IMAGE)
                    } else if (slots.isEmpty() && look.populatedSlots().isEmpty()) {
                        issues += text("missing_input", "${character.name} · ${look.name}")
                    } else {
                        val paths = mutableListOf<String>()
                        slots.forEach { slot ->
                            val path = slot.path
                            if (path == null || kindOf(sessionId, path) != InputKind.IMAGE || !files.isImageOrVideo(sessionId, path)) issues += text("missing_input", "${character.name}/${slot.name}")
                            else { ProductionManifestCodec.validatePath(path); paths += path }
                        }
                        if (paths.size == 1) {
                            if (packed.none { it.path == paths.single() }) input(paths.single(), "${character.name} · ${slots.first().name}", look.name, expectedKind = InputKind.IMAGE)
                        } else if (paths.isNotEmpty()) packed += PlannedInput(null, "${character.name} · ${look.name}", slots.joinToString(" / ") { it.name }, InputKind.IMAGE, MediaRole.REFERENCE, null, paths)
                    }
                } else {
                    if (sheetPath != null && (look.populatedSlots().isEmpty() || (selected.isNotEmpty() && slots.all { it.path.isNullOrBlank() }))) {
                        issues += text("mode_mismatch_separate", "${character.name} · ${look.name}")
                    } else {
                        slots.forEach { slot ->
                            val path = slot.path
                            if (path == null || packed.none { it.path == path }) {
                                input(path, "${character.name} · ${slot.name}", look.name, expectedKind = InputKind.IMAGE)
                            }
                        }
                    }
                }
            }
            (listOfNotNull(shot.locationAssetId) + shot.propAssetIds).forEach { id ->
                val asset = p.assets.find { it.id == id }
                val versionPath = asset?.currentVersionPath()
                val viewSlots = asset?.populatedReferenceViews().orEmpty()
                val kindDetail = localizedAssetKindLabel(asset?.kind)
                if (inputMode == ReferenceInputMode.SHEET) {
                    when {
                        versionPath != null -> if (packed.none { it.path == versionPath }) input(versionPath, asset.name, kindDetail)
                        viewSlots.size == 1 -> {
                            val singlePath = viewSlots.single().path
                            if (singlePath == null || packed.none { it.path == singlePath }) input(singlePath, "${asset?.name ?: id} · ${viewSlots.single().name}", kindDetail, expectedKind = InputKind.IMAGE)
                        }
                        viewSlots.size >= 2 -> {
                            val validPaths = mutableListOf<String>()
                            viewSlots.forEach { slot ->
                                val path = slot.path
                                if (path == null || kindOf(sessionId, path) != InputKind.IMAGE || !files.isImageOrVideo(sessionId, path)) issues += text("missing_input", "${asset?.name ?: id}/${slot.name}")
                                else { ProductionManifestCodec.validatePath(path); validPaths += path }
                            }
                            if (validPaths.isNotEmpty()) packed += PlannedInput(null, asset?.name ?: id, viewSlots.joinToString(" / ") { it.name }, InputKind.IMAGE, MediaRole.REFERENCE, null, validPaths)
                        }
                        else -> input(null, asset?.name ?: id, kindDetail)
                    }
                } else {
                    if (viewSlots.isNotEmpty()) {
                        viewSlots.forEach { slot ->
                            val path = slot.path
                            if (path == null || packed.none { it.path == path }) input(path, "${asset?.name ?: id} · ${slot.name}", kindDetail, expectedKind = InputKind.IMAGE)
                        }
                    } else if (versionPath == null || packed.none { it.path == versionPath }) {
                        input(versionPath, asset?.name ?: id, kindDetail)
                    }
                }
            }
            shot.dialogue.filter { it.audioSource == DialogueAudioSource.NATIVE }.map { it.characterAssetId }.distinct().forEach { id ->
                val character = p.assets.find { it.id == id }
                character?.referenceVoice?.path?.let { path ->
                    if (packed.none { it.path == path }) input(path, character.name, "参考语音", MediaRole.REFERENCE_VOICE, InputKind.AUDIO)
                }
            }
        }
        var number = 0
        val numbered = packed.map { it.copy(number = if (it.kind == InputKind.IMAGE && !it.isAnchor) ++number else null) }
        val accepted = mutableListOf<PlannedInput>()
        val overflow = mutableListOf<PlannedInput>()
        if (channel == null) {
            accepted += numbered
        } else {
            val counts = mutableMapOf<InputKind, Int>()
            numbered.forEach { item ->
                val maximum = when (item.kind) { InputKind.IMAGE -> channel.capabilities.maxImages; InputKind.AUDIO -> channel.capabilities.maxAudio; InputKind.VIDEO -> channel.capabilities.maxVideos }
                val bounded = item.role in setOf(MediaRole.REFERENCE, MediaRole.REFERENCE_VOICE)
                val count = if (bounded) counts.getOrDefault(item.kind, 0) + 1 else 0
                if (bounded) counts[item.kind] = count
                if (bounded && count > maximum) overflow += item else accepted += item
            }
            if (overflow.isNotEmpty()) issues += text("overflow", overflow.joinToString("；") { "${it.title} (${it.path ?: it.compositeOf.joinToString()})" })
        }
        val format = channel?.capabilities?.promptFormat ?: PromptFormat.GENERIC
        val prompt = if (!compile && shot.prompt.isNotBlank()) shot.prompt else ShotPromptCompiler.compile(p, shot, accepted, format)
        val template = channel?.let { templateFor(it) }
        val baseSpecs = if (channel == null) GenerationSpecs() else try { specsFor(channel, shot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { issues += text("invalid_plan", e.message?.takeIf { it.isNotBlank() && it.any { c -> c.code > 0x2E80 } } ?: "生成规格配置有误"); GenerationSpecs() }
        val specs = if (framesOverride != null) {
            when {
                channel == null || template == null || template.slots.none { it.kind == SlotKind.FRAMES } -> {
                    issues += text("invalid_plan", "当前通道不支持指定帧数")
                    baseSpecs
                }
                framesOverride < template.frameBase || (framesOverride - template.frameBase) % template.frameStep != 0 ||
                    (template.maxFrames != null && framesOverride > template.maxFrames) -> {
                    issues += text("invalid_plan", "帧数须满足 ${template.frameBase}+${template.frameStep}n" + (template.maxFrames?.let { " 且不超过 $it" } ?: ""))
                    baseSpecs
                }
                else -> baseSpecs.copy(frames = framesOverride)
            }
        } else baseSpecs
        if (channel != null) {
            if (channel.id.startsWith("comfy:")) {
                val warning = when {
                    framesOverride != null && specs.frames == framesOverride -> text("override_frames", specs.frames.toString())
                    template?.fps != null && specs.frames != null -> text("derived_frames", specs.frames.toString())
                    else -> specs.frames?.let { text("workflow_timing_frames", it.toString()) } ?: text("workflow_timing", labelOf(channel))
                }
                warnings += warning
            } else if (shot.durationSeconds % 1.0 != 0.0) {
                issues += text("invalid_plan", "云端视频通道要求整数秒时长")
            }
        }
        val request = request(p, episodeId, shot, channel?.id.orEmpty(), prompt, accepted, sessionId, specs).let { planned ->
            require(params.isEmpty() || template != null) { "该通道不接受模板参数。" }
            planned.copy(params = template?.resolveParams(params) ?: emptyMap(), productionContext = template?.let { ProductionMediaContext(templateFingerprint = it.fingerprint()) })
        }
        if (channel != null) {
            if (backendFor != null && backendFor.invoke(channel) == null) issues += text("unavailable_backend", channel.id)
            val occupied = occupiedShotCandidates(p, episodeId, shotCode, queue) { task -> registry.channel(task.request.channelId)?.let { expectedOutputs(it) } ?: 1 }
            if (occupied + expectedOutputs(channel) > 30) issues += text("candidate_capacity", shotCode)
            issues += validateBackend(channel, request).map { text("invalid_plan", it) }
            val validation = validateRequest(channel, request)
            if (!validation.valid && overflow.isEmpty()) issues += text("invalid_plan", localizeValidationIssues(validation))
        }
        val audioPlans = mutableListOf<PlannedDialogueAudio>()
        val speech = resolveDefaultChannel(MediaGroup.SPEECH, GenerationMode.SPEECH, ordered)
        shot.dialogue.forEachIndexed { index, dialogue ->
            if (dialogue.audioSource != DialogueAudioSource.DUBBED) return@forEachIndexed
            val character = p.assets.find { it.id == dialogue.characterAssetId }
            val voice = character?.referenceVoice?.path
            if (speech == null) { issues += text("no_channel", "配音"); return@forEachIndexed }
            val needsReferenceVoice = speech.capabilities.maxAudio > 0
            if (needsReferenceVoice) {
                if (voice == null) { issues += text("missing_voice", character?.name ?: dialogue.characterAssetId); return@forEachIndexed }
                if (kindOf(sessionId, voice) != InputKind.AUDIO || !files.isImageOrVideo(sessionId, voice)) issues += text("missing_input", voice)
            }
            val audioInputs = if (needsReferenceVoice && voice != null) {
                listOf(MediaInput(voice, InputKind.AUDIO, MediaRole.REFERENCE_VOICE))
            } else emptyList()
            val audioParams = buildMap<String, kotlinx.serialization.json.JsonPrimitive> {
                dialogue.tone?.takeIf { it.isNotBlank() }?.let { put("style", kotlinx.serialization.json.JsonPrimitive(it)) }
            }
            val audioRequest = MediaGenerationRequest(
                speech.id,
                GenerationMode.SPEECH,
                dialogue.text,
                audioInputs,
                ProductionSourceRef(p.id, episodeId, shot.code, index).encode(),
                sessionId = sessionId,
                outputDir = "${p.rootPath}/$episodeId/audio",
                params = audioParams,
            )
            val valid = validateRequest(speech, audioRequest)
            if (valid.excessInputs.isNotEmpty()) issues += text("overflow", "${character?.name} ($voice)")
            else if (!valid.valid) issues += text("invalid_plan", "配音：${localizeValidationIssues(valid)}")
            if (backendFor != null && backendFor.invoke(speech) == null) issues += text("unavailable_backend", speech.id)
            issues += validateBackend(speech, audioRequest).map { text("invalid_plan", it) }
            audioPlans += PlannedDialogueAudio(index, dialogue.characterAssetId, dialogue.tone, audioRequest)
        }
        return Plan(snapshot, shot, ShotPlanPreview(channel, shot.channelId.isNullOrBlank(), channel?.let { labelOf(it) }.orEmpty(), shot.mode, accepted, overflow,
            prompt, shot.promptUserEdited && !compile, format, null, issues.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n"), warnings, audioPlans, specs, params = request.params))
    }

    private fun formatSecondsCompact(seconds: Double): String {
        val rounded = kotlin.math.round(seconds * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    override suspend fun generate(sessionId: String, episodeId: String, shotCode: String): ShotGenerateResult = generate(sessionId, episodeId, shotCode, emptyMap(), null)
    override suspend fun generate(sessionId: String, episodeId: String, shotCode: String, params: Map<String, kotlinx.serialization.json.JsonPrimitive>): ShotGenerateResult = generate(sessionId, episodeId, shotCode, params, null)
    override suspend fun generate(sessionId: String, episodeId: String, shotCode: String, params: Map<String, kotlinx.serialization.json.JsonPrimitive>, frames: Int?): ShotGenerateResult = withContext(ioDispatcher) {
        submissions.withLock {
            val submitted = mutableListOf<MediaTask>()
            try {
                val plan = plan(sessionId, episodeId, shotCode, params = params, framesOverride = frames)
                plan.preview.blockedReason?.let { return@withLock ShotGenerateResult.Rejected(it) }
                val channel = requireNotNull(plan.preview.channel)
                val inputs = plan.preview.inputs.map { input -> if (input.compositeOf.isEmpty()) input else input.copy(path = compositor.materialize(sessionId, plan.snapshot.production.rootPath, input.compositeOf)) }
                val actual = request(plan.snapshot.production, episodeId, plan.shot, channel.id, plan.preview.prompt, inputs, sessionId, plan.preview.specs).let { planned ->
                    val template = templateFor(channel)
                    require(params.isEmpty() || template != null) { "该通道不接受模板参数。" }
                    planned.copy(params = template?.resolveParams(params) ?: emptyMap(), productionContext = template?.let { ProductionMediaContext(templateFingerprint = it.fingerprint()) })
                }
                if (store.load(sessionId)?.let { it.production.id == plan.snapshot.production.id && it.revision == plan.snapshot.revision } != true)
                    return@withLock ShotGenerateResult.Rejected(text("plan_changed"))
                submitted += queue.submit(actual)
                plan.preview.audioPlans.forEach { submitted += queue.submit(it.request) }
                ShotGenerateResult.Submitted(submitted.first().id, false, submitted.drop(1).map { it.id })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val reason = (failure as? IllegalArgumentException)?.message?.takeIf { it.any { c -> c.code > 0x2E80 } }?.take(500) ?: text("failed")
                if (submitted.isEmpty()) ShotGenerateResult.Rejected(reason)
                else ShotGenerateResult.Submitted(submitted.first().id, false, submitted.drop(1).map { it.id }, listOf(text("audio_failed", submitted.first().id)))
            }
        }
    }
    override suspend fun recompilePrompt(sessionId: String, episodeId: String, shotCode: String): String = withContext(ioDispatcher) {
        val plan = plan(sessionId, episodeId, shotCode, compile = true)
        store.recompile(sessionId, plan.snapshot.revision, episodeId, shotCode, plan.preview.prompt)
        plan.preview.prompt
    }
    private fun request(production: Production, episodeId: String, shot: Shot, channelId: String, prompt: String, inputs: List<PlannedInput>, sessionId: String, specs: GenerationSpecs) =
        MediaGenerationRequest(channelId, shot.mode.toGenerationMode(), prompt, inputs.map { MediaInput(it.path ?: it.compositeOf.first(), it.kind, it.role) },
            ProductionSourceRef(production.id, episodeId, shot.code).encode(), specs,
            sessionId, "${production.rootPath}/$episodeId/takes")
}

private fun canonicalSlot(id: String): String = when (id) { "front_full_body" -> "front"; "side_full_body" -> "side"; "back_full_body" -> "back"; else -> id }

private fun localizedAssetKindLabel(kind: AssetKind?): String = when (kind) {
    AssetKind.CHARACTER -> "角色"
    AssetKind.LOCATION -> "场景"
    AssetKind.PROP -> "道具"
    AssetKind.STYLE -> "风格"
    AssetKind.SOUND -> "声音"
    null -> ""
}

private fun localizedInputRoleLabel(role: InputRole): String = when (role) {
    InputRole.FIRST_FRAME -> "首帧"
    InputRole.LAST_FRAME -> "尾帧"
    InputRole.ANCHOR -> "锚点帧"
    InputRole.REFERENCE -> "参考图"
}

private fun localizeValidationIssues(validation: RequestValidation): String = buildList {
    if (validation.channelMismatch) add("所选通道与请求通道不匹配")
    if (validation.unsupportedMode) add(localizeMediaSpecError("unsupportedMode"))
    validation.excessInputs.map { it.kind }.distinct().forEach { kind ->
        when (kind) {
            InputKind.IMAGE -> add(localizeMediaSpecError("maxImages"))
            InputKind.AUDIO -> add(localizeMediaSpecError("maxAudio"))
            InputKind.VIDEO -> add(localizeMediaSpecError("maxVideos"))
        }
    }
    validation.specErrors.forEach { add(localizeMediaSpecError(it)) }
    if (validation.invalidInputs.isNotEmpty()) {
        val paths = validation.invalidInputs.joinToString("、") { it.location.ifBlank { "空路径" } }
        add("输入媒体角色或格式与当前模式不匹配：$paths")
    }
}.distinct().joinToString("；")

internal fun mediaKindFromPath(path: String): InputKind? = when (path.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "webp", "gif", "bmp" -> InputKind.IMAGE
    "mp4", "webm", "mov", "mkv" -> InputKind.VIDEO
    "wav", "mp3", "flac", "ogg", "m4a", "aac" -> InputKind.AUDIO
    else -> null
}
