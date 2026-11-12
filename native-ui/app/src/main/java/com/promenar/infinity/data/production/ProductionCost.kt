package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.MediaTask
import com.promenar.infinity.domain.mediagen.MediaTaskState
import java.net.URLDecoder
import java.net.URLEncoder

const val PRODUCTION_COST_ALERT_COUNT = 6

data class GenerationCount(val completed: Int = 0, val failed: Int = 0) { val total: Int get() = completed + failed }
data class ShotKey(val episodeId: String, val shotCode: String)
data class ProductionCostStats(
    val byStage: Map<ProductionStage, GenerationCount>,
    val byShot: Map<ShotKey, GenerationCount>,
    val byAsset: Map<String, GenerationCount>,
) {
    fun shot(episodeId: String, shotCode: String): GenerationCount = byShot[ShotKey(episodeId, shotCode)] ?: GenerationCount()
    fun asset(assetId: String): GenerationCount = byAsset[assetId] ?: GenerationCount()
    fun alerts(count: GenerationCount): Boolean = count.total >= PRODUCTION_COST_ALERT_COUNT
    val alertShots: List<Pair<ShotKey, GenerationCount>> get() = byShot.toList().filter { alerts(it.second) }
        .sortedWith(compareByDescending<Pair<ShotKey, GenerationCount>> { it.second.total }.thenBy { it.first.episodeId }.thenBy { it.first.shotCode })
    val alertAssets: List<Pair<String, GenerationCount>> get() = byAsset.toList().filter { alerts(it.second) }
        .sortedWith(compareByDescending<Pair<String, GenerationCount>> { it.second.total }.thenBy { it.first })
}

/** 资产任务与镜头任务共用项目引用前缀，标识段分别编码。 */
data class ProductionAssetSourceRef(val productionId: String, val assetId: String) {
    fun encode(): String = ProductionSourceRef.projectPrefix(productionId) + "asset:" + URLEncoder.encode(assetId, "UTF-8").replace("+", "%20")
    companion object {
        fun parse(value: String): ProductionAssetSourceRef? = runCatching {
            val match = Regex("^(production:[^/]+/)asset:([^/]+)$").matchEntire(value) ?: return null
            val id = ProductionSourceRef.projectIdFromPrefix(match.groupValues[1]) ?: return null
            ProductionAssetSourceRef(id, URLDecoder.decode(match.groupValues[2], "UTF-8")).takeIf { it.assetId.isNotBlank() && it.encode() == value }
        }.getOrNull()
    }
}

internal data class ProductionTaskLocation(val target: ReviewTarget?, val stage: ProductionStage, val shot: ShotKey? = null, val assetId: String? = null)
internal fun Production.ownsTask(task: MediaTask): Boolean = task.request.sourceRef.startsWith(ProductionSourceRef.projectPrefix(id))
internal fun Production.taskLocation(task: MediaTask): ProductionTaskLocation? {
    if (!ownsTask(task)) return null
    val assetRef = ProductionAssetSourceRef.parse(task.request.sourceRef)
    val asset = assets.firstOrNull { it.id == assetRef?.assetId || it.versions.any { version -> version.taskId == task.id } }
    if (asset != null) {
        val version = asset.versions.firstOrNull { it.taskId == task.id }?.number ?: asset.currentVersion
        return ProductionTaskLocation(version?.let { ReviewTarget.assetVersion(asset.id, it) },
            if (asset.kind == AssetKind.SOUND) ProductionStage.SOUND else ProductionStage.VISUAL_DEV, assetId = asset.id)
    }
    val previewRef = task.request.sourceRef.endsWith("/preview")
    val ref = ProductionSourceRef.parse(task.request.sourceRef.removeSuffix("/preview")) ?: return null
    val shot = reviewShot(ReviewTarget.shot(ref.episodeId, ref.shotCode)) ?: return null
    val preview = previewRef || shot.previewPath?.let { it in task.outputPaths } == true
    val stage = when { preview -> ProductionStage.ANIMATIC; ref.dialogueIndex != null -> ProductionStage.SOUND; else -> ProductionStage.GENERATION }
    return ProductionTaskLocation(ReviewTarget.shot(ref.episodeId, ref.shotCode), stage, ShotKey(ref.episodeId, ref.shotCode))
}

fun productionCostStats(production: Production, tasks: List<MediaTask>): ProductionCostStats {
    val stages = linkedMapOf<ProductionStage, GenerationCount>()
    val shots = linkedMapOf<ShotKey, GenerationCount>()
    val assets = linkedMapOf<String, GenerationCount>()
    fun <K> add(map: MutableMap<K, GenerationCount>, key: K, failed: Boolean) {
        val old = map[key] ?: GenerationCount()
        map[key] = if (failed) old.copy(failed = old.failed + 1) else old.copy(completed = old.completed + 1)
    }
    tasks.distinctBy { it.id }.forEach { task ->
        if (task.state !in setOf(MediaTaskState.COMPLETED, MediaTaskState.FAILED)) return@forEach
        val location = production.taskLocation(task) ?: return@forEach
        val failed = task.state == MediaTaskState.FAILED
        add(stages, location.stage, failed)
        location.shot?.let { add(shots, it, failed) }
        location.assetId?.let { add(assets, it, failed) }
    }
    production.seasons.flatMap { it.episodes }.forEach { episode -> episode.scenes.flatMap { it.shots }.forEach { shot ->
        shot.takes.filter { it.taskId == null }.forEach {
            add(stages, ProductionStage.GENERATION, false)
            add(shots, ShotKey(episode.id, shot.code), false)
        }
    } }
    production.assets.forEach { asset -> asset.versions.filter { it.taskId == null }.forEach {
        add(stages, if (asset.kind == AssetKind.SOUND) ProductionStage.SOUND else ProductionStage.VISUAL_DEV, false)
        add(assets, asset.id, false)
    } }
    return ProductionCostStats(stages, shots, assets)
}
