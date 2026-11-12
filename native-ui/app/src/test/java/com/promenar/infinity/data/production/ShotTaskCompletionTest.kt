package com.promenar.infinity.data.production

import com.promenar.infinity.data.mediagen.queue.MediaTaskCodec
import com.promenar.infinity.data.mediagen.queue.PersistentMediaTask
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.mediagen.InputRole as MediaRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ShotTaskCompletionTest {
    private val production = planningProduction()
    private fun task(index: Int? = null): MediaTask {
        val channel = planningChannel()
        val request = MediaGenerationRequest(channel.id, if (index == null) GenerationMode.REFERENCE else GenerationMode.SPEECH, "图1：实际提交的风衣参考", listOf(if (index == null) MediaInput("productions/fog/derived/actual.png", InputKind.IMAGE, MediaRole.REFERENCE) else MediaInput("ref/voice.wav", InputKind.AUDIO, MediaRole.REFERENCE_VOICE)),
            ProductionSourceRef(production.id, "S1E01", "SC01-01", index).encode(), sessionId = "s", outputDir = "productions/fog/S1E01/${if (index == null) "takes" else "audio"}")
        return MediaTask("task", request, null, MediaTaskState.COMPLETED, outputPaths = listOf("${request.outputDir}/output.${if (index == null) "mp4" else "wav"}"))
    }
    private fun Production.shot() = seasons.single().episodes.single().scenes.single().shots.single()
    @Test fun `完成登记使用任务快照并保持用户已定候选`() {
        val original = production.mapShot("S1E01", "SC01-01") { it.copy(prompt = "提交后被改写的规划", promptUserEdited = true, status = ShotStatus.LOCKED) }
        val completed = task()
        val registered = registerShotTask(original, completed, 3)!!
        val take = registered.shot().takes.single()
        assertEquals("task", take.taskId); assertEquals(completed.request.prompt, take.planSnapshot?.prompt)
        assertEquals(completed.request.inputs, take.planSnapshot?.mediaInputs)
        assertEquals(completed.request.inputs.single().location, take.planSnapshot?.inputs?.single()?.path)
        assertEquals(ShotStatus.LOCKED, registered.shot().status)
        assertEquals(original.shot().changedBy, registered.shot().changedBy)
        assertEquals(registered, registerShotTask(registered, completed, 4))
    }
    @Test fun `重启反序列化任务后首次扫描幂等登记`() = runTest {
        val stored = PersistentMediaTask(task(), planningChannel(), "backend", "provider", "model", 1)
        val restored = MediaTaskCodec.decode(MediaTaskCodec.encode(stored)).task
        val queue = PlanningQueue().also { it.tasks.value = listOf(restored) }
        val store = PlanningStore()
        ShotTaskCompletionRegistrar(queue, store).reconcile()
        ShotTaskCompletionRegistrar(queue, store).reconcile()
        assertEquals(1, store.production.shot().takes.size)
        assertEquals(2L, store.revision)
    }
    @Test fun `失败任务与其他项目不登记`() {
        assertNull(registerShotTask(production, task().copy(state = MediaTaskState.FAILED), 2))
        assertNull(registerShotTask(production.copy(id = "other"), task(), 2))
        assertNull(registerShotTask(production, task().copy(outputPaths = listOf("elsewhere/output.mp4")), 2))
    }
    @Test fun `配音作为附属音频登记且清单往返兼容默认字段`() {
        val result = registerShotTask(production, task(0), 2)!!
        assertTrue(result.shot().takes.isEmpty()); assertEquals(0, result.shot().audio.single().dialogueIndex)
        assertEquals(GenerationMode.SPEECH, result.shot().audio.single().planSnapshot.generationMode)
        val encoded = ProductionManifestCodec.encode(result, 2)
        assertEquals(result, ProductionManifestCodec.decode(encoded).production)
        val json = Json.parseToJsonElement(encoded.toString(Charsets.UTF_8)).jsonObject
        val original = ProductionManifestCodec.encode(production, 1)
        assertTrue(ProductionManifestCodec.decode(original).production.shot().audio.isEmpty())
        assertTrue(json.toString().contains("audio"))
    }
    @Test fun `来源引用严格解析且各段可逆`() {
        val ref = ProductionSourceRef("project/含 空格", "S1E01", "SC01-01", 3)
        assertEquals(ref, ProductionSourceRef.parse(ref.encode()))
        listOf("production:p/episode:e/shot:s/unknown:1", "production:p/episode:e/shot:s/dialogue:-1", "production:%ZZ/episode:e/shot:s", "production:p/episode:e/shot:s/extra").forEach { assertNull(ProductionSourceRef.parse(it)) }
    }
    @Test fun `场解锁与多产物容量不足保留待登记任务`() = runTest {
        val unlocked = production.copy(seasons = production.seasons.map { it.copy(episodes = it.episodes.map { ep -> ep.copy(scenes = ep.scenes.map { sc -> sc.copy(storyboardLocked = false) }) }) })
        assertNull(registerShotTask(unlocked, task(), 2))
        val crowded = production.mapShot("S1E01", "SC01-01") { shot -> shot.copy(takes = List(29) { Take("take-$it", "takes/$it.mp4", TakeSource("service"), 1) }) }
        val multiple = task().let { it.copy(outputPaths = it.outputPaths + "${it.request.outputDir}/second.mp4") }
        val queue = PlanningQueue().also { it.tasks.value = listOf(multiple) }
        val store = PlanningStore(crowded)
        val registrar = ShotTaskCompletionRegistrar(queue, store)
        assertEquals(setOf("task"), registrar.reconcile())
        assertEquals(setOf("task"), registrar.pending.value)
        assertEquals(29, store.production.shot().takes.size)
    }

    @Test fun `事务内项目身份不符必须拒绝登记`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProductionUserEngine.apply(ProductionSnapshot(production.copy(id = "other"), 1, ManifestSync.InSync), 1, ProductionUserAction.RegisterGeneratedTask(task()), 2)
        }
    }

}
