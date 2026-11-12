package com.promenar.infinity.data.production

/** 审核事务只修改审核对象的效果字段，不回滚其它制作内容。 */
internal object ProductionReviews {
    fun review(production: Production, action: ProductionUserAction.Review, revision: Long, now: Long): Production {
        production.requireReviewTarget(action.target)
        val note = action.note?.trim()?.takeIf { it.isNotEmpty() }
        require(note == null || note.length <= 1000) { "审核意见最多 1000 字。" }
        val entry = ReviewEntry(action.target, action.kind, note, now)
        if (action.kind == ReviewKind.SUGGEST) {
            require(note != null) { "建议内容不能为空。" }
            return production.copy(reviews = production.reviews + entry)
        }
        require(action.target.acceptsVerdict) { "这个对象只接受建议。" }
        val group = action.target.reviewGroup()
        val baseline = production.reviewDrafts.firstOrNull { it.group == group } ?: baseline(production, group)
        val pending = baseline.pendingVerdicts.filterNot {
            it.target == action.target || action.kind == ReviewKind.APPROVE && it.kind == ReviewKind.APPROVE
        } + entry
        val displaced = if (action.kind == ReviewKind.APPROVE) {
            (baseline.previousVerdicts.filter { it.kind == ReviewKind.APPROVE }.map { it.target } +
                listOfNotNull(baseline.selectedTakeId?.let { ReviewTarget.take(requireNotNull(group.episodeId), requireNotNull(group.shotCode), it) }) +
                baseline.displacements.flatMap { it.displacedTargets }).filterNot { it == action.target }.distinct()
        } else baseline.displacements.firstOrNull { it.target == action.target }?.displacedTargets.orEmpty()
        val history = ReviewDisplacement(action.target, displaced,
            if (action.kind == ReviewKind.APPROVE) action.target.version else baseline.displacements.firstOrNull { it.target == action.target }?.approvedVersion)
        val draft = baseline.copy(pendingVerdicts = pending,
            displacements = baseline.displacements.filter { item -> item.target != action.target && pending.any { it.target == item.target } } + history)
        return rebuild(production, draft, revision).copy(reviewDrafts = production.reviewDrafts.filterNot { it.group == group } + draft)
    }

    fun retract(production: Production, action: ProductionUserAction.RetractReview, revision: Long): Production {
        production.requireReviewTarget(action.target)
        if (action.kind == ReviewKind.SUGGEST) return production.copy(reviews = production.reviews.filterNot {
            it.target == action.target && it.kind == ReviewKind.SUGGEST && it.round == null
        })
        require(action.target.acceptsVerdict) { "这个对象只接受建议。" }
        val current = production.verdictOf(action.target)
        if (current?.round != null || current?.kind != action.kind) return production
        val group = action.target.reviewGroup()
        val baseline = production.reviewDrafts.firstOrNull { it.group == group }
            ?: throw IllegalArgumentException("未发送结论缺少回退信息，请刷新制作面板。")
        val draft = baseline.copy(pendingVerdicts = baseline.pendingVerdicts.filterNot { it.target == action.target },
            displacements = baseline.displacements.filterNot { it.target == action.target })
        return rebuild(production, draft, revision).copy(reviewDrafts = production.reviewDrafts.filterNot { it.group == group } +
            if (draft.pendingVerdicts.isEmpty()) emptyList() else listOf(draft))
    }

    fun sent(production: Production, action: ProductionUserAction.MarkReviewSent, revision: Long): Production {
        if (action.round != production.nextReviewRound) throw ProductionRevisionConflictException()
        require(action.round < Int.MAX_VALUE) { "审核轮次已达到上限。" }
        require(action.sentAt >= 0) { "发送时间不能为负数。" }
        val entries = production.unsentReviews().map { it.copy(round = action.round, sentAt = action.sentAt, sentRevision = revision) }
        require(entries.isNotEmpty()) { "没有未发送的审核意见。" }
        return production.copy(
            reviews = production.reviews.filterNot { it.kind == ReviewKind.SUGGEST }.map { if (it.round == null) it.copy(round = action.round, sentAt = action.sentAt, sentRevision = revision) else it },
            reviewRounds = (listOf(ReviewRound(action.round, action.sentAt, entries, revision)) + production.reviewRounds).take(50),
            nextReviewRound = action.round + 1,
            reviewDrafts = emptyList(),
        )
    }

    private fun baseline(p: Production, group: ReviewTarget): ReviewDraft {
        val verdicts = p.reviews.filter { it.kind != ReviewKind.SUGGEST && it.target.reviewGroup() == group }
        val shot = if (group.kind == ReviewTargetKind.SHOT) p.reviewShot(group) else null
        val asset = if (group.kind == ReviewTargetKind.ASSET_VERSION) p.assets.firstOrNull { it.id == group.assetId } else null
        val locked = when (group.kind) {
            ReviewTargetKind.SCRIPT -> p.reviewEpisode(group.episodeId)?.scriptLocked
            ReviewTargetKind.SCENE -> p.reviewEpisode(group.episodeId)?.scenes?.firstOrNull { it.code == group.sceneCode }?.storyboardLocked
            else -> null
        }
        val change = p.changeRequests.firstOrNull { group.kind == ReviewTargetKind.CHANGE_REQUEST && it.id == group.requestId }
        return ReviewDraft(group, verdicts, selectedTakeId = shot?.selectedTakeId, shotStatus = shot?.status,
            currentVersion = asset?.currentVersion, assetStatus = asset?.status, locked = locked,
            changeApproved = change?.approved ?: false, changeRejected = change?.rejected ?: false)
    }

