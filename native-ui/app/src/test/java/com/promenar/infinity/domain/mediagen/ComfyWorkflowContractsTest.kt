package com.promenar.infinity.domain.mediagen

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComfyWorkflowContractsTest {
    @Test fun `supportsUnlimitedFieldsAndAllTwelveValueTypes`() {
        val fields = listOf(
            WorkflowFieldSpec("prompt", "提示词", mutability = FieldMutability.CONTENT, valueType = FieldValueType.TEXT, required = true, semanticRole = FieldSemanticRole.PROMPT),
            WorkflowFieldSpec("prefix", "前缀", mutability = FieldMutability.CONTENT, valueType = FieldValueType.STRING, defaultValue = JsonPrimitive("Qwen21")),
            WorkflowFieldSpec("steps", "步数", mutability = FieldMutability.FIXED, valueType = FieldValueType.INT, defaultValue = JsonPrimitive(40), min = 1.0, max = 100.0),
            WorkflowFieldSpec("cfg", "CFG", mutability = FieldMutability.FIXED, valueType = FieldValueType.FLOAT, defaultValue = JsonPrimitive(1.0), min = 0.0, max = 20.0),
            WorkflowFieldSpec("free_after", "结束后释放显存", mutability = FieldMutability.FIXED, valueType = FieldValueType.BOOL, defaultValue = JsonPrimitive(true)),
            WorkflowFieldSpec("encoder", "编码器", mutability = FieldMutability.FIXED, valueType = FieldValueType.ENUM, defaultValue = JsonPrimitive("official"), options = listOf("official", "heretic")),
            WorkflowFieldSpec("extra_meta", "扩展元数据", mutability = FieldMutability.CONTENT, valueType = FieldValueType.JSON),
            WorkflowFieldSpec("first_frame", "首帧", mutability = FieldMutability.CONTENT, valueType = FieldValueType.IMAGE_FILE, semanticRole = FieldSemanticRole.FIRST_FRAME),
            WorkflowFieldSpec("motion_video", "动作视频", mutability = FieldMutability.CONTENT, valueType = FieldValueType.VIDEO_FILE, semanticRole = FieldSemanticRole.INPUT_VIDEO),
            WorkflowFieldSpec("ref_audio", "参考音频", mutability = FieldMutability.CONTENT, valueType = FieldValueType.AUDIO_FILE, semanticRole = FieldSemanticRole.AUDIO_REF),
            WorkflowFieldSpec("ref_images", "多参考图", mutability = FieldMutability.CONTENT, valueType = FieldValueType.FILE_LIST, semanticRole = FieldSemanticRole.REFERENCE_IMAGES),
            WorkflowFieldSpec("guides", "时间点锚定图", mutability = FieldMutability.CONTENT, valueType = FieldValueType.TIMED_FILES, semanticRole = FieldSemanticRole.GUIDES),
        ) + (1..12).map { i ->
            WorkflowFieldSpec("custom_scalar_$i", "自定义参数 $i", mutability = FieldMutability.FIXED, valueType = FieldValueType.INT, defaultValue = JsonPrimitive(i))
        }

        val workflow = ComfyWorkflow(
            workflowId = "qwen21_universal",
            hostId = "main",
            name = "Qwen Image 2.1 万能工作流",
            mediaCategory = ComfyMediaCategory.IMAGE,
            submitterScriptId = "submit_qwen21",
            fields = fields,
        )
        assertEquals(24, workflow.fields.size)
        assertEquals(8, workflow.contentFields().size)
        assertEquals(16, workflow.fixedFields().size)

        // 同数值不同表现形式（1 vs 1.0）不应被误判为偏离固定基线。
        assertTrue(workflow.detectFixedOverrides(mapOf("cfg" to JsonPrimitive(1))).isEmpty())
        val diffs = workflow.detectFixedOverrides(mapOf("steps" to JsonPrimitive(28), "encoder" to JsonPrimitive("heretic")))
        assertEquals(listOf("steps", "encoder"), diffs.map { it.key })
        assertEquals("40", diffs[0].oldDisplayValue)
        assertEquals("28", diffs[0].newDisplayValue)

        // TIMED_FILES 校验：缺 frame_idx/time_seconds 应失败。
        val guidesField = workflow.field("guides")!!
        guidesField.validateValue(Json.parseToJsonElement("""[{"frame_idx": 12, "path": "media/g1.png"}]"""))
        assertThrows(IllegalArgumentException::class.java) {
            guidesField.validateValue(Json.parseToJsonElement("""[{"path": "media/g1.png"}]"""))
        }
    }

    @Test fun `statefulChainSupportsMidSegmentRetryIsolatedAndInPlaceInvalidation`() {
        val seg1 = StatefulChainNode(
            nodeId = "seg_01_v1", parentNodeId = null, stage = "first", segmentIndex = 1, attemptLabel = "v1",
            branchId = "main", branchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
            outputMediaPath = "media/seg1.mp4",
            continuationStateJson = buildJsonObject {
                put("latent_dir", "shot_04")
                put("width", 768)
                put("height", 1344)
                put("family", "fl")
            },
            serverLatentSlotKey = "shot_04::clip_1",
            createdAt = 100L,
        )
        val r1 = StatefulChainDag.appendNode(null, "s1", "h3_motion", "main", "chain_a", null, seg1).record

        val seg2 = StatefulChainNode(
            nodeId = "seg_02_v1", parentNodeId = "seg_01_v1", stage = "next", segmentIndex = 2, attemptLabel = "v1",
            branchId = "main", branchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
            outputMediaPath = "media/seg2.mp4",
            continuationStateJson = buildJsonObject { put("latent_dir", "shot_04") },
            serverLatentSlotKey = "shot_04::clip_2",
            createdAt = 200L,
        )
        val r2 = StatefulChainDag.appendNode(r1, "s1", "h3_motion", "main", "chain_a", null, seg2).record

        val seg3v1 = StatefulChainNode(
            nodeId = "seg_03_v1", parentNodeId = "seg_02_v1", stage = "next", segmentIndex = 3, attemptLabel = "v1",
            branchId = "main", branchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
            outputMediaPath = "media/seg3_v1.mp4",
            serverLatentSlotKey = "shot_04::clip_3",
            createdAt = 300L,
        )
        val r3 = StatefulChainDag.appendNode(r2, "s1", "h3_motion", "main", "chain_a", null, seg3v1).record

        val seg4v1 = StatefulChainNode(
            nodeId = "seg_04_v1", parentNodeId = "seg_03_v1", stage = "next", segmentIndex = 4, attemptLabel = "v1",
            branchId = "main", branchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
            outputMediaPath = "media/seg4_v1.mp4",
            serverLatentSlotKey = "shot_04::clip_4",
            createdAt = 400L,
        )
        val r4 = StatefulChainDag.appendNode(r3, "s1", "h3_motion", "main", "chain_a", null, seg4v1).record

        // 从第 2 段分叉重试生成第 3 段 v2：独立分支目录模式下，兄弟与下游均保持 ACTIVE。
        val seg3v2Isolated = StatefulChainNode(
            nodeId = "seg_03_v2", parentNodeId = "seg_02_v1", stage = "next", segmentIndex = 3, attemptLabel = "v2",
            branchId = "branch_v2", branchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
            outputMediaPath = "media/seg3_v2.mp4",
            serverLatentSlotKey = "shot_04_v2::clip_3",
            createdAt = 500L,
        )
        val isolatedRes = StatefulChainDag.appendNode(r4, "s1", "h3_motion", "main", "chain_a", null, seg3v2Isolated)
        assertTrue(isolatedRes.invalidatedNodeIds.isEmpty())
        assertTrue(isolatedRes.record.nodes.all { it.stateValidity == ChainNodeValidity.ACTIVE })
        assertEquals("seg_03_v2", isolatedRes.record.activeTipNodeId)

        // 原位覆写策略从第 2 段重跑产生第 3 段 v3：同槽位 seg_03_v1 及其下游 seg_04_v1 被自动标记 OVERWRITTEN_BY_SIBLING，但视频仍保留回放。
        val seg3v3Overwrite = StatefulChainNode(
            nodeId = "seg_03_v3", parentNodeId = "seg_02_v1", stage = "next", segmentIndex = 3, attemptLabel = "v3",
            branchId = "main", branchPolicy = StatefulBranchPolicy.IN_PLACE_OVERWRITE,
            outputMediaPath = "media/seg3_v3.mp4",
            serverLatentSlotKey = "shot_04::clip_3",
            createdAt = 600L,
        )
        val overwriteRes = StatefulChainDag.appendNode(isolatedRes.record, "s1", "h3_motion", "main", "chain_a", null, seg3v3Overwrite)
        assertEquals(setOf("seg_03_v1", "seg_04_v1"), overwriteRes.invalidatedNodeIds.toSet())
        assertEquals(ChainNodeValidity.OVERWRITTEN_BY_SIBLING, overwriteRes.record.node("seg_03_v1")!!.stateValidity)
        assertEquals("media/seg3_v1.mp4", overwriteRes.record.node("seg_03_v1")!!.outputMediaPath)
        assertEquals(ChainNodeValidity.OVERWRITTEN_BY_SIBLING, overwriteRes.record.node("seg_04_v1")!!.stateValidity)
        assertEquals(ChainNodeValidity.ACTIVE, overwriteRes.record.node("seg_03_v2")!!.stateValidity)
        assertEquals(ChainNodeValidity.ACTIVE, overwriteRes.record.node("seg_03_v3")!!.stateValidity)

        // 禁止从已失效的 seg_03_v1 继续往下接续，防止废片链。
        val blocked = StatefulChainNode(
            nodeId = "seg_04_bad", parentNodeId = "seg_03_v1", stage = "next", segmentIndex = 4, attemptLabel = "v2",
            outputMediaPath = "media/bad.mp4",
        )
        assertThrows(IllegalArgumentException::class.java) {
            StatefulChainDag.appendNode(overwriteRes.record, "s1", "h3_motion", "main", "chain_a", null, blocked)
        }
    }

    @Test fun `cliPhysicalBranchIsolationRequiresPairedReadWriteFlagsAndDagNeverKillsAncestors`() {
        // 1. CLI_ARGS 仅在 readLatentDirFlag + writeLatentDirFlag 成对声明时才判定支持真物理分支隔离
        assertFalse(StatefulChainSpec().supportsPhysicalBranchIsolation(SubmitterInvocationProtocol.CLI_ARGS))
        assertFalse(StatefulChainSpec(branchIdFlag = "--branch-id").supportsPhysicalBranchIsolation(SubmitterInvocationProtocol.CLI_ARGS))
        assertFalse(StatefulChainSpec(writeLatentDirFlag = "--write-latent-dir").supportsPhysicalBranchIsolation(SubmitterInvocationProtocol.CLI_ARGS))
        assertTrue(
            StatefulChainSpec(
                readLatentDirFlag = "--read-latent-dir",
                writeLatentDirFlag = "--write-latent-dir",
            ).supportsPhysicalBranchIsolation(SubmitterInvocationProtocol.CLI_ARGS),
        )
        assertTrue(StatefulChainSpec().supportsPhysicalBranchIsolation(SubmitterInvocationProtocol.PAYLOAD_JSON))

        // 2. 复现 Scenario C 缺陷场景：若脚本在 ISOLATED_DIR 下回报碰撞槽位 main::clip_0（且连父节点也撞），
        //    DAG 必须诚实降级为 IN_PLACE_OVERWRITE、将兄弟节点 seg_02_v1 标为 OVERWRITTEN_BY_SIBLING，且绝不误杀父节点 seg_01_v1！
        val seg1 = StatefulChainNode(
            nodeId = "seg_01_v1", parentNodeId = null, stage = "first", segmentIndex = 1, attemptLabel = "v1",
            branchId = "main", branchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
            outputMediaPath = "m/1.png",
            serverLatentSlotKey = "main::clip_0",
            createdAt = 10L,
        )
        val r1 = StatefulChainDag.appendNode(null, "s1", "chain_live_test", "main", "c1", null, seg1).record

        val seg2Main = StatefulChainNode(
            nodeId = "seg_02_v1", parentNodeId = "seg_01_v1", stage = "next", segmentIndex = 2, attemptLabel = "v1",
            branchId = "main", branchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
            outputMediaPath = "m/2.png",
            serverLatentSlotKey = "main::clip_0",
            createdAt = 20L,
        )
        val r2 = StatefulChainDag.appendNode(r1, "s1", "chain_live_test", "main", "c1", null, seg2Main)
        assertTrue("父节点 seg_01_v1 不得被子节点误杀", r2.invalidatedNodeIds.isEmpty())
        assertEquals(ChainNodeValidity.ACTIVE, r2.record.node("seg_01_v1")!!.stateValidity)

        val seg2Fork = StatefulChainNode(
            nodeId = "seg_02_v2", parentNodeId = "seg_01_v1", stage = "next", segmentIndex = 2, attemptLabel = "v2",
            branchId = "b_01_v1_9999", branchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
            outputMediaPath = "m/3.png",
            serverLatentSlotKey = "main::clip_0",
            createdAt = 30L,
        )
        val r3 = StatefulChainDag.appendNode(r2.record, "s1", "chain_live_test", "main", "c1", null, seg2Fork)
        assertEquals(listOf("seg_02_v1"), r3.invalidatedNodeIds)
        assertEquals(ChainNodeValidity.ACTIVE, r3.record.node("seg_01_v1")!!.stateValidity)
        assertEquals(ChainNodeValidity.OVERWRITTEN_BY_SIBLING, r3.record.node("seg_02_v1")!!.stateValidity)
        assertEquals(ChainNodeValidity.ACTIVE, r3.record.node("seg_02_v2")!!.stateValidity)
        assertEquals(StatefulBranchPolicy.IN_PLACE_OVERWRITE, r3.appendedNode.branchPolicy)
        assertTrue(r3.appendedNode.isolationWarning.orEmpty().contains("main::clip_0"))
    }

    @Test
    fun `ComfyTemplate 通道投影保留声明的 characterReferenceMode 而非硬编码分张`() {
        val tpl = ComfyTemplate(
            id = "sd_ref",
            workflow = JsonObject(emptyMap()),
            slots = listOf(ComfySlot("1", "image_0", SlotKind.REFERENCE_IMAGES, capacity = 2)),
            capabilities = MediaCapabilities(
                modes = setOf(GenerationMode.REFERENCE),
                maxImages = 2,
                characterReferenceMode = CharacterReferenceMode.COMPOSITE,
            ),
            hostId = "main",
            group = MediaGroup.VIDEO,
        )
        assertEquals(CharacterReferenceMode.COMPOSITE, tpl.channel().capabilities.characterReferenceMode)
    }

    @Test
    fun `工作流保留键 character_reference_mode 支持一次性 fixed_overrides Diff 检出与同值放行`() {
        val wf = ComfyWorkflow(
            workflowId = "h3_ref",
            hostId = "main",
            name = "H3 Ref2VA",
            mediaCategory = ComfyMediaCategory.VIDEO,
            submitterScriptId = "submit_h3",
            capabilities = MediaCapabilities(
                modes = setOf(GenerationMode.REFERENCE),
                maxImages = 3,
                characterReferenceMode = CharacterReferenceMode.SEPARATE,
            ),
        )
        // 同值不触发 Diff 审批
        assertTrue(wf.detectFixedOverrides(mapOf("character_reference_mode" to JsonPrimitive("separate"))).isEmpty())

        // 切到 composite 检出精确 Diff
        val diffs = wf.detectFixedOverrides(mapOf("character_reference_mode" to JsonPrimitive("composite")))
        assertEquals(1, diffs.size)
        assertEquals("character_reference_mode", diffs.single().key)
        assertEquals("资产参考入参模式", diffs.single().label)
        assertTrue(diffs.single().oldDisplayValue.contains("分张"))
        assertTrue(diffs.single().newDisplayValue.contains("整卡"))
        assertEquals(JsonPrimitive("composite"), diffs.single().newValue)

        // 非法取值报错
        assertThrows(IllegalArgumentException::class.java) {
            wf.detectFixedOverrides(mapOf("character_reference_mode" to JsonPrimitive("invalid")))
        }
    }
}
