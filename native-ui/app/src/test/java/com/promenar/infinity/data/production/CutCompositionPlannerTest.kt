package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CutCompositionPlannerTest {
    private val firstPath = "productions/p/takes/first.mp4"
    private val secondPath = "productions/p/takes/second.mp4"
    private val planSnapshot = TakePlanSnapshot(mode = ShotMode.TEXT, inputs = emptyList(), prompt = "")
    private fun production(shots: List<Shot>, assets: List<Asset> = emptyList(), aspectRatio: String? = null) =
        Production("p", "项目", ProductionKind.FILM, "productions/p", aspectRatio = aspectRatio, createdAt = 1, updatedAt = 1,
            seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "集", scenes = listOf(Scene("SC01", 1, "场", shots = shots)))))),
            assets = assets)

    private fun shot(code: String = "SC01-01", path: String = firstPath, duration: Double = 5.0) =
        Shot(code, "镜头 $code", durationSeconds = duration, takes = listOf(Take("take-$code", path, TakeSource("服务"), 1)))

    private fun clip(shot: Shot, seconds: Double = shot.durationSeconds) =
        CutClip("S1E01", shot.code, shot.takes.single().id, shot.takes.single().path, seconds)

    private fun cut(clips: List<CutClip>, burn: Boolean = false) = CutVersion("cut", "S1E01", 1, 1, clips, burn)

    @Test fun `视频段遵守冻结顺序缺口使用计划时长标题卡`() {
        val first = shot()
        val second = shot("SC01-02", secondPath, 3.0)
        val gap = Shot("SC01-03", "缺少候选", durationSeconds = 2.5)
        val production = production(listOf(first, second, gap))
        val cut = cut(listOf(clip(second), CutClip("S1E01", gap.code, seconds = gap.durationSeconds), clip(first, 4.0)))
        val result = CutCompositionPlanner.plan(production, cut)
        assertThat(result.segments.map { it.shotCode }).containsExactly(second.code, gap.code, first.code).inOrder()
        assertThat(result.segments.map { it.startSeconds }).containsExactly(0.0, 3.0, 5.5).inOrder()
        assertThat(result.segments.map { it.durationSeconds }).containsExactly(3.0, 2.5, 4.0).inOrder()
        assertThat(result.segments[1].path).isNull()
        assertThat(result.segments[1].title).isEqualTo("缺少候选")
        assertThat(result.durationSeconds).isEqualTo(9.5)
        assertThat(result.videoPath).isEqualTo("productions/p/cuts/S1E01-v1.mp4")
        assertThat(result.subtitlePath).isEqualTo("productions/p/cuts/S1E01-v1.srt")
        assertThat(result.srt).isEmpty()
    }

    @Test fun `输出尺寸按项目画幅和最大素材高度生成偶数并限制一千零八十`() {
        val first = shot()
        val second = shot("SC01-02", secondPath)
        val p = production(listOf(first, second), aspectRatio = "4:3")
        val cut = cut(listOf(clip(first), clip(second)))
        val small = CutCompositionPlanner.plan(p, cut, mapOf(firstPath to CutMediaMetadata(height = 719), secondPath to CutMediaMetadata(height = 480)))
        assertThat(small.height).isEqualTo(718)
        assertThat(small.width).isEqualTo(958)
        assertThat(small.width % 2).isEqualTo(0)
        val large = CutCompositionPlanner.plan(p, cut, mapOf(firstPath to CutMediaMetadata(height = 2160)))
        assertThat(large.height).isEqualTo(1080)
        assertThat(large.width).isEqualTo(1440)
        val portrait = CutCompositionPlanner.plan(p.copy(aspectRatio = "9:16"), cut)
        assertThat(portrait.height).isEqualTo(1080)
        assertThat(portrait.width).isEqualTo(608)
    }

    @Test fun `无素材无效画幅和无效探测尺寸使用缺省偶数尺寸`() {
        val gap = Shot("SC01-01", "标题卡", durationSeconds = 1.0)
        val cut = cut(listOf(CutClip("S1E01", gap.code, seconds = 1.0)))
        listOf(null, "", "宽银幕", "0:9", "16:-9", "NaN:1", "1e300:1").forEach { ratio ->
            val result = CutCompositionPlanner.plan(production(listOf(gap), aspectRatio = ratio), cut)
            assertThat(result.width).isEqualTo(1920)
            assertThat(result.height).isEqualTo(1080)
        }
        val shot = shot()
        val result = CutCompositionPlanner.plan(production(listOf(shot)), cut(listOf(clip(shot))), mapOf(firstPath to CutMediaMetadata(height = -1)))
        assertThat(result.width).isEqualTo(1920)
        assertThat(result.height).isEqualTo(1080)
    }

    @Test fun `横屏与电影画幅保持偶数宽高`() {
        val first = shot()
        val version = cut(listOf(clip(first)))
        val p = production(listOf(first))
        val landscape = CutCompositionPlanner.plan(p.copy(aspectRatio = "16:9"), version)
        assertThat(landscape.width).isEqualTo(1920)
        val cinema = CutCompositionPlanner.plan(p.copy(aspectRatio = "2.39:1"), version)
        assertThat(cinema.height).isEqualTo(1080)
        assertThat(cinema.width).isEqualTo(2582)
    }

    @Test fun `最新配音按对白顺序从镜头起点接续截短并降低对应原声`() {
        val old = "productions/p/audio/old.wav"
        val firstDub = "productions/p/audio/first.wav"
        val secondDub = "productions/p/audio/second.wav"
        val first = shot().copy(dialogue = listOf(
            ShotDialogue("char", "第一句", audioSource = DialogueAudioSource.DUBBED),
            ShotDialogue("char", "第二句", audioSource = DialogueAudioSource.NATIVE),
            ShotDialogue("char", "第三句", audioSource = DialogueAudioSource.DUBBED),
        ), audio = listOf(
            ShotAudio("new", firstDub, 0, "task", planSnapshot, 3),
            ShotAudio("third", secondDub, 2, "task", planSnapshot, 2),
            ShotAudio("old", old, 0, "task", planSnapshot, 1),
        ))
        val second = shot("SC01-02", secondPath, 2.0)
        val metadata = mapOf(firstPath to CutMediaMetadata(durationSeconds = 8.0, hasAudio = true),
            firstDub to CutMediaMetadata(durationSeconds = 2.0), secondDub to CutMediaMetadata(durationSeconds = 4.0),
            secondPath to CutMediaMetadata(hasAudio = true))
        val result = CutCompositionPlanner.plan(production(listOf(first, second)), cut(listOf(clip(first), clip(second))), metadata)
        val dubbed = result.audio.filter { it.kind == CutAudioKind.DIALOGUE }
        assertThat(dubbed.map { it.path }).containsExactly(firstDub, secondDub).inOrder()
        assertThat(dubbed.map { it.startSeconds }).containsExactly(0.0, 2.0).inOrder()
        assertThat(dubbed.map { it.durationSeconds }).containsExactly(2.0, 3.0).inOrder()
        assertThat(dubbed.all { it.sourceStartSeconds == 0.0 }).isTrue()
        val original = result.audio.filter { it.kind == CutAudioKind.ORIGINAL }
        assertThat(original.map { it.volumeDb }).containsExactly(-10.0, 0.0).inOrder()
        assertThat(original.map { it.durationSeconds }).containsExactly(5.0, 2.0).inOrder()
    }

    @Test fun `没有原声音轨时跳过原声有路径的配音缺少实测时长时明确失败`() {
        val first = shot()
        val muted = CutCompositionPlanner.plan(production(listOf(first)), cut(listOf(clip(first))), mapOf(firstPath to CutMediaMetadata(hasAudio = false)))
        assertThat(muted.audio).isEmpty()
        val dub = first.copy(dialogue = listOf(ShotDialogue("char", "台词", audioSource = DialogueAudioSource.DUBBED)),
            audio = listOf(ShotAudio("audio", "productions/p/audio/a.wav", 0, "task", planSnapshot, 1)))
        assertThrows(IllegalArgumentException::class.java) { CutCompositionPlanner.plan(production(listOf(dub)), cut(listOf(clip(dub)))) }
    }

    @Test fun `配乐选择首个锁定声音当前版本降十四分贝截总长并淡出`() {
        val shot = shot(duration = 2.0)
        fun asset(id: String, status: AssetStatus) = Asset(id, AssetKind.SOUND, id, status = status, currentVersion = 2,
            versions = listOf(AssetVersion(1, "productions/p/music/$id-old.wav", 1), AssetVersion(2, "productions/p/music/$id.wav", 2)))
        val p = production(listOf(shot), listOf(asset("draft", AssetStatus.DRAFT), asset("first", AssetStatus.LOCKED), asset("second", AssetStatus.LOCKED)))
        val path = "productions/p/music/first.wav"
        val result = CutCompositionPlanner.plan(p, cut(listOf(clip(shot))), mapOf(path to CutMediaMetadata(durationSeconds = 30.0)))
        val music = result.audio.single { it.kind == CutAudioKind.MUSIC }
        assertThat(music.path).isEqualTo(path)
        assertThat(music.startSeconds).isEqualTo(0.0)
        assertThat(music.durationSeconds).isEqualTo(2.0)
        assertThat(music.volumeDb).isEqualTo(-14.0)
        assertThat(music.fadeOutSeconds).isEqualTo(1.0)
        val short = CutCompositionPlanner.plan(p, cut(listOf(clip(shot))), mapOf(path to CutMediaMetadata(durationSeconds = 0.5)))
        assertThat(short.audio.single { it.kind == CutAudioKind.MUSIC }.fadeOutSeconds).isEqualTo(0.5)
    }

    @Test fun `字幕平分镜头时长跨镜头接续并转义标签与空行`() {
        val first = shot(duration = 3.0).copy(dialogue = listOf(
            ShotDialogue("char", "<b>你好</b> & 欢迎\r\n\r\n第二行"),
            ShotDialogue("char", "再见\u0000"),
        ))
        val second = shot("SC01-02", secondPath, 1.0).copy(dialogue = listOf(ShotDialogue("char", "尾句")))
        val result = CutCompositionPlanner.plan(production(listOf(first, second)), cut(listOf(clip(first), clip(second)), burn = true))
        assertThat(result.burnSubtitles).isTrue()
        assertThat(result.subtitles.map { it.startMillis }).containsExactly(0L, 1500L, 3000L).inOrder()
        assertThat(result.subtitles.map { it.endMillis }).containsExactly(1500L, 3000L, 4000L).inOrder()
        assertThat(result.srt).isEqualTo(
            "1\n00:00:00,000 --> 00:00:01,500\n&lt;b&gt;你好&lt;/b&gt; &amp; 欢迎\n第二行\n\n" +
                "2\n00:00:01,500 --> 00:00:03,000\n再见\n\n" +
                "3\n00:00:03,000 --> 00:00:04,000\n尾句\n\n",
        )
        assertThat(result.subtitles.all { it.endMillis > it.startMillis && !it.text.contains("\n\n") }).isTrue()
    }

    @Test fun `毫秒分配包含完整镜头时长且拒绝不可能的字幕时间`() {
        val shot = shot(duration = 0.01).copy(dialogue = (1..3).map { ShotDialogue("char", "$it") })
        val result = CutCompositionPlanner.plan(production(listOf(shot)), cut(listOf(clip(shot))))
        assertThat(result.subtitles.map { it.endMillis }).containsExactly(3L, 6L, 10L).inOrder()
        val tooShort = shot.copy(durationSeconds = 0.001)
        assertThrows(IllegalArgumentException::class.java) {
            CutCompositionPlanner.plan(production(listOf(tooShort)), cut(listOf(clip(tooShort))))
        }
    }
}
