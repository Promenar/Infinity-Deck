package com.promenar.infinity.data.production

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 只审计候选与资产目录；目录访问失败时保留上一次结果。 */
class ProductionFileAudit(
    private val store: ProductionWorkStore,
    private val listMedia: suspend (String, Production) -> List<String>,
) {
    private val mutex = Mutex()
    suspend fun scan(sessionId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val initial = store.load(sessionId) ?: return@withLock
            val paths = listMedia(sessionId, initial.production).distinct().sorted()
            // 与回报登记并发时重新读取引用，且以仓库修订号阻止过期审计覆盖新快照。
            repeat(3) {
                val current = store.load(sessionId) ?: return@withLock
                if (current.production.id != initial.production.id || current.production.rootPath != initial.production.rootPath) return@withLock
                val result = findUnregisteredProductionFiles(current.production, paths)
                if (result == current.production.unregisteredFiles) return@withLock
                try { store.apply(sessionId, current, ProductionUserAction.SetUnregisteredFiles(result)); return@withLock }
                catch (_: ProductionRevisionConflictException) { }
            }
        }
    }
}

fun findUnregisteredProductionFiles(production: Production, paths: List<String>): List<String> {
    val referenced = buildSet {
        fun plan(value: TakePlanSnapshot?) { value?.inputs?.forEach { add(it.path) }; value?.mediaInputs?.forEach { add(it.location) } }
        production.seasons.flatMap { it.episodes }.forEach { ep -> ep.scenes.flatMap { it.shots }.forEach { shot ->
            shot.previewPath?.let(::add); shot.inputs.forEach { add(it.path) }; shot.audio.forEach { add(it.path); plan(it.planSnapshot) }
            shot.takes.forEach { take -> add(take.path); plan(take.planSnapshot); take.segments.forEach { add(it.path) } }
            shot.chains.forEach { chain -> plan(chain.planSnapshot); chain.outputPath?.let(::add); chain.segments.forEach { segment -> segment.path?.let(::add); segment.firstFramePath?.let(::add); segment.taskOutputPath?.let(::add) } }
        } }
        production.assets.forEach { asset -> asset.versions.forEach { add(it.path) }; asset.looks.flatMap { it.slots }.forEach { it.path?.let(::add) }; asset.referenceVoice?.path?.let(::add) }
        production.runs.forEach { run -> run.resultPath?.let(::add); run.segments.forEach { add(it.path) } }
        production.cuts.forEach { cut -> cut.clips.forEach { it.path?.let(::add) }; cut.export?.let { add(it.videoPath); it.subtitlePath?.let(::add) } }
    }
    return paths.distinct().filter { path -> path.startsWith(production.rootPath + "/") && mediaKindFromPath(path) != null && path !in referenced }.sorted()
}
