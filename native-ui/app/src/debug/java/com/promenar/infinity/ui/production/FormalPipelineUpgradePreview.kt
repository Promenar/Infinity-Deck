package com.promenar.infinity.ui.production

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.data.mediagen.comfy.ComfyHost
import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.ui.chat.taskPanelUiState
import com.promenar.infinity.data.production.InputRole
import com.promenar.infinity.data.production.ManifestSync
import com.promenar.infinity.data.production.PlannedInput
import com.promenar.infinity.data.production.ProductionSnapshot
import com.promenar.infinity.data.production.ProductionStage
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.ShotInput
import com.promenar.infinity.data.production.StageStatus
import com.promenar.infinity.data.production.Take
import com.promenar.infinity.data.production.TakeDerivation
import com.promenar.infinity.data.production.TakeSource
import com.promenar.infinity.data.production.MediaSpec
import com.promenar.infinity.data.state.StateBoard
import com.promenar.infinity.data.state.StateBoardSnapshot
import com.promenar.infinity.domain.mediagen.ChainNodeValidity
import com.promenar.infinity.domain.mediagen.ComfyMediaCategory
import com.promenar.infinity.domain.mediagen.ComfyWorkflow
import com.promenar.infinity.domain.mediagen.ComfyWorkflowState
import com.promenar.infinity.domain.mediagen.FieldMutability
import com.promenar.infinity.domain.mediagen.FieldSemanticRole
import com.promenar.infinity.domain.mediagen.FieldValueType
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.GenerationSpecs
import com.promenar.infinity.domain.mediagen.InputKind
import com.promenar.infinity.domain.mediagen.InputRole as MediaInputRole
import com.promenar.infinity.domain.mediagen.MediaCapabilities
import com.promenar.infinity.domain.mediagen.MediaChannel
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.domain.mediagen.StatefulChainNode
import com.promenar.infinity.domain.mediagen.StatefulChainRecord
import com.promenar.infinity.domain.mediagen.StatefulChainSpec
import com.promenar.infinity.domain.mediagen.WorkflowExecutionMode
import com.promenar.infinity.domain.mediagen.WorkflowFieldSpec
import com.promenar.infinity.ui.chat.components.RunDetailContent
import com.promenar.infinity.ui.chat.components.RunDetailTab
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Composable
private fun FormalFrame(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

private fun formalPostProcessSnapshot(): ProductionSnapshot {
    val base = ProductionPhaseTwoSamples.snapshot.production
    val withPost = base.copy(
        stages = base.stages.map { st ->
            if (st.stage == ProductionStage.POST_PROCESS) st.copy(status = StageStatus.IN_PROGRESS) else st
        },
        seasons = base.seasons.map { season ->
            season.copy(
                episodes = season.episodes.map { ep ->
                    ep.copy(
                        scenes = ep.scenes.map { sc ->
                            sc.copy(
                                shots = sc.shots.map { sh ->
                                    when (sh.code) {
                                        "SC01-01" -> {
                                            val orig = Take(
                                                id = "t-orig-1",
                                                path = "productions/harbor/S1E01/takes/sc01-01.mp4",
                                                source = TakeSource("ComfyUI"),
                                                createdAt = 100L,
                                                spec = MediaSpec(1344, 768, 24.0, 5.0),
                                            )
                                            val derived = Take(
                                                id = "t-post-1",
                                                path = "productions/harbor/S1E01/takes/sc01-01-up.mp4",
                                                source = TakeSource("ComfyUI（2x 超分）"),
                                                createdAt = 200L,
                                                spec = MediaSpec(2688, 1536, 24.0, 5.0),
                                                derivedFrom = TakeDerivation("t-orig-1", "2x 超分"),
                                            )
                                            sh.copy(takes = listOf(orig, derived), selectedTakeId = derived.id)
                                        }
                                        "SC01-02" -> {
                                            val orig = Take(
                                                id = "t-orig-2",
                                                path = "productions/harbor/S1E01/takes/sc01-02.mp4",
                                                source = TakeSource("Seedance"),
                                                createdAt = 100L,
                                                spec = MediaSpec(1280, 720, 24.0, 4.5),
                                            )
                                            val derived = Take(
                                                id = "t-post-2",
                                                path = "productions/harbor/S1E01/takes/sc01-02-up.mp4",
                                                source = TakeSource("ComfyUI（2x 超分）"),
                                                createdAt = 210L,
                                                spec = MediaSpec(2560, 1440, 24.0, 4.5),
                                                derivedFrom = TakeDerivation("t-orig-2", "2x 超分"),
                                            )
                                            sh.copy(takes = listOf(orig, derived), selectedTakeId = orig.id)
                                        }
                                        else -> sh
                                    }
                                },
                            )
                        },
                    )
                },
            )
        },
    )
    return ProductionSnapshot(withPost, 12L, ManifestSync.InSync)
}

@Preview(name = "正式后处理页签 深中400", widthDp = 400, heightDp = 780, locale = "zh-rCN")
@Composable
fun FormalPostProcessTabDarkPreview() = FormalFrame(dark = true) {
    ProductionPanelContent(
        snapshot = formalPostProcessSnapshot(),
        onAction = { _, _ -> null },
        onResolve = { null },
        initialTab = ProductionTab.POST_PROCESS,
        services = ProductionPhaseTwoSamples.services,
        tasks = ProductionPhaseTwoSamples.tasks,
        channels = ProductionPhaseTwoSamples.channels,
        channelOrder = ProductionPhaseTwoSamples.order,
    )
}

@Preview(name = "正式镜头规格设置 浅中400", widthDp = 400, heightDp = 760, locale = "zh-rCN")
@Composable
fun FormalShotSpecsSheetLightPreview() = FormalFrame(dark = false) {
    val comfyChannel = MediaChannel(
        id = "comfy:wf-video",
        group = MediaGroup.VIDEO,
        capabilities = MediaCapabilities(setOf(GenerationMode.REFERENCE)),
    )
    val comfyWorkflow = ComfyWorkflow(
        workflowId = "wf-video",
        hostId = "main",
        name = "视频工作流",
        mediaCategory = ComfyMediaCategory.VIDEO,
        rawWorkflowJson = buildJsonObject {},
        capabilities = MediaCapabilities(setOf(GenerationMode.REFERENCE)),
        fields = listOf(
            WorkflowFieldSpec(
                key = "width",
                label = "宽度",
                mutability = FieldMutability.CONTENT,
                valueType = FieldValueType.INT,
                defaultValue = JsonPrimitive(1344),
                semanticRole = FieldSemanticRole.WIDTH,
            ),
            WorkflowFieldSpec(
                key = "height",
                label = "高度",
                mutability = FieldMutability.CONTENT,
                valueType = FieldValueType.INT,
                defaultValue = JsonPrimitive(768),
                semanticRole = FieldSemanticRole.HEIGHT,
            ),
        ),
    )
    val cloudChannel = MediaChannel(
        id = "cloud:seedance",
        group = MediaGroup.VIDEO,
        capabilities = MediaCapabilities(
            modes = setOf(GenerationMode.REFERENCE),
            resolutions = listOf("720p", "1080p"),
        ),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("本地工作流规格（自定义像素宽高）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            ShotSpecsSheetContent(
                shot = Shot("SC01-03", "低身旋斩", durationSeconds = 5.0, specWidth = 1920, specHeight = 1088),
                channel = comfyChannel,
                workflow = comfyWorkflow,
                previewSpecs = GenerationSpecs(width = 1920, height = 1088),
                onSave = { _, _, _ -> },
            )
        }
        Text("云端通道规格（动态能力枚举）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            ShotSpecsSheetContent(
                shot = Shot("SC01-02", "林晚回头", durationSeconds = 4.5, specResolution = "1080p"),
                channel = cloudChannel,
                workflow = null,
                previewSpecs = GenerationSpecs(resolution = "1080p"),
                onSave = { _, _, _ -> },
            )
        }
    }
}

