package com.promenar.infinity.data.production

import kotlin.math.abs

internal fun validateProductionProgress(p: Production) {
    fun unique(ids: List<String>, label: String) { require(ids.all { it.isNotBlank() && it.length <= 128 } && ids.distinct().size == ids.size) { "$label 标识无效或重复。" } }
    fun duration(seconds: Double?) { require(seconds == null || seconds.isFinite() && seconds > 0 && seconds <= ProductionCuts.MAX_TIMELINE_SECONDS) { "媒体时长必须是有限正数。" } }
    fun spec(value: MediaSpec?) { value?.let { require((it.width == null || it.width > 0) && (it.height == null || it.height > 0) && (it.fps == null || it.fps.isFinite() && it.fps > 0)) { "媒体规格无效。" }; duration(it.durationSeconds) } }
    fun derivation(value: TakeDerivation?) { value?.let { require(it.sourceTakeId.isNotBlank() && it.operation.isNotBlank() && it.operation.length <= 120) { "派生来源与处理名称不能为空。" } } }
    fun segments(values: List<TakeSegment>) {
        require(values.map { it.index } == values.indices.toList()) { "分段下标必须从零连续递增。" }
        var end = 0.0
        values.forEach { segment ->
            ProductionCuts.validateProjectPath(p, segment.path)
            require(segment.startSeconds.isFinite() && abs(segment.startSeconds - end) < 0.00001 && segment.endSeconds.isFinite() && segment.endSeconds > segment.startSeconds) { "分段时间线必须连续且时长为正。" }
            duration(segment.endSeconds); end = segment.endSeconds
        }
    }
    val shots = p.seasons.flatMap { it.episodes }.flatMap { it.scenes }.flatMap { it.shots }
    unique(shots.flatMap { it.chains }.map { it.id }, "衔接链")
    shots.forEach { shot ->
        require(shot.chains.count { it.isActive() } <= 1) { "每个镜头只能有一条进行中的衔接链。" }
        shot.takes.forEach { take ->
            spec(take.spec); derivation(take.derivedFrom); segments(take.segments)
            take.derivedFrom?.let { require(it.sourceTakeId != take.id && shot.takes.any { source -> source.id == it.sourceTakeId }) { "派生来源候选不存在或引用自身。" } }
            // 沿直接来源检查循环，避免损坏的清单形成无根谱系。
            val visited = mutableSetOf(take.id)
            var source = take.derivedFrom?.sourceTakeId
            while (source != null) { require(visited.add(source)) { "候选派生来源存在循环。" }; source = shot.takes.firstOrNull { it.id == source }?.derivedFrom?.sourceTakeId }
            take.runId?.let { id -> require(p.runs.any { it.id == id && it.resultTakeId == take.id }) { "候选登记项不存在。" } }
            take.chainId?.let { id -> require(shot.chains.any { it.id == id && it.takeId == take.id }) { "候选衔接链不存在。" } }
        }
        shot.chains.forEach { chain ->
            chain.outputPath?.let { ProductionCuts.validateProjectPath(p, it) }
            require(chain.segments.size in 1..8 && chain.segments.map { it.index } == chain.segments.indices.toList()) { "链段下标须从零连续递增，最多 8 段。" }
            require(chain.current in chain.segments.indices && chain.attempt >= 0 && chain.createdAt >= 0 && chain.updatedAt >= chain.createdAt) { "衔接链当前段或时间无效。" }
            require(chain.startTakeId == null || shot.takes.any { it.id == chain.startTakeId }) { "衔接起点候选不存在。" }
            chain.segments.forEach { s -> duration(s.seconds); s.path?.let { ProductionCuts.validateProjectPath(p, it) }; s.firstFramePath?.let { ProductionCuts.validateProjectPath(p, it) }; s.taskOutputPath?.let { ProductionCuts.validateProjectPath(p, it) }
                require(s.state != ChainSegmentState.COMPLETED || s.path != null) { "已完成链段缺少路径。" } }
            require(chain.state != ChainState.COMPLETED || chain.takeId != null && shot.takes.any { it.id == chain.takeId } && chain.segments.all { it.state == ChainSegmentState.COMPLETED }) { "已完成衔接链缺少候选或链段。" }
        }
    }
    unique(p.runs.map { it.id }, "登记项")
    p.runs.forEach { run ->
        p.validateRunTarget(run.target)
        require(run.label.isNotBlank() && run.label.length <= 120 && (run.message?.length ?: 0) <= 1000 && (run.failure?.length ?: 0) <= 1000) { "登记项名称或说明长度无效。" }
        require(run.completedSteps >= 0 && (run.steps == null || run.steps > 0 && run.completedSteps <= run.steps)) { "登记项完成步数不能超出总步数。" }
        require(run.startedAt >= 0 && run.reportedAt >= run.startedAt) { "登记项回报时间无效。" }
        run.resultPath?.let { ProductionCuts.validateProjectPath(p, it) }; segments(run.segments)
        if (run.state == RunState.COMPLETED) {
            if (run.target.assetId != null) require(p.assets.first { it.id == run.target.assetId }.versions.any { it.number == run.resultAssetVersion }) { "登记项产物资产版本不存在。" }
            else require(p.requiredShot(requireNotNull(run.target.episodeId), requireNotNull(run.target.shotCode)).takes.any { it.id == run.resultTakeId }) { "登记项产物候选不存在。" }
        }
    }
    p.assets.flatMap { it.versions }.forEach { duration(it.durationSeconds); derivation(it.derivedFrom) }
    require(p.unregisteredFiles == p.unregisteredFiles.distinct().sorted()) { "未登记文件必须唯一并按路径排序。" }
    p.unregisteredFiles.forEach { ProductionCuts.validateProjectPath(p, it) }
}
