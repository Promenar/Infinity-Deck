package com.promenar.infinity.data.production

/** 纯函数用户操作；调用方负责在事务中核对并保存返回值。 */
object ProductionUserEngine {
    fun apply(snapshot: ProductionSnapshot, expectedRevision: Long, action: ProductionUserAction, now: Long): ProductionSnapshot {
        require(snapshot.revision == expectedRevision) { "面板已更新，请重试。" }
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
        when (action) {
            is ProductionUserAction.SelectTake -> updateShot(action.episodeId, action.shotCode) { shot ->
                require(shot.takes.any { it.id == action.takeId }) { "候选 ${action.takeId} 不存在，请重新选择。" }
                shot.copy(selectedTakeId = action.takeId, status = ShotStatus.LOCKED, userNote = null)
            }
            is ProductionUserAction.RequestRedo -> updateShot(action.episodeId, action.shotCode) { shot ->
                shot.copy(selectedTakeId = null, status = ShotStatus.REDO, userNote = action.note)
            }
            is ProductionUserAction.UnlockShot -> updateShot(action.episodeId, action.shotCode) { shot ->
                require(shot.status == ShotStatus.LOCKED) { "镜头未锁定，请先检查状态。" }
                shot.copy(status = ShotStatus.REVIEW)
            }
            is ProductionUserAction.EditPrompt -> updateShot(action.episodeId, action.shotCode) { shot ->
                require(shot.status != ShotStatus.LOCKED) { "镜头已锁定，请先解锁。" }
                shot.copy(prompt = action.prompt)
            }
            is ProductionUserAction.SetAssetStatus -> {
                var found = false
                p = p.copy(assets = p.assets.map { asset ->
                    if (asset.id == action.assetId) {
                        found = true
                        asset.copy(status = action.status, userNote = action.note, changedBy = ChangeSource.USER, changedAt = revision)
                    } else asset
                })
                require(found) { "资产 ${action.assetId} 不存在，请先读取资产库。" }
            }
            is ProductionUserAction.LockScene -> {
                var found = false
                updateEpisode(action.episodeId) { episode -> episode.copy(scenes = episode.scenes.map { scene ->
                    if (scene.code == action.sceneCode) { found = true; scene.copy(storyboardLocked = action.locked, changedBy = ChangeSource.USER, changedAt = revision) } else scene
                }) }
                require(found) { "场 ${action.sceneCode} 不存在，请先读取该集。" }
            }
            is ProductionUserAction.LockScript -> updateEpisode(action.episodeId) { episode ->
                episode.copy(scriptLocked = action.locked, changedBy = ChangeSource.USER, changedAt = revision)
            }
            is ProductionUserAction.SetStage -> p = p.copy(stages = p.stages.map { stage ->
                if (stage.stage == action.stage) stage.copy(status = action.status, changedBy = ChangeSource.USER, changedAt = revision) else stage
            })
        }
        p = p.copy(updatedAt = now)
        ProductionManifestCodec.encode(p, revision)
        return ProductionSnapshot(p, revision, ManifestSync.Pending)
    }
}
