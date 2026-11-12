package com.promenar.infinity.ui.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ProductionPanelActionsTest {
    private fun snapshot(storyboardLocked: Boolean = true): ProductionSnapshot {
        val shot = Shot("SC01-01", "原镜头", durationSeconds = 3.0)
        val scene = Scene("SC01", 1, "场", storyboardLocked = storyboardLocked, shots = listOf(shot))
        return ProductionSnapshot(Production("p", "项目", ProductionKind.FILM, "p",
            seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "集", scenes = listOf(scene))))),
            createdAt = 1, updatedAt = 1), 1, ManifestSync.InSync)
    }
    private fun location(snapshot: ProductionSnapshot) = snapshot.production.findShot("S1E01", "SC01-01")!!
    private class Source(initial: ProductionSnapshot) : ProductionPanelSource {
        val state = MutableStateFlow(initial)
        var failure: Exception? = null
        override fun observe(sessionId: String) = state
        override fun observeEverBound(sessionId: String) = flowOf(true)
        override suspend fun apply(sessionId: String, expectedRevision: Long, action: ProductionUserAction): ProductionSnapshot {
            check(sessionId == "session")
            failure?.let { throw it }
            return ProductionUserEngine.apply(state.value, expectedRevision, action, 2).also { state.value = it }
        }
        override suspend fun resolveManifest(sessionId: String, resolution: ManifestResolution): ProductionSnapshot = error("不处理清单")
    }

    @Test fun `锁定内容回调成功表示请求已保存而非内容已替换`() = runTest {
        val initial = snapshot()
        val source = Source(initial)
        val loc = location(initial)
        val error = requestShotContentChange(source, "session", 1, loc, loc.shot.copy(title = "新标题"), "修订冲突", "内容无效", "保存失败")
        assertThat(error).isNull()
        assertThat(location(source.state.value).shot).isEqualTo(loc.shot)
        assertThat(source.state.value.production.changeRequests.single().approved).isFalse()
        assertThat(source.state.value.revision).isEqualTo(2)
        assertThat(requestShotContentChange(source, "session", 1, loc, loc.shot.copy(title = "冲突"), "修订冲突", "内容无效", "保存失败")).isEqualTo("修订冲突")
        assertThat(source.state.value.production.changeRequests).hasSize(1)
        assertThat(requestShotContentChange(source, "session", 2, loc, loc.shot.copy(title = ""), "修订冲突", "内容无效", "保存失败")).isEqualTo("内容无效")
        assertThat(source.state.value.revision).isEqualTo(2)
        assertThat(requestShotContentChange(source, "session", 2, loc, loc.shot, "修订冲突", "内容无效", "保存失败")).isEqualTo("内容无效")
        assertThat(source.state.value.production.changeRequests).hasSize(1)
    }

    @Test fun `内容回调保留本地化失败并传播取消`() = runTest {
        val source = Source(snapshot())
        val loc = location(source.state.value)
        source.failure = IllegalStateException("内部错误")
        assertThat(requestShotContentChange(source, "session", 1, loc, loc.shot, "修订冲突", "内容无效", "保存失败")).isEqualTo("保存失败")
        source.failure = CancellationException("取消")
        try {
            requestShotContentChange(source, "session", 1, loc, loc.shot, "修订冲突", "内容无效", "保存失败")
            throw AssertionError("应传播取消")
        } catch (_: CancellationException) { }
    }

    @Test fun `新增成功后打开新镜头失败时不导航`() = runTest {
        val source = Source(snapshot(storyboardLocked = false))
        val initial = source.state.value
        val opened = mutableListOf<Pair<String, String>>()
        val handler: ProductionActionHandler = { revision, action ->
            source.apply("session", revision, action)
            null
        }
        val error = addShotAndOpen(initial, "S1E01", "SC01", "新镜头", handler) { episode, code ->
            assertThat(source.state.value.production.findShot(episode, code)).isNotNull()
            opened += episode to code
        }
        assertThat(error).isNull()
        assertThat(opened).containsExactly("S1E01" to "SC01-02")
        val failed = addShotAndOpen(initial, "S1E01", "SC01", "新镜头", { _, _ -> "修订冲突" }) { episode, code -> opened += episode to code }
        assertThat(failed).isEqualTo("修订冲突")
        assertThat(opened).hasSize(1)
        assertThat(source.state.value.production.findShot("S1E01", "SC01-03")).isNull()
    }
}
