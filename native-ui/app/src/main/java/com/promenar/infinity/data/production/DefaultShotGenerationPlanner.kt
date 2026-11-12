package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.mediagen.InputRole as MediaRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
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
    "overflow" -> "输入超出通道上限：$detail"
    "invalid_plan" -> "生成规划不完整：$detail"
    "unavailable_backend" -> "通道后端尚未配置：$detail"
    "failed" -> "生成规划提交失败，请核对输入与通道。"
    "reference_review" -> "参考包待审：$detail"
    "missing_voice" -> "配音缺少角色参考语音：$detail"
    "workflow_timing" -> "时长由锁定工作流决定：$detail"
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ShotGenerationPlanner {
    private val submissions = Mutex()
    private data class Plan(val snapshot: ProductionSnapshot, val shot: Shot, val preview: ShotPlanPreview)
    private fun text(key: String, detail: String = "") = messages.text(key, detail)

    override suspend fun preview(sessionId: String, episodeId: String, shotCode: String): ShotPlanPreview = withContext(ioDispatcher) {
        plan(sessionId, episodeId, shotCode).preview
    }

    private suspend fun plan(sessionId: String, episodeId: String, shotCode: String, compile: Boolean = false): Plan {
        val snapshot = store.load(sessionId) ?: throw IllegalArgumentException(text("missing_project"))
        val p = snapshot.production
        val scene = p.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId }?.scenes?.firstOrNull { sc -> sc.shots.any { it.code == shotCode } }
            ?: throw IllegalArgumentException(text("missing_shot", "$episodeId/$shotCode"))
        val shot = scene.shots.first { it.code == shotCode }
        val mode = shot.mode.toGenerationMode()
        val ordered = registry.observeChannels().first()
        val channel = if (shot.channelId.isNullOrBlank()) resolveDefaultChannel(MediaGroup.VIDEO, mode, ordered)
            else registry.channel(shot.channelId)?.takeIf { it.group == MediaGroup.VIDEO && mode in it.capabilities.modes }
        val issues = mutableListOf<String>()
        if (!p.experimental && !scene.storyboardLocked) issues += text("unlocked", scene.code)
        if (channel == null) issues += shot.channelId?.let { text("no_channel", it) } ?: text("no_channel_mode", "")
        val warnings = shot.appearances.mapNotNull { appearance -> p.assets.find { it.id == appearance.characterAssetId }?.takeIf { it.status != AssetStatus.LOCKED }?.name }
            .distinct().map { text("reference_review", it) }.toMutableList()
        val packed = mutableListOf<PlannedInput>()
        suspend fun input(path: String?, title: String, detail: String, role: MediaRole = MediaRole.REFERENCE, expectedKind: InputKind? = null) {
            if (path == null) { issues += text("missing_input", title); return }
            ProductionManifestCodec.validatePath(path)
            val kind = kindOf(sessionId, path)
            if (kind == null || expectedKind != null && kind != expectedKind || !files.isImageOrVideo(sessionId, path)) {
                issues += text("missing_input", "$title ($path)"); return
            }
            packed += PlannedInput(null, title, detail, kind, role, path)
        }
        val explicit = when (shot.mode) {
            ShotMode.FIRST_FRAME, ShotMode.FIRST_LAST -> shot.inputs.sortedBy { when (it.role) { InputRole.FIRST_FRAME -> 0; InputRole.LAST_FRAME -> 1; else -> 2 } }
            else -> shot.inputs
        }
        explicit.forEach { item -> input(item.path, item.label ?: item.role.name.lowercase(), item.atSeconds?.let { "$it 秒" }.orEmpty(),
            when {
                shot.mode == ShotMode.REFERENCE -> MediaRole.REFERENCE
                item.role == InputRole.FIRST_FRAME -> MediaRole.FIRST_FRAME
                item.role == InputRole.LAST_FRAME -> MediaRole.LAST_FRAME
                else -> MediaRole.REFERENCE
            }) }
        if (shot.mode == ShotMode.REFERENCE && channel != null) {
            shot.appearances.forEach { appearance ->
                val character = p.assets.find { it.id == appearance.characterAssetId && it.kind == AssetKind.CHARACTER }
                val look = character?.looks?.find { it.id == appearance.lookId }
                if (character == null || look == null) { issues += text("missing_input", "${appearance.characterAssetId}/${appearance.lookId}"); return@forEach }
                val selectedIds = appearance.referenceSlotIds
                val selected = selectedIds.mapNotNull { id -> look.slots.find { it.id == id }.also { if (it == null) issues += text("missing_input", "${character.name}/$id") } }
                val composite = channel.capabilities.characterReferenceMode == CharacterReferenceMode.COMPOSITE
                val order = if (composite) ProductionRules.defaultSlotOrder else channel.capabilities.referenceOrder.map(::canonicalSlot)
                val slots = selected.sortedWith(compareBy<ReferenceSlot> { slot -> order.indexOf(canonicalSlot(slot.id)).let { if (it < 0) Int.MAX_VALUE else it } }.thenBy { it.id })
                if (composite) {
                    val paths = mutableListOf<String>()
                    slots.forEach { slot ->
                        val path = slot.path
                        if (path == null || kindOf(sessionId, path) != InputKind.IMAGE || !files.isImageOrVideo(sessionId, path)) issues += text("missing_input", "${character.name}/${slot.name}")
                        else { ProductionManifestCodec.validatePath(path); paths += path }
                    }
                    if (paths.isNotEmpty()) packed += PlannedInput(null, "${character.name} · ${look.name}", slots.joinToString(" / ") { it.name }, InputKind.IMAGE, MediaRole.REFERENCE, null, paths)
                } else slots.forEach { slot -> input(slot.path, "${character.name} · ${slot.name}", look.name, expectedKind = InputKind.IMAGE) }
            }
            (listOfNotNull(shot.locationAssetId) + shot.propAssetIds).forEach { id ->
                val asset = p.assets.find { it.id == id }
                val version = asset?.versions?.firstOrNull { it.number == asset.currentVersion } ?: asset?.versions?.maxByOrNull { it.number }
                input(version?.path, asset?.name ?: id, asset?.kind?.name?.lowercase().orEmpty())
            }
            shot.dialogue.filter { it.audioSource == DialogueAudioSource.NATIVE }.map { it.characterAssetId }.distinct().forEach { id ->
                val character = p.assets.find { it.id == id }
                character?.referenceVoice?.path?.let { input(it, character.name, "参考语音", MediaRole.REFERENCE_VOICE, InputKind.AUDIO) }
            }
        }
        var number = 0
        val numbered = packed.map { it.copy(number = if (it.kind == InputKind.IMAGE) ++number else null) }
        val accepted = mutableListOf<PlannedInput>()
        val overflow = mutableListOf<PlannedInput>()
        val counts = mutableMapOf<InputKind, Int>()
        numbered.forEach { item ->
            val maximum = when (item.kind) { InputKind.IMAGE -> channel?.capabilities?.maxImages; InputKind.AUDIO -> channel?.capabilities?.maxAudio; InputKind.VIDEO -> channel?.capabilities?.maxVideos } ?: 0
            val bounded = item.role in setOf(MediaRole.REFERENCE, MediaRole.REFERENCE_VOICE)
            val count = if (bounded) counts.getOrDefault(item.kind, 0) + 1 else 0
            if (bounded) counts[item.kind] = count
            if (bounded && count > maximum) overflow += item else accepted += item
        }
        if (overflow.isNotEmpty()) issues += text("overflow", overflow.joinToString("；") { "${it.title} (${it.path ?: it.compositeOf.joinToString()})" })
        val format = channel?.capabilities?.promptFormat ?: PromptFormat.GENERIC
        val prompt = if (shot.promptUserEdited && !compile) shot.prompt else ShotPromptCompiler.compile(p, shot, accepted, format)
        val specs = if (channel == null) GenerationSpecs() else try { specsFor(channel, shot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { issues += text("invalid_plan", "specs"); GenerationSpecs() }
        if (channel?.id?.startsWith("comfy:") == true) warnings += text("workflow_timing", specs.frames?.toString() ?: channel.id)
        else if (shot.durationSeconds % 1.0 != 0.0) issues += text("invalid_plan", "durationSeconds")
        val request = request(p, episodeId, shot, channel?.id.orEmpty(), prompt, accepted, sessionId, specs)
        if (channel != null) {
            if (backendFor != null && backendFor.invoke(channel) == null) issues += text("unavailable_backend", channel.id)
            val occupiedTasks = queue.observeTasks().first().filter { task ->
                val source = ProductionSourceRef.parse(task.request.sourceRef)
                source?.productionId == p.id && source.episodeId == episodeId && source.shotCode == shotCode && source.dialogueIndex == null &&
                    (task.state !in setOf(MediaTaskState.FAILED, MediaTaskState.COMPLETED) || task.state == MediaTaskState.FAILED && task.failure == MediaTaskFailure.POLL || task.state == MediaTaskState.COMPLETED && shot.takes.none { it.taskId == task.id })
            }
            val reservations = (queue as? com.promenar.infinity.data.mediagen.queue.MediaOutputReservationReader)?.outputReservations(occupiedTasks.map { it.id }.toSet()).orEmpty()
            var occupied = 0
            for (task in occupiedTasks) occupied += if (task.state == MediaTaskState.COMPLETED) task.outputPaths.distinct().count { path -> shot.takes.none { it.taskId == task.id && it.path == path } }
                else reservations[task.id] ?: registry.channel(task.request.channelId)?.let { expectedOutputs(it) } ?: 1
            if (shot.takes.size + occupied + expectedOutputs(channel) > 30) issues += text("candidate_capacity", shotCode)
            issues += validateBackend(channel, request).map { text("invalid_plan", it) }
            val validation = validateRequest(channel, request)
            if (!validation.valid && overflow.isEmpty()) issues += text("invalid_plan", (validation.specErrors + validation.invalidInputs.map { it.location }).joinToString())
        }
        val audioPlans = mutableListOf<PlannedDialogueAudio>()
        val speech = resolveDefaultChannel(MediaGroup.AUDIO, GenerationMode.SPEECH, ordered)
        shot.dialogue.forEachIndexed { index, dialogue ->
            if (dialogue.audioSource != DialogueAudioSource.DUBBED) return@forEachIndexed
            val character = p.assets.find { it.id == dialogue.characterAssetId }
            val voice = character?.referenceVoice?.path
            if (speech == null) { issues += text("no_channel", "SPEECH"); return@forEachIndexed }
            if (voice == null) { issues += text("missing_voice", character?.name ?: dialogue.characterAssetId); return@forEachIndexed }
            if (kindOf(sessionId, voice) != InputKind.AUDIO || !files.isImageOrVideo(sessionId, voice)) issues += text("missing_input", voice)
            val audioRequest = MediaGenerationRequest(speech.id, GenerationMode.SPEECH, dialogue.text,
                listOf(MediaInput(voice, InputKind.AUDIO, MediaRole.REFERENCE_VOICE)), ProductionSourceRef(p.id, episodeId, shot.code, index).encode(),
                sessionId = sessionId, outputDir = "${p.rootPath}/$episodeId/audio")
            val valid = validateRequest(speech, audioRequest)
            if (valid.excessInputs.isNotEmpty()) issues += text("overflow", "${character?.name} ($voice)")
            else if (!valid.valid) issues += text("invalid_plan", "SPEECH ${valid.specErrors.joinToString()}")
            if (backendFor != null && backendFor.invoke(speech) == null) issues += text("unavailable_backend", speech.id)
            issues += validateBackend(speech, audioRequest).map { text("invalid_plan", it) }
            audioPlans += PlannedDialogueAudio(index, dialogue.characterAssetId, dialogue.tone, audioRequest)
        }
        suspend fun estimate(c: MediaChannel, r: MediaGenerationRequest): Money? {
            val backend = backendFor?.invoke(c) ?: return null
            return try { backend.estimate(c, r) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        }
        val costs = if (channel != null && issues.isEmpty()) listOf(estimate(channel, request)) + audioPlans.map { estimate(requireNotNull(registry.channel(it.request.channelId)), it.request) } else listOf(null)
        val cost = costs.firstOrNull()?.let { first -> if (costs.all { it?.currency == first.currency }) Money(costs.fold(java.math.BigDecimal.ZERO) { sum, amount -> sum.add(requireNotNull(amount).amount) }, first.currency) else null }
        return Plan(snapshot, shot, ShotPlanPreview(channel, shot.channelId.isNullOrBlank(), channel?.let { labelOf(it) }.orEmpty(), shot.mode, accepted, overflow,
            prompt, shot.promptUserEdited && !compile, format, cost, issues.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n"), warnings, audioPlans, specs))
    }

    override suspend fun generate(sessionId: String, episodeId: String, shotCode: String): ShotGenerateResult = withContext(ioDispatcher) {
        submissions.withLock {
            val submitted = mutableListOf<MediaTask>()
            try {
                val plan = plan(sessionId, episodeId, shotCode)
                plan.preview.blockedReason?.let { return@withLock ShotGenerateResult.Rejected(it) }
                val channel = requireNotNull(plan.preview.channel)
                val inputs = plan.preview.inputs.map { input -> if (input.compositeOf.isEmpty()) input else input.copy(path = compositor.materialize(sessionId, plan.snapshot.production.rootPath, input.compositeOf)) }
                val actual = request(plan.snapshot.production, episodeId, plan.shot, channel.id, plan.preview.prompt, inputs, sessionId, plan.preview.specs)
                if (store.load(sessionId)?.let { it.production.id == plan.snapshot.production.id && it.revision == plan.snapshot.revision } != true)
                    return@withLock ShotGenerateResult.Rejected(text("plan_changed"))
                submitted += queue.submit(actual)
                plan.preview.audioPlans.forEach { submitted += queue.submit(it.request) }
                ShotGenerateResult.Submitted(submitted.first().id, submitted.any { it.state == MediaTaskState.AWAITING_APPROVAL }, submitted.drop(1).map { it.id })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (submitted.isEmpty()) ShotGenerateResult.Rejected(text("failed"))
                else ShotGenerateResult.Submitted(submitted.first().id, submitted.any { it.state == MediaTaskState.AWAITING_APPROVAL }, submitted.drop(1).map { it.id }, listOf(text("audio_failed", submitted.first().id)))
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
internal fun mediaKindFromPath(path: String): InputKind? = when (path.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "webp", "gif", "bmp" -> InputKind.IMAGE
    "mp4", "webm", "mov", "mkv" -> InputKind.VIDEO
    "wav", "mp3", "flac", "ogg", "m4a", "aac" -> InputKind.AUDIO
    else -> null
}
