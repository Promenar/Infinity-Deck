package com.promenar.infinity.data.production

/** 快照和清单采用相同的审核谱系约束。历史对象可已删除，但标识与修订必须有效。 */
internal fun validateProductionReviews(p: Production, revision: Long = Long.MAX_VALUE) {
    require(p.nextReviewRound > 0 && p.reviewRounds.size <= 50) { "审核轮次无效。" }
    fun entry(value: ReviewEntry) {
        value.target.requireReviewShape()
        require(value.at >= 0 && (value.note == null || value.note.length <= 1000)) { "审核意见时间或长度无效。" }
        require(value.kind == ReviewKind.SUGGEST || value.target.acceptsVerdict) { "这个对象只接受建议。" }
        require(value.kind != ReviewKind.SUGGEST || !value.note.isNullOrBlank()) { "建议内容不能为空。" }
        require(value.round == null || value.round in 1 until p.nextReviewRound) { "审核意见轮次无效。" }
        require(value.sentAt == null || value.sentAt >= 0) { "审核发送时间无效。" }
        require(value.sentRevision == null || value.sentRevision in 0..revision) { "审核发送修订号无效。" }
        require(value.round != null || value.sentAt == null && value.sentRevision == null) { "未发送意见不能包含发送信息。" }
    }
    p.reviews.forEach(::entry)
    val verdicts = p.reviews.filter { it.kind != ReviewKind.SUGGEST }
    require(verdicts.map { it.target }.distinct().size == verdicts.size) { "每个对象只能保留一条结论。" }
    require(verdicts.filter { it.kind == ReviewKind.APPROVE }.groupBy { it.target.reviewGroup() }.values.all { it.size <= 1 }) { "同一对象只能批准一个候选或版本。" }
    require(p.reviews.none { it.kind == ReviewKind.SUGGEST && it.round != null }) { "已发送建议应保存在审核轮次。" }
    require(p.reviewRounds.map { it.number }.distinct().size == p.reviewRounds.size) { "审核记录轮次重复。" }
    p.reviewRounds.forEach { round ->
        require(round.number in 1 until p.nextReviewRound && round.sentAt >= 0 && round.revision in 0..revision) { "审核记录轮次或时间无效。" }
        round.entries.forEach { value ->
            entry(value)
            require(value.round == round.number && (value.sentAt == null || value.sentAt == round.sentAt) &&
                (value.sentRevision == null || value.sentRevision == round.revision)) { "审核记录的发送信息不一致。" }
        }
    }
    require(p.reviewDrafts.map { it.group }.distinct().size == p.reviewDrafts.size) { "审核回退基线重复。" }
    p.reviewDrafts.forEach { draft ->
        require(draft.pendingVerdicts.isNotEmpty()) { "审核基线必须有未发送结论。" }
        when (draft.group.kind) {
            ReviewTargetKind.SHOT -> { draft.group.requireReviewShape(); require(draft.shotStatus != null) { "缺少镜头回退状态。" } }
            ReviewTargetKind.ASSET_VERSION -> {
                require(draft.group == ReviewTarget(ReviewTargetKind.ASSET_VERSION, assetId = draft.group.assetId) && !draft.group.assetId.isNullOrBlank() && draft.assetStatus != null) { "缺少资产回退状态。" }
            }
            ReviewTargetKind.SCRIPT, ReviewTargetKind.SCENE -> { draft.group.requireReviewShape(); require(draft.locked != null) { "缺少锁定回退状态。" } }
            ReviewTargetKind.CUT, ReviewTargetKind.CHANGE_REQUEST -> draft.group.requireReviewShape()
            else -> throw IllegalArgumentException("审核基线对象无效。")
        }
        require(!draft.changeApproved || !draft.changeRejected) { "改动回退状态冲突。" }
        (draft.previousVerdicts + draft.pendingVerdicts).forEach { value ->
            entry(value)
            require(value.kind != ReviewKind.SUGGEST && value.target.reviewGroup() == draft.group) { "审核基线包含其它对象的意见。" }
        }
        require(draft.previousVerdicts.all { it.round != null } && draft.pendingVerdicts.all { it.round == null }) { "审核基线的发送状态无效。" }
        require(draft.pendingVerdicts.map { it.target }.distinct().size == draft.pendingVerdicts.size && draft.pendingVerdicts.all { p.verdictOf(it.target) == it }) { "未发送结论与回退基线不一致。" }
        draft.displacements.forEach { history ->
            history.target.requireReviewShape()
            require(draft.pendingVerdicts.any { it.target == history.target } && (history.approvedVersion == null || history.approvedVersion > 0)) { "审核替换记录无效。" }
            history.displacedTargets.forEach { it.requireReviewShape(); require(it.reviewGroup() == draft.group) { "审核替换记录包含其它对象。" } }
        }
    }
    require(verdicts.filter { it.round == null }.all { value -> p.reviewDrafts.any { draft -> value in draft.pendingVerdicts } }) { "未发送结论缺少回退基线。" }
    require(p.changeRequests.all { it.changedAt in 0..revision && (!it.approved || !it.rejected) }) { "改动请求状态或修订号无效。" }
    p.seasons.flatMap { it.episodes }.forEach { episode ->
        require(episode.contentChangedAt == null || episode.contentChangedAt in 0..revision) { "剧本内容修订号无效。" }
        episode.scenes.forEach { require(it.contentChangedAt == null || it.contentChangedAt in 0..revision) { "场内容修订号无效。" } }
    }
    p.cuts.forEach { require(it.contentChangedAt == null || it.contentChangedAt in 0..revision) { "剪辑内容修订号无效。" } }
}
