package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ChainMediaOps {
    suspend fun spec(sessionId: String, path: String): MediaSpec
    suspend fun copy(sessionId: String, source: String, target: String)
    suspend fun tailFrame(sessionId: String, video: String, target: String)
    /** 每个后续段裁去一帧，返回成片实际规格及时间线。 */
    suspend fun concatenate(sessionId: String, paths: List<String>, target: String): ChainConcatenation
}
data class ChainConcatenation(val spec: MediaSpec, val segments: List<TakeSegment>)

/** 以实际帧率裁去一帧；时间线使用每段实测时长。 */
fun chainTimeline(paths: List<String>, specs: List<MediaSpec>, trimSeconds: List<Double>? = null): List<TakeSegment> {
    require(paths.isNotEmpty() && paths.size == specs.size) { "链段规格缺失。" }
    require(trimSeconds == null || trimSeconds.size == paths.size) { "链段裁帧信息缺失。" }
    var cursor = 0.0
    return paths.mapIndexed { index, path ->
        val duration = requireNotNull(specs[index].durationSeconds) { "无法读取链段时长。" }
        val trim = if (index == 0) 0.0 else trimSeconds?.get(index) ?: 1.0 / requireNotNull(specs[index].fps) { "无法读取链段帧率。" }
        require(duration.isFinite() && trim.isFinite() && duration > trim && trim >= 0) { "链段时长不足一帧。" }
        TakeSegment(index, path, cursor, cursor + duration - trim).also { cursor = it.endSeconds }
    }
}

/** 压缩样本可按解码顺序传入；裁切点始终是第二个显示帧的时间。 */
internal fun firstFrameEndUs(presentationTimes: Sequence<Long>): Long {
    var first = Long.MAX_VALUE
    var second = Long.MAX_VALUE
    presentationTimes.filter { it >= 0 }.forEach { time ->
        if (time < first) { second = first; first = time } else if (time > first && time < second) second = time
    }
    require(first != Long.MAX_VALUE && second != Long.MAX_VALUE && second > first) { "链段不足两帧。" }
    return second
}

