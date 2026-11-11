package com.promenar.infinity.data.production

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionLimitsTest {
    private fun p() = Production("p", "项目", ProductionKind.SERIES, "productions/p", createdAt = 1, updatedAt = 1)
    private fun episode(scenes: List<Scene>) = Episode("S1E01", 1, "集", scenes = scenes)
    private fun scene(shots: List<Shot> = emptyList()) = Scene("SC01", 1, "场", shots = shots)
    private fun shot() = Shot("SC01-01", "镜头", durationSeconds = 1.0)
    private fun tree(e: Episode) = p().copy(seasons = listOf(Season("S1", 1, episodes = listOf(e))))
    private fun rejected(production: Production) = assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.encode(production, 1) }

    @Test fun `项目数量与文本上限`() {
        rejected(p().copy(seasons = List(21) { Season("S${it + 1}", it + 1) }))
        rejected(p().copy(seasons = listOf(Season("S1", 1, episodes = List(101) { Episode("S1E${(it + 1).toString().padStart(2, '0')}", it + 1, "集") }))))
        rejected(tree(episode(List(201) { Scene("SC${it + 1}", it + 1, "场") })))
        rejected(tree(episode(listOf(scene(List(101) { shot().copy(code = "SC01-${it + 1}") })))))
        rejected(tree(episode(listOf(scene(listOf(shot().copy(takes = List(31) { Take("t$it", "a.png", TakeSource("服务"), 1) })))))))
        rejected(tree(episode(listOf(scene(listOf(shot().copy(inputs = List(13) { ShotInput(InputRole.REFERENCE, "a.png") })))))))
        rejected(p().copy(assets = List(501) { Asset("a$it", AssetKind.PROP, "资产") }))
        rejected(p().copy(assets = listOf(Asset("a", AssetKind.PROP, "资产", versions = List(31) { AssetVersion(it + 1, "a.png", 1) }))))
        rejected(p().copy(bible = List(51) { DocumentRef("文档", "a.md") }))
        rejected(p().copy(title = "字".repeat(121)))
        rejected(tree(episode(listOf(scene(listOf(shot().copy(prompt = "字".repeat(7001))))))))
        rejected(p().copy(logline = "字".repeat(1001)))
        rejected(tree(episode(listOf(scene(listOf(shot().copy(userNote = "字".repeat(1001))))))))
    }

    @Test fun `标识路径与选定候选校验`() {
        rejected(p().copy(seasons = listOf(Season("season-1", 1))))
        rejected(tree(episode(listOf(scene(listOf(shot().copy(code = "shot-1")))))))
        rejected(tree(episode(listOf(scene(listOf(shot().copy(selectedTakeId = "不存在")))))))
        rejected(p().copy(assets = listOf(Asset("1bad", AssetKind.PROP, "资产"))))
        rejected(p().copy(bible = listOf(DocumentRef("文档", "a/../b"))))
    }
    @Test fun `快照 JSON 超过四兆时拒绝`() {
        val large = p().copy(assets = List(500) { i ->
            Asset("a$i", AssetKind.PROP, "资产", versions = List(30) { v -> AssetVersion(v + 1, "a.png", 1, "字".repeat(500)) })
        })
        rejected(large)
    }

}