@Preview(name = "正式锁帧素材与只读链 深中400", widthDp = 400, heightDp = 820, locale = "zh-rCN")
@Composable
fun FormalGuideAnchorAndChainDarkPreview() = FormalFrame(dark = true) {
    val shot = Shot(
        code = "SC01-03",
        title = "低身旋斩",
        durationSeconds = 10.0,
        inputs = listOf(
            ShotInput(InputRole.ANCHOR, "productions/demo/kf0.png", label = "起手构图", atSeconds = 0.0),
            ShotInput(InputRole.ANCHOR, "productions/demo/kf60.png", label = "收刀定格", atSeconds = 2.5),
            ShotInput(InputRole.REFERENCE, "productions/demo/char.png", label = "陆临正面"),
        ),
    )
    val inputs = listOf(
        PlannedInput(null, "起手构图", "0.0 秒", InputKind.IMAGE, MediaInputRole.REFERENCE, "productions/demo/kf0.png", isAnchor = true, atSeconds = 0.0),
        PlannedInput(null, "收刀定格", "2.5 秒", InputKind.IMAGE, MediaInputRole.REFERENCE, "productions/demo/kf60.png", isAnchor = true, atSeconds = 2.5),
        PlannedInput(1, "陆临正面", "黑衣立领", InputKind.IMAGE, MediaInputRole.REFERENCE, "productions/demo/char.png"),
        PlannedInput(2, "雨夜古坪", "场景参考", InputKind.IMAGE, MediaInputRole.REFERENCE, "productions/demo/loc.png"),
    )
    val workflow = ComfyWorkflow(
        workflowId = "wf-chain",
        hostId = "main",
        name = "长镜续写工作流",
        mediaCategory = ComfyMediaCategory.VIDEO,
        rawWorkflowJson = buildJsonObject {},
        executionMode = WorkflowExecutionMode.STATEFUL_CHAIN,
        chainSpec = StatefulChainSpec(),
        fps = 24.0,
        capabilities = MediaCapabilities(setOf(GenerationMode.REFERENCE)),
    )
    val chain = StatefulChainRecord(
        chainId = "chain-1",
        sessionId = "s1",
        workflowId = "wf-chain",
        hostId = "main",
        shotRef = "SC01-03",
        rootNodeId = "n1",
        activeTipNodeId = "n2",
        nodes = listOf(
            StatefulChainNode(
                nodeId = "n1",
                parentNodeId = null,
                stage = "first",
                segmentIndex = 1,
                attemptLabel = "v1",
                outputMediaPath = "productions/demo/seg1.mp4",
                startSeconds = 0.0,
                endSeconds = 5.0,
                frames = 124,
                promptSummary = "雨夜石坪起手，人物低身前倾准备拔剑，雨水沿青石板飞溅。",
                stateValidity = ChainNodeValidity.ACTIVE,
            ),
            StatefulChainNode(
                nodeId = "n2",
                parentNodeId = "n1",
                stage = "next",
                segmentIndex = 2,
                attemptLabel = "v1",
                outputMediaPath = "productions/demo/seg2.mp4",
                startSeconds = 5.0,
                endSeconds = 10.0,
                frames = 124,
                promptSummary = "承接上段势能高速旋斩，长剑划破雨幕后定格收刀。",
                stateValidity = ChainNodeValidity.ACTIVE,
            ),
        ),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProductionSection(
            title = "输入",
            titleSegments = listOf("输入", "分张", "2 张", "2 个锁帧"),
            trailing = "按本地工作流传参",
        ) {
            inputs.forEachIndexed { idx, planned ->
                if (idx > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 76.dp, end = InfinitySpacing.RowHorizontal),
                    )
                }
                PlannedInputRow(planned, fps = 24.0)
            }
        }
        ProductionComfyChainSection(
            workflowState = ComfyWorkflowState(workflow),
            hosts = listOf(ComfyHost("main", "MAIN", "http://127.0.0.1:8188")),
            chains = listOf(chain),
            sessionId = "s1",
            shot = shot,
            defaultPrompt = "",
            executionEngine = null,
            chainStore = null,
            parentWorking = false,
            onResultMessage = {},
            onErrorMessage = {},
        )
    }
}

