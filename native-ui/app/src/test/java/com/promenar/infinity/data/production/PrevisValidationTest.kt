package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PrevisValidationTest {
    private val sampleEpisode = Episode(
        id = "S1E01",
        number = 1,
        title = "晨间问候",
        scenes = listOf(
            Scene(
                code = "SC01",
                number = 1,
                heading = "外 老街 早",
                shots = listOf(
                    Shot(code = "SC01-01", title = "经过街口", durationSeconds = 4.5),
                    Shot(code = "SC01-02", title = "摊前招呼", durationSeconds = 5.0),
                ),
            ),
        ),
    )

    private fun validScene() = PrevisScene(
        version = 1,
        unit = "m",
        props = listOf(
            PrevisProp(id = "stall", label = "包子铺", shape = "box", pos = listOf(1.8, 0.0, -1.2), size = listOf(2.8, 0.95, 0.9)),
            PrevisProp(id = "lamp", label = "路灯", shape = "cylinder", pos = listOf(-2.5, 0.0, 1.0), size = listOf(0.16, 4.0, 0.16)),
        ),
        actors = listOf(
            PrevisActor(id = "luzhao", name = "陆昭", height = 1.76, accent = "#60a5fa"),
            PrevisActor(id = "vendor", name = "掌柜", height = 1.68, accent = "#f59e0b"),
        ),
        shots = listOf(
            PrevisShot(
                code = "SC01-01",
                duration = 4.5,
                label = "经过街口",
                camera = PrevisCamera(
                    from = listOf(-3.4, 1.7, 3.6),
                    to = listOf(-1.8, 1.55, 2.5),
                    lookAtFrom = listOf(0.2, 1.35, -0.4),
                    focalLengthMm = 35.0,
                    easing = "easeInOut",
                ),
                actors = listOf(
                    PrevisActorKeyframe(id = "luzhao", from = listOf(-2.2, 0.0, 0.2), to = listOf(-0.3, 0.0, -0.2), pose = "walk"),
                    PrevisActorKeyframe(id = "vendor", from = listOf(1.8, 0.0, -2.0), lookAtActor = "luzhao", pose = "stand"),
                ),
                axis = PrevisAxisSpec(actorA = "luzhao", actorB = "vendor", side = 1),
            ),
            PrevisShot(
                code = "SC01-02",
                duration = 5.0,
                label = "摊前招呼",
                camera = PrevisCamera(from = listOf(-1.4, 1.52, 1.8), lookAtFrom = listOf(1.6, 1.48, -1.7), focalLengthMm = 50.0),
                actors = listOf(
                    PrevisActorKeyframe(id = "luzhao", from = listOf(-0.2, 0.0, -0.2), lookAtActor = "vendor", pose = "wave"),
                    PrevisActorKeyframe(id = "vendor", from = listOf(1.8, 0.0, -2.0), lookAtActor = "luzhao", pose = "talk"),
                ),
                axis = PrevisAxisSpec(actorA = "luzhao", actorB = "vendor", side = 1),
                depthRange = listOf(0.8, 10.0),
            ),
        ),
    )

    @Test
    fun `合法 3D 预演场景可编码解码并通过集镜头校验`() {
        val original = validScene()
        val json = PrevisCodec.encode(original, sampleEpisode)
        val decoded = PrevisCodec.decode(json, sampleEpisode)
        assertThat(decoded).isEqualTo(original)
        assertThat(decoded.totalDurationSeconds()).isWithin(1e-6).of(9.5)
    }

    @Test
    fun `镜头编号不在所属集分镜中或几何参数非法时拒绝`() {
        val wrongShot = validScene().let { s ->
            s.copy(shots = listOf(s.shots.first().copy(code = "SC99-01")))
        }
        val err = assertThrows(IllegalArgumentException::class.java) {
            validatePrevisScene(wrongShot, sampleEpisode)
        }
        assertThat(err.message).contains("SC99-01")
        assertThat(err.message).contains("SC01-01")

        val badShape = validScene().let { s ->
            s.copy(props = listOf(s.props.first().copy(shape = "teapot")))
        }
        assertThrows(IllegalArgumentException::class.java) { validatePrevisScene(badShape, sampleEpisode) }

        val badPose = validScene().let { s ->
            val first = s.shots.first()
            s.copy(shots = listOf(first.copy(actors = listOf(first.actors.first().copy(pose = "fly")))))
        }
        assertThrows(IllegalArgumentException::class.java) { validatePrevisScene(badPose, sampleEpisode) }

        val unknownActor = validScene().let { s ->
            val first = s.shots.first()
            s.copy(shots = listOf(first.copy(actors = listOf(first.actors.first().copy(id = "ghost")))))
        }
        assertThrows(IllegalArgumentException::class.java) { validatePrevisScene(unknownActor, sampleEpisode) }

        val nanPos = validScene().let { s ->
            s.copy(props = listOf(s.props.first().copy(pos = listOf(0.0, Double.NaN, 0.0))))
        }
        assertThrows(IllegalArgumentException::class.java) { validatePrevisScene(nanPos, sampleEpisode) }

        val badDepth = validScene().let { s ->
            s.copy(shots = listOf(s.shots.first().copy(depthRange = listOf(10.0, 2.0))))
        }
        assertThrows(IllegalArgumentException::class.java) { validatePrevisScene(badDepth, sampleEpisode) }
    }

    @Test
    fun `画幅字符串映射为 16 像素对齐的硬件编码分辨率`() {
        assertThat(previsExportDimensions(null)).isEqualTo(1280 to 720)
        assertThat(previsExportDimensions("16:9")).isEqualTo(1280 to 720)
        assertThat(previsExportDimensions("9:16")).isEqualTo(720 to 1280)
        assertThat(previsExportDimensions("1:1")).isEqualTo(1280 to 1280)
        val cinema = previsExportDimensions("2.39:1")
        assertThat(cinema.first).isEqualTo(1280)
        assertThat(cinema.second % 16).isEqualTo(0)
        assertThat(cinema.second).isIn(320..1280)
    }

    @Test
    fun `项目清单支持保留可选 previsPath 并兼容旧版无该字段清单`() {
        val base = Production(
            id = "p1",
            title = "测试项目",
            kind = ProductionKind.FILM,
            rootPath = "productions/test",
            seasons = listOf(Season("S1", 1, episodes = listOf(sampleEpisode.copy(previsPath = "productions/test/previs/S1E01.previs.json")))),
            createdAt = 1,
            updatedAt = 1,
        )
        val encoded = ProductionManifestCodec.encode(base, 1)
        val decoded = ProductionManifestCodec.decode(encoded)
        assertThat(decoded.production.seasons.single().episodes.single().previsPath)
            .isEqualTo("productions/test/previs/S1E01.previs.json")

        val legacyJson = String(encoded, Charsets.UTF_8)
            .replace(""""previsPath": "productions/test/previs/S1E01.previs.json",""", "")
        val legacyDecoded = ProductionManifestCodec.decode(legacyJson.toByteArray(Charsets.UTF_8))
        assertThat(legacyDecoded.production.seasons.single().episodes.single().previsPath).isNull()

        val badPath = base.copy(
            seasons = listOf(Season("S1", 1, episodes = listOf(sampleEpisode.copy(previsPath = "../secret.json")))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProductionManifestCodec.encode(badPath, 1)
        }
    }
}
