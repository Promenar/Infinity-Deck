package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.InputRole
import com.promenar.infinity.data.production.ManifestSync
import com.promenar.infinity.data.production.PrevisFileAccess
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionKind
import com.promenar.infinity.data.production.ProductionPrevisStore
import com.promenar.infinity.data.production.ProductionSnapshot
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.ProductionUserEngine
import com.promenar.infinity.data.production.PreviewKind
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.Season
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.ShotInput
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

class ProductionPrevisSkillTest {
    private class FakeFileAccess : PrevisFileAccess {
        val files = linkedMapOf<String, ByteArray>()

        override suspend fun normalizeRelativePath(sessionId: String, rawPath: String, projectRoot: String?): String =
            rawPath.trimStart('/')

        override suspend fun read(sessionId: String, path: String): ByteArray? = files[path]

        override suspend fun replaceBytes(sessionId: String, path: String, bytes: ByteArray) {
            files[path] = bytes
        }

        override suspend fun replaceFile(sessionId: String, path: String, file: File) {
            files[path] = file.readBytes()
        }
    }

    private fun initialSnapshot(): ProductionSnapshot = ProductionSnapshot(
        production = Production(
            id = "p1",
            title = "晨间测试",
            kind = ProductionKind.FILM,
            rootPath = "productions/demo",
            seasons = listOf(
                Season(
                    id = "S1",
                    number = 1,
                    episodes = listOf(
                        Episode(
                            id = "S1E01",
                            number = 1,
                            title = "第1集",
                            scenes = listOf(
                                Scene(
                                    code = "SC01",
                                    number = 1,
                                    heading = "外 街口",
                                    shots = listOf(
                                        Shot(code = "SC01-01", title = "经过", durationSeconds = 4.0),
                                        Shot(code = "SC01-02", title = "招呼", durationSeconds = 5.0),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            createdAt = 1,
            updatedAt = 1,
        ),
        revision = 1,
        manifest = ManifestSync.InSync,
    )

    private val ctx = object : SkillExecutionContext {
        override val sessionId = "s1"
        override val agentId = "a1"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
        override val assistantMessageId = "m1"
    }

    @Test
    fun `完整生命周期 get set patch_shot 与 clear`() = runTest {
        var snap = initialSnapshot()
        val fileAccess = FakeFileAccess()
        val store = ProductionPrevisStore(fileAccess)
        val skill = ProductionPrevisSkill(
            load = { snap },
            setEpisodePrevis = { _, epId, path ->
                snap = snap.copy(
                    revision = snap.revision + 1,
                    production = snap.production.copy(
                        seasons = snap.production.seasons.map { s ->
                            s.copy(episodes = s.episodes.map { ep -> if (ep.id == epId) ep.copy(previsPath = path) else ep })
                        },
                    ),
                )
                snap
            },
            store = store,
        )
        assertThat(skill.risk).isEqualTo(ToolRisk.SESSION_STATE)

        // 1. 未设置时 get 返回默认路径
        val emptyGet = Json.parseToJsonElement(
            skill.execute(Json.parseToJsonElement("""{"action":"get","episode_id":"S1E01"}""").jsonObject, ctx).content,
        ).jsonObject
        assertThat(emptyGet["has_previs"]?.jsonPrimitive?.content).isEqualTo("false")
        assertThat(emptyGet["default_path"]?.jsonPrimitive?.content).isEqualTo("productions/demo/previs/S1E01.previs.json")

        // 2. set 直接传 scene 写入工作区并更新集绑定
        val sceneJson = """
            {
              "version": 1,
              "unit": "m",
              "actors": [{"id": "luzhao", "name": "陆昭"}],
              "shots": [
                {
                  "code": "SC01-01",
                  "duration": 4.0,
                  "camera": {"from": [-3, 1.6, 3], "lookAtFrom": [0, 1.4, 0], "focalLengthMm": 35},
                  "actors": [{"id": "luzhao", "from": [-1, 0, 0], "pose": "walk"}]
                }
              ]
            }
        """.trimIndent()
        val setRes = skill.execute(
            Json.parseToJsonElement("""{"action":"set","episode_id":"S1E01","scene":$sceneJson}""").jsonObject,
            ctx,
        )
        assertThat(setRes.status).isNotEqualTo("error")
        assertThat(fileAccess.files).containsKey("productions/demo/previs/S1E01.previs.json")
        assertThat(snap.revision).isEqualTo(2L)

        // 3. patch_shot 追加第二镜且拒绝越界路径
        val patchShot = """
            {
              "action": "patch_shot",
              "episode_id": "S1E01",
              "shot_code": "SC01-02",
              "shot": {
                "code": "SC01-02",
                "duration": 5.0,
                "camera": {"from": [0, 1.5, 2], "lookAtFrom": [0, 1.4, -1], "focalLengthMm": 50},
                "actors": [{"id": "luzhao", "from": [0, 0, -1], "pose": "wave"}]
              }
            }
        """.trimIndent()
        val patchRes = skill.execute(Json.parseToJsonElement(patchShot).jsonObject, ctx)
        assertThat(patchRes.status).isNotEqualTo("error")
        assertThat(Json.parseToJsonElement(patchRes.content).jsonObject["shot_count"]?.jsonPrimitive?.content).isEqualTo("2")

        // 越界目录写入须拦截
        val escapeRes = skill.execute(
            Json.parseToJsonElement("""{"action":"set","episode_id":"S1E01","path":"other/evil.previs.json","scene":$sceneJson}""").jsonObject,
            ctx,
        )
        assertThat(escapeRes.status).isEqualTo("error")

        // 4. clear 解绑但保留文件
        val clearRes = skill.execute(Json.parseToJsonElement("""{"action":"clear","episode_id":"S1E01"}""").jsonObject, ctx)
        assertThat(clearRes.status).isNotEqualTo("error")
        assertThat(snap.production.seasons.single().episodes.single().previsPath).isNull()
        assertThat(fileAccess.files).containsKey("productions/demo/previs/S1E01.previs.json")
    }

    @Test
    fun `AttachPrevisFrame 可设为预演图与参考输入并在超限时拒绝`() {
        val snap0 = initialSnapshot()
        val snap1 = ProductionUserEngine.apply(
            snap0,
            snap0.revision,
            ProductionUserAction.AttachPrevisFrame(
                episodeId = "S1E01",
                shotCode = "SC01-01",
                path = "productions/demo/previs/frames/S1E01_SC01-01-beauty.png",
                inputRole = null,
            ),
            now = 100L,
        )
        val shot1 = snap1.production.seasons.single().episodes.single().scenes.single().shots.first()
        assertThat(shot1.preview).isEqualTo(PreviewKind.KEYFRAME)
        assertThat(shot1.previewPath).isEqualTo("productions/demo/previs/frames/S1E01_SC01-01-beauty.png")

        val snap2 = ProductionUserEngine.apply(
            snap1,
            snap1.revision,
            ProductionUserAction.AttachPrevisFrame(
                episodeId = "S1E01",
                shotCode = "SC01-01",
                path = "productions/demo/previs/frames/S1E01_SC01-01-depth.png",
                inputRole = InputRole.REFERENCE,
                label = "3D 深度参考",
            ),
            now = 200L,
        )
        val shot2 = snap2.production.seasons.single().episodes.single().scenes.single().shots.first()
        assertThat(shot2.inputs).containsExactly(
            ShotInput(InputRole.REFERENCE, "productions/demo/previs/frames/S1E01_SC01-01-depth.png", label = "3D 深度参考"),
        )

        // 已满 12 个输入时再加入不同路径应拒绝
        val fullShot = shot2.copy(inputs = List(12) { idx -> ShotInput(InputRole.REFERENCE, "productions/demo/inputs/i$idx.png") })
        val fullSnap = snap2.copy(
            production = snap2.production.copy(
                seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "第1集", scenes = listOf(Scene("SC01", 1, "外", shots = listOf(fullShot))))))),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProductionUserEngine.apply(
                fullSnap,
                fullSnap.revision,
                ProductionUserAction.AttachPrevisFrame("S1E01", "SC01-01", "productions/demo/previs/frames/new.png", InputRole.REFERENCE),
                now = 300L,
            )
        }
    }
}
