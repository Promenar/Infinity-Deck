package com.promenar.infinity.ui.production

import com.promenar.infinity.data.production.*
import kotlinx.coroutines.CancellationException

/** 同一用户操作在仓库事务内决定直接编辑或待审；宿主只映射本地化错误。 */
internal suspend fun requestShotContentChange(
    source: ProductionPanelSource,
    sessionId: String,
    revision: Long,
    location: ShotLocation,
    proposed: Shot,
    conflict: String,
    invalid: String,
    failed: String,
): String? = try {
    source.apply(sessionId, revision, ProductionUserAction.EditShotContent(location.episode.id, location.shot.code, proposed))
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: ProductionRevisionConflictException) {
    conflict
} catch (_: IllegalArgumentException) {
    invalid
} catch (_: Exception) {
    failed
}

/** 只有事务成功才打开详情；过期快照不会导航到预测编号。 */
internal suspend fun addShotAndOpen(
    snapshot: ProductionSnapshot,
    episodeId: String,
    sceneCode: String,
    title: String,
    onAction: ProductionActionHandler,
    onOpen: (String, String) -> Unit,
): String? {
    val scene = snapshot.production.seasons.flatMap { it.episodes }.first { it.id == episodeId }.scenes.first { it.code == sceneCode }
    val code = ProductionRules.nextShotCode(scene)
    val error = onAction(snapshot.revision, ProductionUserAction.AddShot(episodeId, sceneCode, title))
    if (error == null) onOpen(episodeId, code)
    return error
}
