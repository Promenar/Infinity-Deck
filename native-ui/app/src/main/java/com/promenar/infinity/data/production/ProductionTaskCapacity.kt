package com.promenar.infinity.data.production

import com.promenar.infinity.data.mediagen.queue.MediaOutputReservationReader
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/** 所有制作提交共享容量检查窗口；仓库修订号另行保护快照。 */
internal val ProductionSubmissionMutex = Mutex()

internal suspend fun occupiedShotCandidates(production: Production, episodeId: String, shotCode: String, queue: MediaGenerationQueue,
    fallbackOutputs: suspend (MediaTask) -> Int = { 1 }): Int {
    val shot = production.requiredShot(episodeId, shotCode)
    val tasks = queue.observeTasks().first().filter { task ->
        val ref = ProductionSourceRef.parse(task.request.sourceRef)
        ref?.productionId == production.id && ref.episodeId == episodeId && ref.shotCode == shotCode && ref.dialogueIndex == null && task.request.productionContext?.chainId == null &&
            (task.state !in setOf(MediaTaskState.FAILED, MediaTaskState.CANCELED, MediaTaskState.COMPLETED) || task.state == MediaTaskState.FAILED && task.failure == MediaTaskFailure.POLL || task.state == MediaTaskState.COMPLETED && shot.takes.none { it.taskId == task.id })
    }
    val reserved = (queue as? MediaOutputReservationReader)?.outputReservations(tasks.map { it.id }.toSet()).orEmpty()
    var occupied = shot.takes.size + shot.chains.count { it.isActive() }
    for (task in tasks) occupied += if (task.state == MediaTaskState.COMPLETED) task.outputPaths.distinct().count { path -> shot.takes.none { it.taskId == task.id && it.path == path } }
        else reserved[task.id] ?: fallbackOutputs(task)
    return occupied
}