class ShotChainCoordinator(
    private val store: ProductionWorkStore, private val registry: MediaChannelRegistry, private val queue: MediaGenerationQueue,
    private val planner: ShotGenerationPlanner, private val media: ChainMediaOps,
    private val fingerprint: suspend (String) -> String?,
    private val ready: suspend (String) -> Boolean = { true },
    private val specsFor: suspend (MediaChannel, Shot) -> GenerationSpecs = { _, shot -> GenerationSpecs(durationSeconds = shot.durationSeconds.toInt()) },
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private suspend fun load(sessionId: String) = requireNotNull(store.load(sessionId)) { "当前会话没有制作项目。" }
    private suspend fun channel(id: String) = requireNotNull(registry.channel(id)) { "镜头通道不可用。" }
    private suspend fun save(session: String, snapshot: ProductionSnapshot, ep: String, shot: String, chain: ShotChain, take: Take? = null) =
        store.apply(session, snapshot, ProductionUserAction.SaveChain(ep, shot, chain, take))

    suspend fun start(sessionId: String, episodeId: String, shotCode: String, startTakeId: String? = null, segments: Int = 3,
        segmentSeconds: Double? = null, segmentPrompts: List<String> = emptyList()): ShotChain = withContext(Dispatchers.IO) {
        ProductionSubmissionMutex.withLock { mutex.withLock {
            val snapshot = load(sessionId); val p = snapshot.production; val shot = p.requiredShot(episodeId, shotCode)
            require(shot.chains.none { it.isActive() }) { "该镜头已有进行中的衔接链。" }
            require(segments in 1..8) { "segments 需要 1～8。" }
            require(segmentPrompts.isEmpty() || segmentPrompts.size == segments) { "segment_prompts 数量必须等于 segments。" }
            require(segmentPrompts.all { it.isNotBlank() && it.length <= 7000 }) { "每段提示词需要 1～7000 字。" }
            val source = startTakeId?.let { id -> requireNotNull(shot.takes.firstOrNull { it.id == id }) { "起点候选不存在。" } }
            val plan = planner.preview(sessionId, episodeId, shotCode)
            val selected = requireNotNull(plan.channel) { "没有可用的镜头通道。" }
            require(GenerationMode.FIRST_FRAME in selected.capabilities.modes) { "通道不支持尾帧衔接；当前可用：${registry.observeChannels().first().filter { it.group == MediaGroup.VIDEO && GenerationMode.FIRST_FRAME in it.capabilities.modes }.joinToString { it.id }.ifEmpty { "无" }}" }
            require(ready(selected.id)) { "模板需要通过预检。" }
            if (source == null) require(plan.blockedReason == null) { plan.blockedReason.orEmpty() }
            ProductionRules.generationRejection(p, episodeId, shotCode)?.let { throw IllegalArgumentException(it) }
            require(occupiedShotCandidates(p, episodeId, shotCode, queue) < 30) { "候选容量不足。" }
            require(source != null || plan.inputs.none { it.path == null }) { "首段合图尚未落盘，请先生成起点候选。" }
            val seconds = segmentSeconds ?: shot.durationSeconds.coerceIn((selected.capabilities.minDurationSeconds ?: 0).toDouble(), (selected.capabilities.maxDurationSeconds ?: Int.MAX_VALUE).toDouble())
            require(seconds.isFinite() && seconds > 0 && seconds <= Int.MAX_VALUE && selected.capabilities.minDurationSeconds?.let { seconds >= it } != false && selected.capabilities.maxDurationSeconds?.let { seconds <= it } != false) { "每段时长超出通道范围。" }
            val durationSpecs = specsFor(selected, shot.copy(durationSeconds = seconds))
            if (segmentSeconds != null) require(durationSpecs.durationSecondsExact == seconds || durationSpecs.durationSeconds?.toDouble() == seconds) { "当前模板使用固定工作流时长，不能覆盖 segment_seconds；可省略该参数，或配置 DURATION 秒数槽位。" }
            val sourceSeconds = source?.let { requireNotNull(media.spec(sessionId, it.path).durationSeconds) { "无法读取起点候选时长。" } }
            val frozen = TakePlanSnapshot(selected.id, shot.mode, shot.inputs, plan.prompt, (if (source == null) plan.inputs else emptyList()).map {
                MediaInput(requireNotNull(it.path), it.kind, it.role)
            }, plan.specs, shot.mode.toGenerationMode(), fingerprint(selected.id), params = plan.params)
            val time = now()
            val chain = ShotChain(newId(), ChainState.PLANNED, List(segments) { index -> ChainSegment(index,
                if (index == 0 && sourceSeconds != null) sourceSeconds else seconds, segmentPrompts.getOrNull(index)) },
                startTakeId = startTakeId, channelId = selected.id, templateFingerprint = frozen.templateFingerprint,
                createdAt = time, updatedAt = time, planSnapshot = frozen)
            save(sessionId, snapshot, episodeId, shotCode, chain)
            chain
        } }
    }
    suspend fun status(sessionId: String, episodeId: String, shotCode: String, chainId: String): ShotChain =
        requireNotNull(load(sessionId).production.requiredShot(episodeId, shotCode).chains.firstOrNull { it.id == chainId }) { "衔接链不存在。" }

    suspend fun cancel(sessionId: String, episodeId: String, shotCode: String, chainId: String): ShotChain = withContext(Dispatchers.IO) {
        val snapshot = load(sessionId)
        store.apply(sessionId, snapshot, ProductionUserAction.CancelChain(episodeId, shotCode, chainId)).production.requiredShot(episodeId, shotCode).chains.first { it.id == chainId }
    }
    suspend fun retry(sessionId: String, episodeId: String, shotCode: String, chainId: String): ShotChain = withContext(Dispatchers.IO) {
        ProductionSubmissionMutex.withLock { mutex.withLock {
            val snapshot = load(sessionId); val shot = snapshot.production.requiredShot(episodeId, shotCode)
            val chain = requireNotNull(shot.chains.firstOrNull { it.id == chainId }) { "衔接链不存在。" }
            require(chain.state == ChainState.FAILED) { "衔接链当前为 ${chain.state}，不能重试。" }
            require(shot.chains.none { it.id != chainId && it.isActive() }) { "该镜头已有进行中的衔接链。" }
            require(occupiedShotCandidates(snapshot.production, episodeId, shotCode, queue) < 30) { "候选容量不足。" }
            val unresolved = (queue as? MediaTaskRecoveryReader)?.unresolvedTaskIds()
            val uncertain = queue.observeTasks().first().any { task -> task.request.productionContext?.chainId == chainId &&
                if (unresolved != null) task.id in unresolved else (task.state == MediaTaskState.RESULT_UNKNOWN || task.state == MediaTaskState.FAILED && task.failure == MediaTaskFailure.POLL) }
            require(!uncertain) { "远端任务结果尚未确认，请先核实原任务状态，再重试衔接链。" }
            val id = requireNotNull(chain.channelId); require(ready(id)) { "通道或模板不可用。" }
            require(chain.attempt < Int.MAX_VALUE) { "衔接重试代次已达上限。" }
            val concatenationOnly = chain.segments.all { it.state == ChainSegmentState.COMPLETED }
            val next = chain.copy(state = if (concatenationOnly) ChainState.CONCATENATING else ChainState.RUNNING,
                segments = chain.segments.map { if (concatenationOnly || it.index < chain.current) it else it.copy(state = ChainSegmentState.PENDING, taskId = null, path = null, firstFramePath = null, failure = null, taskOutputPath = null) },
                failure = null, updatedAt = now(), templateFingerprint = fingerprint(id), attempt = chain.attempt + 1)
            save(sessionId, snapshot, episodeId, shotCode, next)
            next
        } }
    }

    /** 首轮遍历持久项目；不依赖本进程记忆的链列表。 */
    suspend fun reconcile(sessionId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val initial = store.load(sessionId) ?: return@withLock
            for (episode in initial.production.seasons.flatMap { it.episodes }) for (shot in episode.scenes.flatMap { it.shots }) for (chain in shot.chains) {
                try { advance(sessionId, initial.production.id, episode.id, shot.code, chain.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: ProductionRevisionConflictException) { }
                catch (failure: Exception) {
                    val current = store.load(sessionId) ?: continue
                    if (current.production.id != initial.production.id) continue
                    val old = current.production.requiredShot(episode.id, shot.code).chains.firstOrNull { it.id == chain.id } ?: continue
                    if (old.isActive()) {
                        val failedIndex = if (old.state == ChainState.HANDOFF) (old.current + 1).coerceAtMost(old.segments.lastIndex) else old.current
                        val message = (failure as? IllegalArgumentException)?.message?.takeIf { it.any { c -> c.code > 0x2E80 } }?.take(1000) ?: "衔接处理失败，请核对媒体和通道。"
                        try { save(sessionId, current, episode.id, shot.code, old.copy(state = ChainState.FAILED, current = failedIndex, failure = message, updatedAt = now(),
                            segments = old.segments.map { if (it.index == failedIndex && it.state != ChainSegmentState.COMPLETED) it.copy(state = ChainSegmentState.FAILED, failure = message) else it })) }
                        catch (_: ProductionRevisionConflictException) { }
                    }
                }
            }
        }
    }
    private suspend fun advance(session: String, projectId: String, ep: String, code: String, id: String) {
        val snapshot = load(session); val p = snapshot.production
        if (p.id != projectId) return
        val shot = p.requiredShot(ep, code)
        val chain = shot.chains.firstOrNull { it.id == id } ?: return
        val tasks = queue.observeTasks().first().filter { it.request.sessionId == session && it.request.sourceRef == ProductionSourceRef(p.id, ep, code).encode() && it.request.productionContext?.chainId == id }
        if (chain.state == ChainState.CANCELED) {
            tasks.filter { it.state !in setOf(MediaTaskState.COMPLETED, MediaTaskState.CANCELED) }.forEach { queue.cancel(it.id) }
            return
        }
        if (!chain.isActive()) return
        val directory = "${p.rootPath}/$ep/takes/chain-$id"
        suspend fun persist(next: ShotChain, take: Take? = null) = save(session, snapshot, ep, code, next.copy(updatedAt = now()), take)
        if (chain.state == ChainState.CONCATENATING) {
            val rendered = "$directory/joined-${chain.attempt}.mp4"
            val result = media.concatenate(session, chain.segments.map { requireNotNull(it.path) }, rendered)
            media.copy(session, rendered, "$directory/joined.mp4")
            val takeId = UUID.nameUUIDFromBytes("chain:$projectId:$id".toByteArray(Charsets.UTF_8)).toString()
            val take = Take(takeId, "$directory/joined.mp4", TakeSource(requireNotNull(chain.channelId), mode = ShotMode.FIRST_FRAME), now(),
                planSnapshot = chain.planSnapshot, segments = result.segments, spec = result.spec, chainId = id)
            persist(chain.copy(state = ChainState.COMPLETED, takeId = take.id, outputPath = rendered), take)
            return
        }
        val k = chain.current
        val segment = chain.segments[k]
        if (chain.state == ChainState.HANDOFF) {
            val nextIndex = k + 1
            require(nextIndex in chain.segments.indices) { "衔接链段超出范围。" }
            if (fingerprint(requireNotNull(chain.channelId)) != chain.templateFingerprint) {
                persist(chain.copy(state = ChainState.FAILED, current = nextIndex, failure = "模板已变化",
                    segments = chain.segments.map { if (it.index == nextIndex) it.copy(state = ChainSegmentState.FAILED, failure = "模板已变化") else it }))
                return
            }
            val frame = "$directory/seg-$k-last.png"
            media.tailFrame(session, requireNotNull(segment.path), frame)
            persist(chain.copy(state = ChainState.RUNNING, current = nextIndex, segments = chain.segments.map { if (it.index == nextIndex) it.copy(firstFramePath = frame) else it }))
            return
        }
        if (k == 0 && chain.startTakeId != null && segment.state != ChainSegmentState.COMPLETED) {
            val source = requireNotNull(shot.takes.firstOrNull { it.id == chain.startTakeId }) { "起点候选不存在。" }
            val target = "$directory/seg-0.mp4"
            media.copy(session, source.path, target)
            persist(chain.copy(state = if (chain.segments.size == 1) ChainState.CONCATENATING else ChainState.HANDOFF,
                segments = chain.segments.map { if (it.index == 0) it.copy(state = ChainSegmentState.COMPLETED, path = target) else it }))
            return
        }
        val task = segment.taskId?.let { taskId -> tasks.firstOrNull { it.id == taskId } }
            ?: tasks.firstOrNull { it.request.productionContext?.let { ctx -> ctx.segmentIndex == k && ctx.attempt == chain.attempt } == true }
        if (task == null) {
            require(segment.taskId == null) { "链段任务不存在，无法确认提交结果。" }
            val channel = channel(requireNotNull(chain.channelId))
            require(fingerprint(channel.id) == chain.templateFingerprint) { "模板已变化" }
            require(ready(channel.id)) { "模板或通道不可用。" }
            val frozen = requireNotNull(chain.planSnapshot) { "链缺少生成规划。" }
            val frame = if (k == 0) null else segment.firstFramePath ?: "$directory/seg-${k - 1}-last.png".also {
                media.tailFrame(session, requireNotNull(chain.segments[k - 1].path), it)
            }
            val inputs = if (k == 0) frozen.mediaInputs else listOf(MediaInput(requireNotNull(frame), InputKind.IMAGE, com.promenar.infinity.domain.mediagen.InputRole.FIRST_FRAME))
            val mode = if (k == 0) frozen.generationMode ?: frozen.mode.toGenerationMode() else GenerationMode.FIRST_FRAME
            val specs = specsFor(channel, shot.copy(durationSeconds = segment.seconds))
            val request = MediaGenerationRequest(channel.id, mode, segment.prompt ?: frozen.prompt, inputs, ProductionSourceRef(p.id, ep, code).encode(), specs,
                session, "$directory/attempt-${chain.attempt}", frozen.params, ProductionMediaContext(templateFingerprint = chain.templateFingerprint, chainId = id, segmentIndex = k, attempt = chain.attempt))
            require(validateRequest(channel, request).valid) { "通道不支持链段的输入或时长。" }
            val before = load(session)
            if (before.production.id != p.id || before.revision != snapshot.revision) throw ProductionRevisionConflictException()
            val submitted = queue.submit(request)
            // 即使写回发生修订冲突，下一轮也能按链、段、代次重新定位持久任务。
            persist(chain.copy(state = ChainState.RUNNING, segments = chain.segments.map { if (it.index == k) it.copy(state = ChainSegmentState.RUNNING, taskId = submitted.id, firstFramePath = frame) else it }))
            return
        }
        when (task.state) {
            MediaTaskState.COMPLETED -> {
                require(task.outputPaths.size == 1) { "链段需要一个视频产物。" }
                val path = "$directory/seg-$k.mp4"
                if (task.outputPaths.single() != path) media.copy(session, task.outputPaths.single(), path)
                val actual = requireNotNull(media.spec(session, path).durationSeconds) { "链段视频不可读。" }
                persist(chain.copy(state = if (k == chain.segments.lastIndex) ChainState.CONCATENATING else ChainState.HANDOFF,
                    segments = chain.segments.map { if (it.index == k) it.copy(state = ChainSegmentState.COMPLETED, taskId = task.id, path = path, seconds = actual, taskOutputPath = task.outputPaths.single()) else it }))
            }
            MediaTaskState.FAILED, MediaTaskState.CANCELED, MediaTaskState.DOWNLOAD_FAILED, MediaTaskState.RESULT_UNKNOWN -> {
                val reason = task.error?.take(1000) ?: "链段任务失败：${task.state}"
                persist(chain.copy(state = ChainState.FAILED, failure = reason, segments = chain.segments.map { if (it.index == k) it.copy(state = ChainSegmentState.FAILED, taskId = task.id, failure = reason) else it }))
            }
            else -> if (segment.taskId != task.id || chain.state == ChainState.PLANNED) persist(chain.copy(state = ChainState.RUNNING,
                segments = chain.segments.map { if (it.index == k) it.copy(state = ChainSegmentState.RUNNING, taskId = task.id) else it }))
        }
    }
}