    private fun rebuild(p: Production, draft: ReviewDraft, revision: Long): Production {
        val suppressed = draft.displacements.flatMap { it.displacedTargets }.toSet()
        var verdicts = draft.previousVerdicts.filterNot { it.target in suppressed }
        draft.pendingVerdicts.forEach { entry ->
            verdicts = verdicts.filterNot { it.target == entry.target || entry.kind == ReviewKind.APPROVE && it.kind == ReviewKind.APPROVE } + entry
        }
        val group = draft.group
        var result = p.copy(reviews = p.reviews.filterNot { it.kind != ReviewKind.SUGGEST && it.target.reviewGroup() == group } + verdicts)
        val current = verdicts.lastOrNull()
        val pending = draft.pendingVerdicts.isNotEmpty()
        fun userShot(episodeId: String, code: String, transform: (Shot) -> Shot) {
            result = result.mapShot(episodeId, code) { transform(it).copy(changedBy = ChangeSource.USER, changedAt = revision) }
        }
        when (group.kind) {
            ReviewTargetKind.SHOT -> userShot(requireNotNull(group.episodeId), requireNotNull(group.shotCode)) { shot ->
                val legacySelected = draft.selectedTakeId?.takeIf { id ->
                    val target = ReviewTarget.take(requireNotNull(group.episodeId), requireNotNull(group.shotCode), id)
                    draft.previousVerdicts.none { it.target == target } && target !in suppressed && verdicts.none { it.target == target }
                }
                val selected = verdicts.firstOrNull { it.kind == ReviewKind.APPROVE }?.target?.takeId ?: legacySelected
                val effective = if (legacySelected == null) verdicts else verdicts + ReviewEntry(
                    ReviewTarget.take(requireNotNull(group.episodeId), requireNotNull(group.shotCode), legacySelected), ReviewKind.APPROVE, at = 0)

                shot.copy(selectedTakeId = if (pending) selected else draft.selectedTakeId,
                    status = if (pending || verdicts.isNotEmpty()) deriveShotReviewStatus(shot, effective) else requireNotNull(draft.shotStatus))
            }
            ReviewTargetKind.ASSET_VERSION -> result = result.copy(assets = result.assets.map { asset ->
                if (asset.id != group.assetId) asset else {
                    val approved = verdicts.firstOrNull { it.kind == ReviewKind.APPROVE }?.target?.version
                    val version = if (pending) approved ?: draft.displacements.lastOrNull { it.approvedVersion != null }?.approvedVersion ?: draft.currentVersion else draft.currentVersion
                    val status = when {
                        !pending -> requireNotNull(draft.assetStatus)
                        approved != null -> AssetStatus.LOCKED
                        verdicts.any { it.kind == ReviewKind.REJECT && it.target.version == version } -> AssetStatus.REDO
                        else -> requireNotNull(draft.assetStatus)
                    }
                    asset.copy(currentVersion = version, status = status, changedBy = ChangeSource.USER, changedAt = revision)
                }
            })
            ReviewTargetKind.SCRIPT, ReviewTargetKind.SCENE -> result = result.copy(seasons = result.seasons.map { season ->
                season.copy(episodes = season.episodes.map { episode ->
                    if (episode.id != group.episodeId) episode else {
                        val locked = if (pending) current?.kind == ReviewKind.APPROVE else requireNotNull(draft.locked)
                        if (group.kind == ReviewTargetKind.SCRIPT) episode.copy(scriptLocked = locked, changedBy = ChangeSource.USER, changedAt = revision, contentChangedAt = episode.contentChangedAt ?: episode.changedAt)
                        else episode.copy(scenes = episode.scenes.map { scene ->
                            if (scene.code != group.sceneCode) scene else scene.copy(storyboardLocked = locked, changedBy = ChangeSource.USER, changedAt = revision, contentChangedAt = scene.contentChangedAt ?: scene.changedAt)
                        })
                    }
                })
            })
            ReviewTargetKind.CHANGE_REQUEST -> {
                val request = result.changeRequests.first { it.id == group.requestId }
                val approved = if (pending) current?.kind == ReviewKind.APPROVE else draft.changeApproved
                val rejected = if (pending) current?.kind == ReviewKind.REJECT else draft.changeRejected
                if (request.approved != approved) userShot(request.episodeId, request.shotCode) { shot ->
                    val changes = if (approved) request.changes else request.changes.mapValues { (_, value) -> ShotFieldChange(value.newValue, value.oldValue) }
                    ProductionRules.applyChanges(shot, changes)
                }
                result = result.copy(changeRequests = result.changeRequests.map { if (it.id == request.id) it.copy(approved = approved, rejected = rejected, changedBy = ChangeSource.USER, changedAt = revision) else it })
            }
            ReviewTargetKind.CUT -> Unit
            else -> error("不支持的审核效果。")
        }
        return result
    }
}
