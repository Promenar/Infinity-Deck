package com.promenar.infinity.data.production

/** 纯函数用户操作；调用方负责在事务中核对并保存返回值。 */
object ProductionUserEngine {
    fun apply(snapshot: ProductionSnapshot, expectedRevision: Long, action: ProductionUserAction, now: Long): ProductionSnapshot {
        if (snapshot.revision != expectedRevision) throw ProductionRevisionConflictException()
        if (action is ProductionUserAction.MarkReviewSent && snapshot.production.queuedReviews().none { it.queuedRound == action.round } ||
            action is ProductionUserAction.RevertReviewQueued && snapshot.production.queuedReviews().none { it.queuedRound == action.round }) return snapshot
        require(snapshot.revision < Long.MAX_VALUE) { "项目修订号已达到上限。" }
        val revision = snapshot.revision + 1
        var p = snapshot.production

        fun updateEpisode(id: String, transform: (Episode) -> Episode) {
            var found = false
            p = p.copy(seasons = p.seasons.map { season ->
                season.copy(episodes = season.episodes.map { episode ->
                    if (episode.id == id) { found = true; transform(episode) } else episode
                })
            })
            require(found) { "集 $id 不存在，请先读取项目。" }
        }
        fun updateShot(episodeId: String, shotCode: String, transform: (Shot) -> Shot) {
            var found = false
            updateEpisode(episodeId) { episode ->
                episode.copy(scenes = episode.scenes.map { scene ->
                    scene.copy(shots = scene.shots.map { shot ->
                        if (shot.code == shotCode) { found = true; transform(shot).copy(changedBy = ChangeSource.USER, changedAt = revision) } else shot
                    })
                })
            }
            require(found) { "镜头 $shotCode 不存在，请先读取该集。" }
        }
        fun updateLook(assetId: String, lookId: String, transform: (CharacterLook) -> CharacterLook) {
            var found = false
            p = p.copy(assets = p.assets.map { asset ->
                if (asset.id != assetId) asset else asset.copy(looks = asset.looks.map { look ->
                    if (look.id == lookId) { found = true; transform(look) } else look
                }, changedBy = ChangeSource.USER, changedAt = revision)
            })
            require(found) { "角色造型不存在。" }
        }
        fun updateContent(episodeId: String, shotCode: String, transform: (Shot) -> Shot) {
            val scene = p.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId }?.scenes?.firstOrNull { sc -> sc.shots.any { it.code == shotCode } }
                ?: throw IllegalArgumentException("镜头 $shotCode 不存在。")
            val old = scene.shots.first { it.code == shotCode }
            val updated = transform(old)
            val changes = ProductionRules.changes(old, updated)
            require(!scene.storyboardLocked || action !is ProductionUserAction.EditShotContent || changes.isNotEmpty()) { "镜头内容没有变化。" }
            if (scene.storyboardLocked && changes.isNotEmpty()) {
                // 校验拟提交内容，但只保存请求，保留镜头内容和修订元数据。
                ProductionManifestCodec.encode(p.mapShot(episodeId, shotCode) { updated }, revision)
                val request = ShotChangeRequest(java.util.UUID.randomUUID().toString(), episodeId, scene.code, shotCode, changes, ChangeSource.USER, changedAt = revision)
                p = p.copy(changeRequests = p.changeRequests + request)
            } else if (!scene.storyboardLocked) updateShot(episodeId, shotCode) { updated }
        }
        when (action) {
            is ProductionUserAction.Review -> p = ProductionReviews.review(p, action, revision, now)
            is ProductionUserAction.RetractReview -> p = ProductionReviews.retract(p, action, revision)
            is ProductionUserAction.MarkReviewQueued -> p = ProductionReviews.queued(p, action)
            is ProductionUserAction.RevertReviewQueued -> p = ProductionReviews.revertQueued(p, action, revision)
            is ProductionUserAction.MarkReviewSent -> p = ProductionReviews.sent(p, action, revision)
            is ProductionUserAction.CreateCut -> p = ProductionCuts.create(p, action.episodeId, action.mediaDurations, now)
            is ProductionUserAction.DeleteCut -> p = ProductionCuts.delete(p, action.cutId)
            is ProductionUserAction.SetCutSubtitles -> p = ProductionCuts.setSubtitles(p, action.cutId, action.burnSubtitles)
            is ProductionUserAction.RegisterCutExport -> p = ProductionCuts.registerExport(p, action.cutId, action.record)
            is ProductionUserAction.AddShot -> {
                var found = false
                updateEpisode(action.episodeId) { episode -> episode.copy(scenes = episode.scenes.map { scene ->
                    if (scene.code != action.sceneCode) scene else {
                        found = true
                        require(!scene.storyboardLocked) { "场 ${scene.code} 已锁定，请先解锁后新增镜头。" }
                        val shot = Shot(ProductionRules.nextShotCode(scene), action.title, durationSeconds = 5.0,
                            changedBy = ChangeSource.USER, changedAt = revision)
                        scene.copy(shots = scene.shots + shot, changedBy = ChangeSource.USER, changedAt = revision)
                    }
                }) }
                require(found) { "场 ${action.sceneCode} 不存在，请先读取该集。" }
            }
            is ProductionUserAction.RegisterGeneratedTask -> p = requireNotNull(registerShotTask(p, action.task, now)) { "任务暂不能登记，请核对项目绑定、场锁定与候选容量。" }
            is ProductionUserAction.RecompilePrompt -> updateShot(action.episodeId, action.shotCode) { it.copy(prompt = action.prompt, promptUserEdited = false) }
            is ProductionUserAction.EditShotContent -> updateContent(action.episodeId, action.shotCode) { old ->
                require(action.proposed.code == old.code) { "镜头标识不能修改。" }
                ProductionRules.applyChanges(old, ProductionRules.changes(old, action.proposed))
            }
            is ProductionUserAction.SetExperimental -> p = p.copy(experimental = action.enabled, policyChangedBy = ChangeSource.USER, policyChangedAt = revision)
            is ProductionUserAction.SetShotPlan -> updateShot(action.episodeId, action.shotCode) { it.copy(channelId = action.channelId, mode = action.mode) }
            is ProductionUserAction.ClearPromptEdited -> updateShot(action.episodeId, action.shotCode) { it.copy(promptUserEdited = false) }
            is ProductionUserAction.SetCharacterLook -> updateContent(action.episodeId, action.shotCode) { shot ->
                val character = p.assets.firstOrNull { it.id == action.characterAssetId && it.kind == AssetKind.CHARACTER }
                    ?: throw IllegalArgumentException("角色不存在。")
                val look = character.looks.firstOrNull { it.id == action.lookId } ?: throw IllegalArgumentException("造型不存在。")
                require(shot.appearances.any { it.characterAssetId == character.id }) { "角色未在镜头中出场。" }
                shot.copy(appearances = shot.appearances.map { if (it.characterAssetId == character.id) it.copy(lookId = action.lookId, referenceSlotIds = ProductionRules.defaultSlotIds(look)) else it })
            }
            is ProductionUserAction.ToggleReferenceSlot -> updateShot(action.episodeId, action.shotCode) { shot ->
                val appearance = shot.appearances.firstOrNull { it.characterAssetId == action.characterAssetId } ?: throw IllegalArgumentException("角色未在镜头中出场。")
                val look = p.assets.firstOrNull { it.id == appearance.characterAssetId }?.looks?.firstOrNull { it.id == appearance.lookId }
                require(look?.slots?.any { it.id == action.slotId } == true) { "参考位不存在。" }
                shot.copy(appearances = shot.appearances.map { item -> if (item != appearance) item else item.copy(referenceSlotIds = if (action.selected) (item.referenceSlotIds + action.slotId).distinct() else item.referenceSlotIds - action.slotId) })
            }
            is ProductionUserAction.SetAppearances -> updateContent(action.episodeId, action.shotCode) { it.copy(appearances = action.appearances) }
            is ProductionUserAction.SetDialogueSource -> updateShot(action.episodeId, action.shotCode) { shot ->
                require(action.index in shot.dialogue.indices) { "对白不存在。" }
                shot.copy(dialogue = shot.dialogue.mapIndexed { index, line -> if (index == action.index) line.copy(audioSource = action.source) else line })
            }
            is ProductionUserAction.AddLook -> {
                var found = false
                p = p.copy(assets = p.assets.map { asset -> if (asset.id == action.assetId) {
                    found = true; require(asset.kind == AssetKind.CHARACTER && asset.looks.none { it.id == action.look.id }) { "角色或造型无效。" }
                    asset.copy(looks = asset.looks + action.look, changedBy = ChangeSource.USER, changedAt = revision)
                } else asset })
                require(found) { "角色不存在。" }
            }
            is ProductionUserAction.AddReferenceSlot -> updateLook(action.assetId, action.lookId) { look ->
                require(look.slots.none { it.id == action.slot.id }) { "参考位已存在。" }
                look.copy(slots = look.slots + action.slot)
            }
            is ProductionUserAction.RemoveReferenceSlot -> updateLook(action.assetId, action.lookId) { ProductionRules.removeSlot(it, action.slotId) }
            is ProductionUserAction.EditPrompt -> updateShot(action.episodeId, action.shotCode) { shot ->
                shot.copy(prompt = action.prompt, promptUserEdited = true)
            }
            is ProductionUserAction.SetStage -> p = p.copy(stages = p.stages.map { stage ->
                if (stage.stage == action.stage) stage.copy(status = action.status, changedBy = ChangeSource.USER, changedAt = revision) else stage
            })
        }
        if (action is ProductionUserAction.CreateCut || action is ProductionUserAction.SetCutSubtitles || action is ProductionUserAction.RegisterCutExport) {
            p = p.copy(cuts = p.cuts.map { cut ->
                if (cut != snapshot.production.cuts.firstOrNull { it.id == cut.id }) cut.copy(changedAt = revision, changedBy = ChangeSource.USER) else cut
            })
        }
        p = stampProductionContent(snapshot.production, p, revision).pruneDeletedReviewTargets().copy(updatedAt = now)
        ProductionManifestCodec.encode(p, revision)
        return ProductionSnapshot(p, revision, ManifestSync.Pending)
    }
}