private fun formalMixedLengthPlan() = requireNotNull(
    taskPanelUiState(
        listOf(
            TaskStep(
                id = "g1",
                title = "前期资产与分镜定稿",
                sortOrder = 0,
                children = listOf(
                    TaskStep(id = "g1-1", title = "生成角色卡", status = "done", sortOrder = 0),
                    TaskStep(id = "g1-2", title = "核对雨夜石坪机位与锁场", status = "done", sortOrder = 1),
                ),
            ),
            TaskStep(
                id = "g2",
                title = "出片与后处理执行",
                sortOrder = 1,
                children = listOf(
                    TaskStep(id = "g2-1", title = "核对分辨率", status = "done", sortOrder = 0),
                    TaskStep(
                        id = "g2-2",
                        title = "按 1920x1088 规格运行 SC01-03 两段续写链并自动透传 0 秒与 2.5 秒时序锁帧锚点至工作流槽位，落盘后同步总览任务队列进度",
                        status = "doing",
                        sortOrder = 1,
                    ),
                    TaskStep(id = "g2-3", title = "超分", status = "todo", sortOrder = 2),
                    TaskStep(
                        id = "g2-4",
                        title = "逐镜比对原片与派生版本清晰度并筛选最终剪辑轨采用的候选版本，确认无音画错位后交付粗剪成片",
                        status = "todo",
                        sortOrder = 3,
                    ),
                ),
            ),
        ),
        isGenerating = true,
        goal = "惊澜三十秒成片交付",
    ),
)

@Preview(name = "正式Todo混排 深中400", widthDp = 400, heightDp = 760, locale = "zh-rCN")
@Composable
fun FormalTodoMixedLengthDarkPreview() = FormalFrame(dark = true) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
        RunDetailContent(
            initialTab = RunDetailTab.PLAN,
            plan = formalMixedLengthPlan(),
            isGenerating = true,
            turnStartedAt = 1_792_400_000_000L - 195_000L,
            activity = null,
            stateBoard = StateBoardSnapshot(1, StateBoard("进度", emptyList())),
            onDismiss = {},
            onContinue = {},
            onComplete = {},
            onHidePlan = {},
            onClearPlan = {},
            onEditState = { _, _ -> null },
            onRemoveState = { null },
            modifier = Modifier.fillMaxSize(),
            nowMillis = 1_792_400_000_000L,
        )
    }
}

@Preview(name = "正式Todo混排 浅中400", widthDp = 400, heightDp = 760, locale = "zh-rCN")
@Composable
fun FormalTodoMixedLengthLightPreview() = FormalFrame(dark = false) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
        RunDetailContent(
            initialTab = RunDetailTab.PLAN,
            plan = formalMixedLengthPlan(),
            isGenerating = true,
            turnStartedAt = 1_792_400_000_000L - 195_000L,
            activity = null,
            stateBoard = StateBoardSnapshot(1, StateBoard("进度", emptyList())),
            onDismiss = {},
            onContinue = {},
            onComplete = {},
            onHidePlan = {},
            onClearPlan = {},
            onEditState = { _, _ -> null },
            onRemoveState = { null },
            modifier = Modifier.fillMaxSize(),
            nowMillis = 1_792_400_000_000L,
        )
    }
}
