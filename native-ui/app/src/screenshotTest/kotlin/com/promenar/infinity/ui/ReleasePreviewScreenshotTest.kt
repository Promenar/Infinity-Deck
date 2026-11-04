package com.promenar.infinity.ui

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ApprovalRequest
import com.promenar.infinity.data.model.ApprovalCallIdentity
import com.promenar.infinity.data.model.Citation
import com.promenar.infinity.data.model.KgEdge
import com.promenar.infinity.data.model.KgNode
import com.promenar.infinity.data.model.KgPath
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.data.backup.BackupExportOptions
import com.promenar.infinity.data.backup.PendingRestoreMetadata
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.UnsupportedProviderListItem
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.webdav.RemoteBackup
import com.promenar.infinity.data.remote.webdav.WebDavConfig
import com.promenar.infinity.data.repository.BackupUploadReceipt
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationFailureCodec
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.MemoryVectorRecord
import com.promenar.infinity.domain.repository.RenameIndexTarget
import com.promenar.infinity.ui.chat.ApprovalCard
import com.promenar.infinity.ui.chat.ApprovalDisplayCall
import com.promenar.infinity.ui.chat.ApprovalPreview
import com.promenar.infinity.ui.chat.ChatScreenActions
import com.promenar.infinity.ui.chat.ChatScreenContent
import com.promenar.infinity.ui.chat.ChatScreenState
import com.promenar.infinity.ui.chat.ChatUiState
import com.promenar.infinity.ui.chat.GenerationStatus
import com.promenar.infinity.ui.chat.taskPanelUiState
import com.promenar.infinity.ui.chat.ResourceExplorerSheetActions
import com.promenar.infinity.ui.chat.ResourceExplorerSheetContent
import com.promenar.infinity.ui.chat.ResourceExplorerSheetState
import com.promenar.infinity.ui.chat.components.FilesPanel
import com.promenar.infinity.ui.chat.components.RagDetailsSheetContent
import com.promenar.infinity.ui.chat.components.RunDetailContent
import com.promenar.infinity.ui.chat.components.RunDetailTab
import com.promenar.infinity.ui.chat.components.RunDockRow
import com.promenar.infinity.ui.chat.components.runDockState
import com.promenar.infinity.ui.common.SecretField
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.common.status.UiStatusNotice
import com.promenar.infinity.ui.hub.AgentDisplayItem
import com.promenar.infinity.ui.hub.AgentHubScreenActions
import com.promenar.infinity.ui.hub.AgentHubScreenContent
import com.promenar.infinity.ui.hub.AgentHubScreenState
import com.promenar.infinity.ui.hub.UserSettingsHomeScreenActions
import com.promenar.infinity.ui.hub.UserSettingsHomeScreenContent
import com.promenar.infinity.ui.hub.UserSettingsHomeScreenState
import com.promenar.infinity.ui.rag.DocEditorConfirmation
import com.promenar.infinity.ui.rag.DocEditorContentAccess
import com.promenar.infinity.ui.rag.DocEditorFailureCode
import com.promenar.infinity.ui.rag.DocEditorPhase
import com.promenar.infinity.ui.rag.DocEditorScreenActions
import com.promenar.infinity.ui.rag.DocEditorScreenContent
import com.promenar.infinity.ui.rag.DocEditorScreenState
import com.promenar.infinity.ui.rag.DocEditorUiState
import com.promenar.infinity.ui.rag.DocEditorViewMode
import com.promenar.infinity.ui.rag.IndexingNotice
import com.promenar.infinity.ui.rag.PortalTab
import com.promenar.infinity.ui.rag.RagHomeScreenActions
import com.promenar.infinity.ui.rag.RagHomeScreenContent
import com.promenar.infinity.ui.rag.RagHomeScreenState
import com.promenar.infinity.ui.rag.RagFolderContentState
import com.promenar.infinity.ui.rag.RagFolderScreenActions
import com.promenar.infinity.ui.rag.RagFolderScreenContent
import com.promenar.infinity.ui.rag.RagFolderScreenState
import com.promenar.infinity.ui.rag.KnowledgeBaseSettingsActions
import com.promenar.infinity.ui.rag.KnowledgeBaseSettingsContent
import com.promenar.infinity.ui.rag.KnowledgeBaseSettingsState
import com.promenar.infinity.ui.rag.KnowledgeDebugSettingsActions
import com.promenar.infinity.ui.rag.KnowledgeDebugSettingsContent
import com.promenar.infinity.ui.rag.KnowledgeDebugSettingsState
import com.promenar.infinity.ui.rag.KnowledgeGraphSettingsActions
import com.promenar.infinity.ui.rag.KnowledgeGraphSettingsContent
import com.promenar.infinity.ui.rag.KnowledgeGraphSettingsState
import com.promenar.infinity.ui.rag.RagStats
import com.promenar.infinity.ui.settings.ModelSyncNotice
import com.promenar.infinity.ui.settings.ModelEditorScreen
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.ui.common.ModelSelectionUiModel
import com.promenar.infinity.ui.common.ModelPickerSheetContent
import com.promenar.infinity.ui.settings.ModelTestState
import com.promenar.infinity.ui.settings.ThemeScreenContent
import com.promenar.infinity.ui.settings.ThemeUiState
import com.promenar.infinity.ui.settings.BackupOperations
import com.promenar.infinity.ui.settings.BackupRestartRequester
import com.promenar.infinity.ui.settings.BackupSettingsScreen
import com.promenar.infinity.ui.settings.BackupSettingsStore
import com.promenar.infinity.ui.settings.BackupViewModel
import com.promenar.infinity.ui.settings.ProviderModelsScreenActions
import com.promenar.infinity.ui.settings.ProviderModelsScreenContent
import com.promenar.infinity.ui.settings.ProviderModelsScreenState
import com.promenar.infinity.ui.settings.ProviderListScreenActions
import com.promenar.infinity.ui.settings.ProviderListScreenContent
import com.promenar.infinity.ui.settings.ProviderListScreenState
import com.promenar.infinity.ui.settings.DefaultModelsScreenActions
import com.promenar.infinity.ui.settings.DefaultModelsScreenContent
import com.promenar.infinity.ui.settings.DefaultModelsScreenState
import com.promenar.infinity.ui.settings.PROVIDER_PRESETS
import com.promenar.infinity.ui.settings.ProviderFormActions
import com.promenar.infinity.ui.settings.ProviderFormContent
import com.promenar.infinity.ui.settings.ProviderFormUiState
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareRejectReason
import java.io.InputStream
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf

private const val PHONE_WIDTH_DP = 412
private const val PHONE_HEIGHT_DP = 892
private const val LANDSCAPE_WIDTH_DP = 892
private const val LANDSCAPE_HEIGHT_DP = 412
private const val PREVIEW_CHAT_MODEL_ID = "default::deepseek-v4-flash"
private const val PREVIEW_MEMORY_ID = "preview-memory-1"

private val PREVIEW_MODEL_SELECTIONS = List(120) { index ->
    ModelSelectionUiModel(
        selectionId = "provider-preview::model-$index",
        remoteModelId = "model-$index",
        displayName = if (index == 0) {
            "Extremely Long Official Multimodal Reasoning Model Name With Stable Version Suffix 2026-07"
        } else if (index == 1) {
            "Unknown capability model"
        } else {
            "Model ${index.toString().padStart(3, '0')}"
        },
        providerName = if (index % 2 == 0) "Infinity Workbench Preview Provider" else "OpenAI Compatible",
        contextTokens = if (index == 0) 1_048_576 else 128_000,
        workload = ModelWorkload.GENERATIVE_TEXT,
        capabilityStates = mapOf(
            ModelCapability.REASONING to if (index % 3 == 0) {
                SupportState.SUPPORTED
            } else SupportState.UNKNOWN,
            ModelCapability.VISION_INPUT to if (index % 4 == 0) {
                SupportState.SUPPORTED
            } else SupportState.UNKNOWN,
            ModelCapability.TOOL_CALLING to SupportState.UNKNOWN,
        ),
        chatEndpointCompatible = if (index % 2 == 0) {
            SupportState.SUPPORTED
        } else SupportState.UNKNOWN,
    )
}

private val PREVIEW_RAG_DETAILS_REFERENCES = listOf(
    RagReference(
        id = "reference-release-gate",
        source = "/knowledge/release/complete-accessibility-and-visual-regression-checklist.md",
        content = "The release candidate must keep every action reachable at phone width, landscape, and large font scale while preserving the complete source context.",
        score = 0.72f,
        rerankScore = 0.89f,
        rankChange = 2,
        recallRank = 3,
        kind = com.promenar.infinity.data.model.RagReferenceKind.DOCUMENT,
    ),
    RagReference(
        id = "reference-material",
        source = "/knowledge/design/material-three-component-guidance.md",
        content = "Use stable Material 3 components, tonal surfaces, readable typography, and explicit recovery actions for failures.",
        score = 0.87f,
        rerankScore = 0.81f,
        rankChange = -1,
        recallRank = 1,
        kind = com.promenar.infinity.data.model.RagReferenceKind.MEMORY,
    ),
)

private val PREVIEW_RAG_TRACE = com.promenar.infinity.data.model.RagRetrievalTrace(
    outcome = com.promenar.infinity.data.model.RagRetrievalTrace.OUTCOME_HIT,
    recallCount = 12,
    selectedCount = 2,
    scoring = com.promenar.infinity.data.model.RagRetrievalTrace.SCORING_VECTOR,
    rerank = com.promenar.infinity.data.model.RagRetrievalTrace.RERANK_API,
    rerankModel = "bge-reranker-v2-m3",
    searchTimeMs = 850,
    rerankTimeMs = 320,
    memoryCount = 1,
    documentCount = 1,
)

private val PREVIEW_RAG_DETAILS_CITATIONS = listOf(
    Citation(
        title = "Material Design accessibility guidance for responsive modal content",
        url = "https://m3.material.io/foundations/accessible-design/overview",
        source = "Material Design",
        snippet = "Touch targets, readable text, selected states, and clear recovery feedback remain available at every supported window size.",
    ),
    Citation(
        title = "Android large text and responsive layout testing",
        url = "https://developer.android.com/guide/topics/ui/accessibility/testing",
        source = "Android Developers",
        snippet = "Test long content, dynamic type, landscape, semantics, and complete scroll reachability before release.",
    ),
)

private val PREVIEW_RAG_DETAILS_KG_PATHS = (1..4).map { index ->
    val source = KgNode(
        id = "kg-source-$index",
        label = "Release validation source entity with a deliberately long label $index",
        type = "requirement",
    )
    val target = KgNode(
        id = "kg-target-$index",
        label = "Commercial quality delivery condition with readable long content $index",
        type = "gate",
    )
    KgPath(
        queryKeywords = listOf("release", "accessibility", "visual quality"),
        nodes = listOf(source, target),
        edges = listOf(
            KgEdge(
                sourceId = source.id,
                targetId = target.id,
                relation = "must be verified through a complete responsive validation workflow",
            ),
        ),
        reasoning = "The relationship stays readable as a vertical sentence instead of three compressed columns.",
    )
}

private val PREVIEW_MEMORY_VECTORS = listOf(
    MemoryVectorRecord(
        id = PREVIEW_MEMORY_ID,
        content = "The release review established that every visual baseline must be checked at phone width and large font before delivery. This memory keeps the complete decision readable when expanded.",
        sessionId = "session-release-review",
        createdAt = 1_725_000_000_000,
    ),
    MemoryVectorRecord(
        id = "preview-memory-2",
        content = "Knowledge graph navigation opens its own destination and is not a third content tab.",
        sessionId = "session-rag-design",
        createdAt = 1_724_900_000_000,
    ),
)

@PreviewTest
@Preview(
    name = "Main navigation phone dark",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun mainNavigationPhoneDarkReleasePreview() {
    NavigationReleasePreview(expanded = false, dark = true)
}

@PreviewTest
@Preview(
    name = "Main navigation phone light",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun mainNavigationPhoneLightReleasePreview() {
    NavigationReleasePreview(expanded = false, dark = false)
}

@PreviewTest
@Preview(
    name = "Main navigation phone large font",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
fun mainNavigationPhoneLargeFontReleasePreview() {
    NavigationReleasePreview(
        expanded = false,
        dark = true,
        selectedTab = AppTab.SETTINGS,
    )
}

@PreviewTest
@Preview(
    name = "Main navigation phone landscape",
    widthDp = 599,
    heightDp = 360,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun mainNavigationPhoneLandscapeReleasePreview() {
    NavigationReleasePreview(expanded = false, dark = false)
}

@PreviewTest
@Preview(
    name = "Main navigation tablet dark",
    widthDp = 840,
    heightDp = 900,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun mainNavigationTabletDarkReleasePreview() {
    NavigationReleasePreview(expanded = true, dark = true)
}

@PreviewTest
@Preview(
    name = "Main navigation tablet light",
    widthDp = 840,
    heightDp = 900,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun mainNavigationTabletLightReleasePreview() {
    NavigationReleasePreview(expanded = true, dark = false)
}

@Composable
private fun NavigationReleasePreview(
    expanded: Boolean,
    dark: Boolean,
    selectedTab: AppTab = AppTab.LIBRARY,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        AdaptiveNavigationSurface(
            expanded = expanded,
            selectedTab = selectedTab,
            onTabSelected = {},
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Infinity Workbench workspace",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@PreviewTest
@Preview(
    name = "RAG home memory content phone",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragHomeMemoryContentPhoneReleasePreview() {
    ReleasePreviewSurface {
        RagHomeScreenContent(
            state = previewMemoryState(PREVIEW_MEMORY_VECTORS),
            actions = RagHomeScreenActions(),
            documentsContent = { modifier, _, _ -> Box(modifier) },
        )
    }
}

@PreviewTest
@Preview(
    name = "Provider masked API key",
    widthDp = PHONE_WIDTH_DP,
    heightDp = 220,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun providerMaskedKeyReleasePreview() {
    ReleasePreviewSurface {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.provider_form_label_api_key),
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecretField(
                value = "",
                onValueChange = {},
                hasStoredSecret = true,
                onRevealRequest = { null },
                onClear = {},
                placeholder = stringResource(R.string.provider_form_placeholder_api_key),
            )
            Text(
                text = stringResource(R.string.provider_form_secure_storage),
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Provider form add phone",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun providerFormAddReleasePreview() {
    ReleasePreviewSurface {
        ProviderFormContent(
            state = ProviderFormUiState(
                name = "Preview Gateway",
                selectedPreset = PROVIDER_PRESETS.first(),
                baseUrl = "https://api.example.com/v1",
                endpointValid = true,
            ),
            actions = ProviderFormActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Provider form edit actions 2x",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun providerFormEditActionsLargeFontReleasePreview() {
    ReleasePreviewSurface {
        ProviderFormContent(
            state = ProviderFormUiState(
                isEditing = true,
                isExistingProvider = true,
                name = "Gemini Preview",
                selectedPreset = PROVIDER_PRESETS.first { it.name == "Gemini" },
                baseUrl = "https://generativelanguage.googleapis.com",
                hasStoredCredential = true,
                usesVertexCredential = true,
                endpointValid = true,
            ),
            actions = ProviderFormActions(),
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 3),
        )
    }
}

private const val PREVIEW_METADATA_SHORT_NAME = "Infinity Workbench Chat"
private const val PREVIEW_REASONING_MODEL_NAME = "DeepSeek V4 Flash"
private const val PREVIEW_METADATA_LONG_NAME =
    "超长模型展示名称：用于边界回归的长名称场景，仍需保持时间戳可见与布局稳定"

@PreviewTest
@Preview(
    name = "Empty chat English",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun emptyChatReleasePreview() {
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession(),
                    agentName = "Infinity Workbench Assistant",
                ),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Streaming chat Chinese",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun streamingChatReleasePreview() {
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession(),
                    agentName = "Infinity Workbench 助手",
                    messages = listOf(
                        previewMessage("user-1", MessageRole.USER, "请总结这份发行检查清单。"),
                        previewMessage(
                            "assistant-1",
                            MessageRole.ASSISTANT,
                            "正在核对安全、备份、后台生成与视觉回归门禁……",
                            reasoning = "先核对安全与备份门禁，再验证后台生成和视觉回归。",
                            modelId = PREVIEW_CHAT_MODEL_ID,
                        ),
                    ),
                    isGenerating = true,
                    status = GenerationStatus.RECEIVING,
                    streamingContent = "正在核对安全、备份、后台生成与视觉回归门禁……",
                ),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

private fun previewTaskTree(): List<TaskStep> = listOf(
    TaskStep(
        id = "release-plan",
        title = "完成 0.2 Beta 发布检查",
        children = listOf(
            TaskStep(id = "release-safety", title = "核对安全与备份门禁", status = "done"),
            TaskStep(
                id = "release-visual",
                title = "验证任务面板交互与视觉回归",
                status = "doing",
                sortOrder = 1,
            ),
        ),
    ),
)

@PreviewTest
@Preview(
    name = "Chat task checklist expanded Chinese",
    widthDp = PHONE_WIDTH_DP,
    heightDp = 560,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatTaskChecklistExpandedReleasePreview() {
    val tree = previewTaskTree().map { root ->
        root.copy(
            children = root.children + listOf(
                TaskStep(id = "release-notes", title = "整理发布说明", status = "todo", sortOrder = 2),
                TaskStep(id = "release-legacy", title = "迁移旧版导出格式", status = "dropped", sortOrder = 3),
            ),
        )
    }
    ReleasePreviewSurface {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 运行坞在应用中位于输入框浮岛顶部，预览套用同一底色与圆角；下方是点开后的运行详情计划页。
            val paused = requireNotNull(taskPanelUiState(tree, isGenerating = false, goal = "完成 0.2 Beta 发布"))
            val done = requireNotNull(
                taskPanelUiState(
                    tree.map { root -> root.copy(children = root.children.map { it.copy(status = "done") }) },
                    isGenerating = false,
                    goal = "完成 0.2 Beta 发布",
                ),
            )
            listOf(paused, done).forEach { state ->
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    RunDockRow(
                        state = requireNotNull(runDockState(state, false, 0, 0, 0, null, null)),
                        onOpen = {},
                    )
                }
            }
            androidx.compose.material3.Surface(
                modifier = Modifier.weight(1f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
            ) {
                RunDetailContent(
                    initialTab = RunDetailTab.PLAN,
                    plan = paused,
                    isGenerating = false,
                    turnStartedAt = null,
                    activity = null,
                    stateBoard = null,
                    onDismiss = {},
                    onContinue = {},
                    onComplete = {},
                    onHidePlan = {},
                    onClearPlan = {},
                    onEditState = { _, _ -> null },
                    onRemoveState = { null },
                    nowMillis = 0L,
                )
            }
        }
    }
}

@PreviewTest
@Preview(
    name = "Chat task pending Chinese",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatTaskPendingReleasePreview() {
    val tree = previewTaskTree()
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession(),
                    agentName = "Infinity Workbench 助手",
                    messages = listOf(
                        previewMessage("task-user", MessageRole.USER, "继续完成发布检查。"),
                        previewMessage(
                            "task-assistant",
                            MessageRole.ASSISTANT,
                            "基础检查已经结束，剩余视觉回归需要确认。",
                            modelId = PREVIEW_CHAT_MODEL_ID,
                        ),
                    ),
                ),
                taskPanel = taskPanelUiState(tree, isGenerating = false),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Chat task generating Chinese",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatTaskGeneratingReleasePreview() {
    val tree = previewTaskTree()
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession(),
                    agentName = "Infinity Workbench 助手",
                    messages = listOf(
                        previewMessage("task-live-user", MessageRole.USER, "继续完成发布检查。"),
                        previewMessage(
                            "task-live-assistant",
                            MessageRole.ASSISTANT,
                            "正在验证任务面板交互与视觉回归……",
                            modelId = PREVIEW_CHAT_MODEL_ID,
                        ),
                    ),
                    isGenerating = true,
                    status = GenerationStatus.RECEIVING,
                    streamingContent = "正在验证任务面板交互与视觉回归……",
                ),
                taskPanel = taskPanelUiState(tree, isGenerating = true),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Chat task pending Chinese font scale 2",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatTaskPendingLargeFontReleasePreview() {
    val tree = previewTaskTree()
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession().copy(options = SessionOptions(fontSize = 22)),
                    agentName = "Infinity Workbench 助手",
                ),
                taskPanel = taskPanelUiState(tree, isGenerating = false),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Chat error font scale 2",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatErrorLargeFontReleasePreview() {
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession(),
                    agentName = "Infinity Workbench Assistant",
                    messages = listOf(
                        previewMessage("user-error", MessageRole.USER, "Continue the release audit."),
                        previewMessage(
                            id = "assistant-error",
                            role = MessageRole.ASSISTANT,
                            content = "",
                            isError = true,
                            errorMessage = GenerationFailureCodec.encode(
                                GenerationFailure.of(GenerationFailureCode.SERVER),
                            ),
                        ),
                    ),
                    status = GenerationStatus.ERROR,
                ),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Provider list Chinese narrow",
    widthDp = 360,
    heightDp = 420,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun providerListChineseNarrowReleasePreview() {
    ReleasePreviewSurface {
        ProviderListScreenContent(
            state = com.promenar.infinity.ui.settings.ProviderListScreenState(
                providers = listOf(
                    com.promenar.infinity.data.model.ProviderListItem(
                        id = "openai",
                        name = "OpenAI",
                        baseUrl = "https://api.promenar.com/v1",
                        protocolType = com.promenar.infinity.data.remote.protocol.ProtocolType.Generic_OpenAI_Compat,
                        hasApiKey = true,
                        enabled = true,
                    ),
                    com.promenar.infinity.data.model.ProviderListItem(
                        id = "anthropic",
                        name = "Anthropic 官方接口（企业账号）",
                        baseUrl = "https://api.anthropic-enterprise-gateway.example.com/v1/messages",
                        protocolType = com.promenar.infinity.data.remote.protocol.ProtocolType.Anthropic_Messages,
                        hasApiKey = false,
                        enabled = false,
                    ),
                ),
            ),
            actions = ProviderListScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Agent skill packs Chinese",
    widthDp = 412,
    heightDp = 640,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun agentSkillPacksReleasePreview() {
    ReleasePreviewSurface {
        Box(modifier = Modifier.padding(16.dp)) {
            // 与视图模型输出一致：固定顺序的内置、用户、项目三组，项目组在设置页为空且不渲染。
            com.promenar.infinity.ui.settings.AgentSkillsSection(
                groups = listOf(
                    com.promenar.infinity.data.skills.SkillSourceGroup(
                        com.promenar.infinity.data.skills.SkillScope.BUILTIN,
                        listOf(
                            releasePreviewSkill(
                                "infinity-workbench",
                                "管理或创建技能、编辑技能文件、管理智能体规则与会话提示词、说明自身能力、运行环境、路径或权限时，先读取本指南。",
                                files = emptyList(),
                                status = com.promenar.infinity.data.skills.SkillStatus.ENABLED,
                            ).copy(
                                id = "builtin:infinity-workbench",
                                scope = com.promenar.infinity.data.skills.SkillScope.BUILTIN,
                                virtualDir = "/.infinity/builtin-skills/infinity-workbench",
                            ),
                        ),
                    ),
                    com.promenar.infinity.data.skills.SkillSourceGroup(
                        com.promenar.infinity.data.skills.SkillScope.USER,
                        listOf(
                            releasePreviewSkill(
                                "release-notes",
                                "根据提交记录与变更文件撰写结构化发行说明，适用于版本发布前整理。",
                                files = listOf("templates/notes.md", "reference/style.md"),
                                status = com.promenar.infinity.data.skills.SkillStatus.ENABLED,
                            ),
                            releasePreviewSkill(
                                "weekly-report",
                                "把工作区中的会议记录整理成周报。",
                                files = emptyList(),
                                status = com.promenar.infinity.data.skills.SkillStatus.DISABLED,
                            ),
                        ),
                    ),
                    com.promenar.infinity.data.skills.SkillSourceGroup(
                        com.promenar.infinity.data.skills.SkillScope.PROJECT,
                        emptyList(),
                    ),
                ),
                actions = com.promenar.infinity.ui.skills.SkillCatalogActions(),
                onSetGroupEnabled = { _, _ -> },
                onImport = {},
                onCreate = {},
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Chat approval write preview Chinese",
    widthDp = 412,
    heightDp = 760,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatApprovalWritePreviewReleasePreview() {
    ReleasePreviewSurface {
        Box(modifier = Modifier.padding(16.dp)) {
            ApprovalCard(
                toolName = "patch_file",
                description = "执行模式：半自动",
                calls = listOf(
                    ApprovalDisplayCall(
                        name = "patch_file",
                        argumentsSummary = "{\"path\":\"/notes/plan.md\"}",
                        riskLabel = "编辑",
                        executionTarget = "[\"path\",\"/notes/plan.md\"]",
                        preview = ApprovalPreview.Preview(
                            ApprovalPreview.Action.EDIT,
                            "/notes/plan.md",
                            "~ L3–4\n  + - [x] 完成工具预算收尾\n  + - [ ] 发布候选验证\n- L9–9",
                        ),
                    ),
                    ApprovalDisplayCall(
                        name = "delete_file",
                        argumentsSummary = "{\"path\":\"/drafts/old.md\"}",
                        riskLabel = "删除",
                        executionTarget = "[\"path\",\"/drafts/old.md\"]",
                        highRisk = true,
                        preview = ApprovalPreview.Preview(ApprovalPreview.Action.DELETE, "/drafts/old.md", null),
                    ),
                ),
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Chat approval tablet English",
    widthDp = 840,
    heightDp = 900,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatApprovalTabletReleasePreview() {
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession(),
                    agentName = "Infinity Workbench Assistant",
                    messages = listOf(
                        previewMessage("user-approval", MessageRole.USER, "Prepare the release notes."),
                    ),
                    approvalRequest = ApprovalRequest(
                        assistantMessageId = "assistant-approval",
                        identityHash = "preview-hash",
                        calls = listOf(
                            ApprovalCallIdentity(
                                toolCallId = "write-release-notes",
                                runtimeToolId = "write_file",
                                toolName = "write_file",
                                argumentsDigest = "digest-write",
                                definitionDigest = "definition-write",
                                requiresApproval = true,
                                argumentsSummary = "{\"path\":\"RELEASE_NOTES.md\"}",
                                risk = "file_write",
                                executionTarget = "[\"path\",\"/RELEASE_NOTES.md\"]",
                            ),
                            ApprovalCallIdentity(
                                toolCallId = "delete-old-draft",
                                runtimeToolId = "delete_file",
                                toolName = "delete_file",
                                argumentsDigest = "digest-delete",
                                definitionDigest = "definition-delete",
                                requiresApproval = true,
                                argumentsSummary = "{\"path\":\"OLD_DRAFT.md\"}",
                                risk = "delete",
                                executionTarget = "[\"path\",\"/OLD_DRAFT.md\"]",
                            ),
                        ),
                        reason = "Writing a release artifact requires approval.",
                    ),
                ),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Chat ready with metadata",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatReadyReleasePreview() {
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession(),
                    agentName = "Infinity Workbench Assistant",
                    messages = listOf(
                        previewMessage(
                            id = "user-ready",
                            role = MessageRole.USER,
                            content = "Check the next release gate list.",
                        ),
                        previewMessage(
                            id = "assistant-ready",
                            role = MessageRole.ASSISTANT,
                            content = "已就绪，我将继续沿用发布清单验证。\n时间与模型元信息将按优先级展示。",
                            modelId = PREVIEW_CHAT_MODEL_ID,
                        ),
                    ),
                ),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Chat long model metadata",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatLongModelMetadataReleasePreview() {
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession(),
                    agentName = "Infinity Workbench Assistant",
                    messages = listOf(
                        previewMessage(
                            id = "assistant-long",
                            role = MessageRole.ASSISTANT,
                            content = "模型名较长时仍应保持时间戳可见。",
                            modelId = PREVIEW_CHAT_MODEL_ID,
                        ),
                    ),
                ),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_LONG_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Chat reasoning metadata",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatReasoningModelReleasePreview() {
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession(),
                    agentName = "Infinity Workbench Assistant",
                    messages = listOf(
                        previewMessage(
                            id = "assistant-reasoning",
                            role = MessageRole.ASSISTANT,
                            content = "推理模型场景测试完成。",
                            reasoning = "先核对安全与备份门禁，再验证后台生成和视觉回归。",
                            modelId = PREVIEW_CHAT_MODEL_ID,
                        ),
                    ),
                    isGenerating = true,
                    status = GenerationStatus.RECEIVING,
                    streamingContent = "先核对安全与备份门禁，再验证后台生成和视觉回归。",
                ),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_REASONING_MODEL_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Chat large font scale 2",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatLargeFontReleasePreview() {
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession().copy(
                        options = SessionOptions(fontSize = 22),
                    ),
                    agentName = "Infinity Workbench Assistant",
                    messages = listOf(
                        previewMessage(
                            id = "assistant-large-font",
                            role = MessageRole.ASSISTANT,
                            content = "2.0x 字号下仍需确认元信息与时间不重叠。",
                            modelId = PREVIEW_CHAT_MODEL_ID,
                        ),
                    ),
                    status = GenerationStatus.IDLE,
                ),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Chat full context documents Chinese",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatFullContextDocumentsChineseReleasePreview() {
    val sentDocument = previewDocument("sent", "项目背景与历史会话.md", 28_640L)
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession(),
                    agentName = "Infinity Workbench 助手",
                    messages = listOf(
                        previewMessage(
                            id = "user-document",
                            role = MessageRole.USER,
                            content = "请完整阅读附件并整理后续行动。",
                            userDocuments = listOf(sentDocument),
                        ),
                    ),
                ),
                draftDocuments = listOf(
                    previewDocument("draft-1", "补充说明.txt", 2_048L),
                    previewDocument("draft-2", "这是一个用于验证超长文件名不会挤压输入栏的参考资料.markdown", 96_420L),
                ),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Chat full context documents English large font",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun chatFullContextDocumentsEnglishLargeFontReleasePreview() {
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession().copy(options = SessionOptions(fontSize = 22)),
                    agentName = "Infinity Workbench Assistant",
                    messages = listOf(
                        previewMessage(
                            id = "user-document-large",
                            role = MessageRole.USER,
                            content = "Use the complete document as context.",
                            userDocuments = listOf(
                                previewDocument("sent-large", "complete-session-export.md", 128_000L),
                            ),
                        ),
                    ),
                ),
                draftDocuments = listOf(
                    previewDocument(
                        "draft-large",
                        "very-long-reference-document-name-for-accessibility-layout-validation.markdown",
                        64_000L,
                    ),
                ),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Attachment actions anchored",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun attachmentActionsAnchoredReleasePreview() {
    AttachmentActionsReleaseScene()
}

@PreviewTest
@Preview(
    name = "Attachment actions font scale 2",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun attachmentActionsLargeFontReleasePreview() {
    AttachmentActionsReleaseScene()
}

@PreviewTest
@Preview(
    name = "Attachment actions Chinese font scale 2",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun attachmentActionsChineseLargeFontReleasePreview() {
    AttachmentActionsReleaseScene()
}

@PreviewTest
@Preview(
    name = "Attachment actions compact viewport",
    widthDp = PHONE_WIDTH_DP,
    heightDp = 520,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun attachmentActionsCompactViewportReleasePreview() {
    AttachmentActionsReleaseScene()
}

@PreviewTest
@Preview(
    name = "Attachment actions RTL",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "ar",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun attachmentActionsRtlReleasePreview() {
    AttachmentActionsReleaseScene()
}

@Composable
private fun AttachmentActionsReleaseScene() {
    ReleasePreviewSurface {
        Box(modifier = Modifier.fillMaxSize()) {
            ChatScreenContent(
                state = ChatScreenState(
                    uiState = ChatUiState(
                        session = previewChatSession(),
                        agentName = "Infinity Workbench Assistant",
                        messages = listOf(
                            previewMessage(
                                id = "attachment-preview",
                                role = MessageRole.ASSISTANT,
                                content = "Attachment actions stay connected to the composer without covering model controls.",
                                modelId = PREVIEW_CHAT_MODEL_ID,
                            )
                        ),
                    ),
                    modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
                ),
                actions = ChatScreenActions(),
                attachmentMenuExpandedState = remember { mutableStateOf(true) },
            )

        }
    }
}

@PreviewTest
@Preview(
    name = "Backup settings English",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun backupSettingsReleasePreview() {
    val viewModel = remember {
        BackupViewModel(
            operations = PreviewBackupOperations,
            settings = PreviewBackupSettings,
            secrets = PreviewSecretStore(),
            restartRequester = BackupRestartRequester {},
            clock = { 0L },
            synchronousIoForTests = true,
        )
    }
    ReleasePreviewSurface {
        BackupSettingsScreen(onNavigateBack = {}, viewModel = viewModel)
    }
}

@PreviewTest
@Preview(
    name = "Theme settings English dark",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun themeSettingsEnglishDarkReleasePreview() {
    ThemeSettingsReleasePreview(dark = true)
}

@PreviewTest
@Preview(
    name = "Theme settings Chinese light",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun themeSettingsChineseLightReleasePreview() {
    ThemeSettingsReleasePreview(dark = false)
}

@PreviewTest
@Preview(
    name = "Theme settings Chinese dark 2x",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
fun themeSettingsChineseDarkLargeTextReleasePreview() {
    ThemeSettingsReleasePreview(dark = true)
}

@PreviewTest
@Preview(
    name = "Theme settings English light 2x",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    fontScale = 2f,
)
@Composable
fun themeSettingsEnglishLightLargeTextReleasePreview() {
    ThemeSettingsReleasePreview(dark = false)
}

@Composable
private fun ThemeSettingsReleasePreview(dark: Boolean) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        ThemeScreenContent(
            state = ThemeUiState(
                preferences = InfinityThemePreferences(
                    mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
                    colorSource = InfinityColorSource.INFINITY,
                ),
                dynamicColorAvailable = true,
            ),
            onModeSelect = {},
            onColorSourceSelect = {},
            onNavigateBack = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "Agent hub English",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun agentHubReleasePreview() {
    ReleasePreviewSurface {
        AgentHubScreenContent(
            state = AgentHubScreenState(
                displayAgents = listOf(
                    AgentDisplayItem(
                        agent = Agent(
                            id = "agent-coder",
                            name = "Coding Expert",
                            description = "Full-stack development and architecture design",
                            icon = "A",
                            color = "#5B8DEF",
                            isPinned = true,
                        ),
                        title = "Coding Expert",
                        subtitle = "Full-stack development and architecture design",
                    ),
                    AgentDisplayItem(
                        agent = Agent(
                            id = "agent-writer",
                            name = "Creative Writing",
                            description = "Literary creation, translation and polishing",
                            icon = "A",
                            color = "#C0C1FF",
                        ),
                        title = "Creative Writing",
                        subtitle = "Literary creation, translation and polishing",
                    ),
                    AgentDisplayItem(
                        agent = Agent(
                            id = "agent-default",
                            name = "Infinity Workbench Assistant",
                            description = "General AI assistant with streaming chat and knowledge retrieval",
                            icon = "A",
                            color = "#7AE582",
                        ),
                        title = "Infinity Workbench Assistant",
                        subtitle = "General AI assistant with streaming chat and knowledge retrieval",
                    ),
                ),
                // 预览时间固定在过去的年份，时间列稳定显示为“年/月/日”。
                sessionsByAgent = mapOf(
                    "agent-coder" to listOf(
                        Session(id = "hub-s1", agentId = "agent-coder", title = "Kotlin coroutine cancellation", updatedAt = 1_750_075_200_000L),
                        Session(id = "hub-s2", agentId = "agent-coder", title = "Release checklist for 0.2.2", isPinned = true, updatedAt = 1_749_988_800_000L),
                    ),
                    "agent-writer" to listOf(
                        Session(id = "hub-s3", agentId = "agent-writer", title = "Polish the product announcement", updatedAt = 1_749_902_400_000L),
                    ),
                ),
                sessionActivity = mapOf(
                    "hub-s1" to com.promenar.infinity.ui.hub.HubSessionActivity.GENERATING,
                    "hub-s3" to com.promenar.infinity.ui.hub.HubSessionActivity.AWAITING_APPROVAL,
                ),
            ),
            actions = AgentHubScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Agent hub populated Chinese large font",
    widthDp = 360,
    heightDp = 640,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun agentHubPopulatedLargeFontReleasePreview() {
    ReleasePreviewSurface {
        AgentHubScreenContent(
            state = AgentHubScreenState(
                displayAgents = listOf(
                    AgentDisplayItem(
                        agent = Agent(
                            id = "agent-long-title",
                            name = "长名称代码架构与发布助手",
                            description = "负责全栈开发、架构审查、测试与发布验收",
                            icon = "A",
                            color = "#5B8DEF",
                            isPinned = true,
                        ),
                        title = "长名称代码架构与发布助手",
                        subtitle = "负责全栈开发、架构审查、测试与发布验收",
                    ),
                    AgentDisplayItem(
                        agent = Agent(
                            id = "agent-no-subtitle",
                            name = "破限助手",
                            description = "",
                            icon = "A",
                            color = "#C0C1FF",
                        ),
                        title = "破限助手",
                        subtitle = "",
                    ),
                ),
            ),
            actions = AgentHubScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Agent hub empty Chinese large font",
    widthDp = 360,
    heightDp = 640,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun agentHubEmptyLargeFontReleasePreview() {
    ReleasePreviewSurface {
        AgentHubScreenContent(
            state = AgentHubScreenState(displayAgents = emptyList()),
            actions = AgentHubScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Agent hub expanded long titles English",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun agentHubExpandedLongTitlesReleasePreview() {
    ReleasePreviewSurface {
        AgentHubScreenContent(
            state = AgentHubScreenState(
                displayAgents = listOf(
                    AgentDisplayItem(
                        agent = Agent(id = "agent-preview", name = "Release and architecture assistant with a long display name", icon = "A", color = "#5B8DEF"),
                        title = "Release and architecture assistant with a long display name",
                        subtitle = "Reviews architecture and release readiness",
                    ),
                ),
                sessionsByAgent = mapOf(
                    "agent-preview" to listOf(
                        Session(
                            id = "session-long-title",
                            agentId = "agent-preview",
                            title = "A very long conversation title that must remain readable without covering its timestamp",
                            isPinned = true,
                            updatedAt = 1_750_075_200_000L,
                        ),
                        Session(
                            id = "session-second-title",
                            agentId = "agent-preview",
                            title = "Second long session title for the grouped list layout",
                            updatedAt = 1_749_988_800_000L,
                        ),
                    ),
                ),
            ),
            actions = AgentHubScreenActions(),
            initialExpandedAgentId = "agent-preview",
        )
    }
}

@PreviewTest
@Preview(
    name = "Agent hub expanded empty agent Chinese font scale 2",
    widthDp = 360,
    heightDp = 1280,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun agentHubExpandedEmptyAgentLargeFontReleasePreview() {
    ReleasePreviewSurface {
        AgentHubScreenContent(
            state = AgentHubScreenState(
                displayAgents = listOf(
                    AgentDisplayItem(
                        agent = Agent(id = "agent-release", name = "发布与架构助手", icon = "A", color = "#5B8DEF"),
                        title = "发布与架构助手",
                        subtitle = "",
                    ),
                    AgentDisplayItem(
                        agent = Agent(id = "agent-empty", name = "破限助手", icon = "A", color = "#C0C1FF"),
                        title = "破限助手",
                        subtitle = "",
                    ),
                ),
                sessionsByAgent = mapOf(
                    "agent-release" to listOf(
                        Session(
                            id = "session-large-font",
                            agentId = "agent-release",
                            title = "长名称会话在大字体下仍应完整保持可点击和可读",
                            updatedAt = 1_750_075_200_000L,
                        ),
                    ),
                ),
            ),
            actions = AgentHubScreenActions(),
            initialExpandedAgentId = "agent-empty",
        )
    }
}

@PreviewTest
@Preview(
    name = "Model picker 120 models dark",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun modelPickerLargeCatalogDarkReleasePreview() {
    ModelPickerReleasePreview(dark = true) {
        ModelPickerSheetContent(
            title = stringResource(R.string.common_model_picker_title),
            searchQuery = "",
            onSearchQueryChange = {},
            currentModelId = PREVIEW_MODEL_SELECTIONS.first().selectionId,
            models = PREVIEW_MODEL_SELECTIONS,
            onSelect = { _, _ -> },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Model picker long names light large font",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    fontScale = 2f,
)
@Composable
fun modelPickerLongNamesLightLargeFontReleasePreview() {
    ModelPickerReleasePreview(dark = false) {
        ModelPickerSheetContent(
            title = stringResource(R.string.common_model_picker_title),
            searchQuery = "",
            onSearchQueryChange = {},
            currentModelId = PREVIEW_MODEL_SELECTIONS.first().selectionId,
            models = PREVIEW_MODEL_SELECTIONS.take(8),
            onSelect = { _, _ -> },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Provider models populated English",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun providerModelsReleasePreviewEnglish() {
    ReleasePreviewSurface {
        ProviderModelsScreenContent(
            state = ProviderModelsScreenState(
                providerName = "Cloud Provider with Long Enterprise Name",
                providerId = "provider-preview-alpha",
                isFetching = false,
                syncNotice = ModelSyncNotice.synced(newCount = 1, updatedCount = 0),
                modelTestStates = mapOf(
                    "provider-preview-alpha::qwen3-72b-thinking-multimodal" to ModelTestState.Success(132),
                ),
                models = listOf(
                    ModelInfo(
                        name = "Longform Multimodal Reasoning Model Very Long Name with Stable Prefix",
                        id = "provider-preview-alpha::qwen3-72b-thinking-multimodal",
                        description = "preview",
                        enabled = true,
                        type = "reasoning",
                        contextLength = 65536,
                        capabilities = listOf("chat", "reasoning", "vision", "internet"),
                        providerId = "provider-preview-alpha",
                        providerName = "Cloud Alpha",
                        remoteModelId = "qwen3-72b-thinking-multimodal",
                        maxOutputTokens = 8192,
                        knowledgeCutoff = "20250201",
                    ),
                ),
            ),
            actions = ProviderModelsScreenActions(
                onRefresh = {},
                onAdd = { _, _ -> true },
                onDisableAll = {},
                onDeleteAll = {},
                onUpdate = {},
                onToggle = {},
                onTest = {},
                onCancelTest = {},
                onDelete = {},
                onClearNotice = {},
            ),
            onNavigateBack = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "Provider models chinese large font",
    widthDp = 840,
    heightDp = 900,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun providerModelsReleasePreviewChineseLargeFont() {
    ReleasePreviewSurface {
        ProviderModelsScreenContent(
            state = ProviderModelsScreenState(
                providerName = "腾讯云-企业模型服务入口（稳定版本）",
                providerId = "provider-preview-beta",
                isFetching = false,
                syncNotice = null,
                modelTestStates = mapOf(
                    "provider-preview-beta::qwen3-235b-long-name-with-capabilities" to ModelTestState.Success(298),
                ),
                models = listOf(
                    ModelInfo(
                        name = "超长模型名称示例：多模态推理与结构化输出协同增强版",
                        id = "provider-preview-beta::qwen3-235b-long-name-with-capabilities",
                        description = "preview",
                        enabled = true,
                        type = "chat",
                        contextLength = 131072,
                        capabilities = listOf("chat", "structuredoutput", "computeruse", "audioinput", "audiooutput"),
                        providerId = "provider-preview-beta",
                        providerName = "Cloud Beta",
                        remoteModelId = "qwen3-235b-long-name-with-capabilities",
                        maxOutputTokens = 12000,
                        knowledgeCutoff = "20240201",
                    ),
                ),
            ),
            actions = ProviderModelsScreenActions(
                onRefresh = {},
                onAdd = { _, _ -> true },
                onDisableAll = {},
                onDeleteAll = {},
                onUpdate = {},
                onToggle = {},
                onTest = {},
                onCancelTest = {},
                onDelete = {},
                onClearNotice = {},
            ),
            onNavigateBack = {},
        )
    }
}

@Preview(
    name = "Provider model editor Chinese large font",
    widthDp = 840,
    heightDp = 900,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
@PreviewTest
fun providerModelEditorReleasePreviewChineseLargeFont() {
    ReleasePreviewSurface {
        ModelEditorScreen(
            model = ModelInfo(
                name = "超长模型名称示例：多模态推理与结构化输出协同增强版",
                id = "provider-preview-beta::qwen3-235b-long-name-with-capabilities",
                description = "preview",
                enabled = true,
                type = "chat",
                contextLength = 131072,
                capabilities = listOf("chat", "structuredoutput", "computeruse", "audioinput", "audiooutput"),
                providerId = "provider-preview-beta",
                providerName = "Cloud Beta",
                remoteModelId = "qwen3-235b-long-name-with-capabilities",
                maxOutputTokens = 12000,
                knowledgeCutoff = "20240201",
                canonicalModelId = "qwen/qwen3-235b",
                userEditedFields = setOf("name", "capabilities"),
            ),
            testState = ModelTestState.Success(298),
            onClose = {},
            onUpdate = {},
            onTest = {},
            onCancel = {},
            onDelete = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG home documents English",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragHomeDocumentsReleasePreviewEnglish() {
    ReleasePreviewSurface {
        RagHomeScreenContent(
            state = RagHomeScreenState(
                currentTab = PortalTab.DOCUMENTS,
                searchQuery = "",
                selectedIds = mutableListOf(),
                workspaceRootUuid = "preview-root",
                folders = emptyList(),
                folderStats = emptyMap(),
                stats = RagStats(documentCount = 2, memoryCount = 1, graphEntityCount = 8),
                memoryVectors = emptyList(),
                isIndexing = false,
                indexingProgress = 1f,
                indexingNotice = UiStatusNotice(NoticeSeverity.Success, IndexingNotice.CODE_COMPLETED),
                canRetryLastFailedIndex = false,
                isRetryingLastFailedIndex = false,
                indexingFileIds = emptySet(),
                kgExtractionStates = emptyMap(),
            ),
            actions = RagHomeScreenActions(),
            documentsContent = { modifier, selectedIds, requestDelete ->
                PreviewRagDocuments(modifier, selectedIds, requestDelete)
            },
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG home documents landscape",
    widthDp = LANDSCAPE_WIDTH_DP,
    heightDp = LANDSCAPE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragHomeDocumentsLandscapeReleasePreview() {
    ReleasePreviewSurface {
        RagHomeScreenContent(
            state = RagHomeScreenState(
                currentTab = PortalTab.DOCUMENTS,
                searchQuery = "",
                selectedIds = mutableListOf(),
                workspaceRootUuid = "preview-root",
                folders = emptyList(),
                folderStats = emptyMap(),
                stats = RagStats(documentCount = 2, memoryCount = 1, graphEntityCount = 8),
                memoryVectors = emptyList(),
                isIndexing = false,
                indexingProgress = 0f,
                canRetryLastFailedIndex = false,
                isRetryingLastFailedIndex = false,
                indexingFileIds = emptySet(),
                kgExtractionStates = emptyMap(),
            ),
            actions = RagHomeScreenActions(),
            documentsContent = { modifier, selectedIds, requestDelete ->
                PreviewRagDocuments(modifier, selectedIds, requestDelete)
            },
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG home selected documents phone",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragHomeSelectedDocumentsPhoneReleasePreview() {
    ReleasePreviewSurface {
        RagHomeScreenContent(
            state = RagHomeScreenState(
                currentTab = PortalTab.DOCUMENTS,
                searchQuery = "",
                selectedIds = mutableListOf("preview-doc-1", "preview-doc-2"),
                workspaceRootUuid = "preview-root",
                folders = emptyList(),
                folderStats = emptyMap(),
                stats = RagStats(documentCount = 2, memoryCount = 1, graphEntityCount = 8),
                memoryVectors = emptyList(),
                isIndexing = false,
                indexingProgress = 0f,
                canRetryLastFailedIndex = false,
                isRetryingLastFailedIndex = false,
                indexingFileIds = emptySet(),
                kgExtractionStates = emptyMap(),
            ),
            actions = RagHomeScreenActions(),
            documentsContent = { modifier, selectedIds, requestDelete ->
                PreviewRagDocuments(modifier, selectedIds, requestDelete)
            },
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG folder content phone",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragFolderContentPhoneReleasePreview() {
    ReleasePreviewSurface {
        RagFolderScreenContent(
            state = previewRagFolderState(mutableStateListOf()),
            actions = RagFolderScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG folder selected large font",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragFolderSelectedLargeFontReleasePreview() {
    ReleasePreviewSurface {
        RagFolderScreenContent(
            state = previewRagFolderState(mutableStateListOf("preview-folder-doc-1", "preview-folder-doc-2")),
            actions = RagFolderScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG folder content landscape",
    widthDp = LANDSCAPE_WIDTH_DP,
    heightDp = LANDSCAPE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragFolderContentLandscapeReleasePreview() {
    ReleasePreviewSurface {
        RagFolderScreenContent(
            state = previewRagFolderState(mutableStateListOf()),
            actions = RagFolderScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG details retrieved phone",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragDetailsRetrievedPhoneReleasePreview() {
    ReleasePreviewSurface {
        RagDetailsSheetContent(
            references = PREVIEW_RAG_DETAILS_REFERENCES,
            citations = emptyList(),
            kgPaths = emptyList(),
            trace = PREVIEW_RAG_TRACE,
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG card outcomes Chinese",
    widthDp = 360,
    heightDp = 320,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragCardOutcomesReleasePreview() {
    ReleasePreviewSurface {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            com.promenar.infinity.ui.chat.RagProgressCard(
                phases = emptyList(),
                references = PREVIEW_RAG_DETAILS_REFERENCES,
                isComplete = true,
                trace = PREVIEW_RAG_TRACE,
            )
            com.promenar.infinity.ui.chat.RagProgressCard(
                phases = emptyList(),
                references = emptyList(),
                isComplete = true,
                trace = PREVIEW_RAG_TRACE.copy(
                    outcome = com.promenar.infinity.data.model.RagRetrievalTrace.OUTCOME_EMPTY,
                    selectedCount = 0,
                ),
            )
            com.promenar.infinity.ui.chat.RagProgressCard(
                phases = emptyList(),
                references = emptyList(),
                isComplete = true,
                trace = com.promenar.infinity.data.model.RagRetrievalTrace(
                    outcome = com.promenar.infinity.data.model.RagRetrievalTrace.OUTCOME_FAILED,
                ),
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "RAG details web large font",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragDetailsWebLargeFontReleasePreview() {
    ReleasePreviewSurface {
        RagDetailsSheetContent(
            references = emptyList(),
            citations = PREVIEW_RAG_DETAILS_CITATIONS,
            kgPaths = emptyList(),
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG details KG long landscape",
    widthDp = LANDSCAPE_WIDTH_DP,
    heightDp = LANDSCAPE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragDetailsKgLongLandscapeReleasePreview() {
    ReleasePreviewSurface {
        RagDetailsSheetContent(
            references = emptyList(),
            citations = emptyList(),
            kgPaths = PREVIEW_RAG_DETAILS_KG_PATHS,
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG FilesPanel deep tree Chinese large font",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragFilesPanelDeepTreeLargeFontReleasePreview() {
    ReleasePreviewSurface {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            FilesPanel(
                workspaceRootUuid = PREVIEW_RAG_ROOT_ID,
                workspaceRepo = PREVIEW_RAG_REPOSITORY,
                rootFiles = listOf(PREVIEW_RAG_PRODUCT_FOLDER),
                initiallyExpandedIds = setOf(PREVIEW_RAG_DEEP_FOLDER.uuid),
                initialChildrenByParent = PREVIEW_RAG_CHILDREN,
                onReindex = {},
                onDelete = { _, onComplete ->
                    onComplete(com.promenar.infinity.ui.chat.components.FileBatchOperationResult(emptyList()))
                },
                onRename = { _, _ -> },
                onMove = { _, _ -> },
                onExtractKG = {},
                onViewKG = {},
                onCopy = {},
                onFolderClick = { _, _ -> },
                onFileClick = {},
                nowMillis = PREVIEW_RAG_NOW_MILLIS,
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "RAG home memory expanded phone",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragHomeMemoryExpandedPhoneReleasePreview() {
    ReleasePreviewSurface {
        RagHomeScreenContent(
            state = previewMemoryState(PREVIEW_MEMORY_VECTORS),
            actions = RagHomeScreenActions(),
            documentsContent = { modifier, _, _ -> Box(modifier) },
            initiallyExpandedMemoryId = PREVIEW_MEMORY_ID,
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG home memory content Chinese large font",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragHomeMemoryContentLargeFontReleasePreview() {
    ReleasePreviewSurface {
        RagHomeScreenContent(
            state = previewMemoryState(PREVIEW_MEMORY_VECTORS),
            actions = RagHomeScreenActions(),
            documentsContent = { modifier, _, _ -> Box(modifier) },
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG home memory delete confirmation phone",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragHomeMemoryDeleteConfirmationReleasePreview() {
    ReleasePreviewSurface {
        RagHomeScreenContent(
            state = previewMemoryState(PREVIEW_MEMORY_VECTORS),
            actions = RagHomeScreenActions(),
            documentsContent = { modifier, _, _ -> Box(modifier) },
            initialMemoryDeleteTargetId = PREVIEW_MEMORY_ID,
        )
    }
}

@PreviewTest
@Preview(
    name = "RAG home memory Chinese large font",
    widthDp = 840,
    heightDp = 900,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragHomeMemoryChineseLargeFontReleasePreview() {
    ReleasePreviewSurface {
        RagHomeScreenContent(
            state = RagHomeScreenState(
                currentTab = PortalTab.MEMORY,
                searchQuery = "",
                selectedIds = mutableListOf(),
                workspaceRootUuid = "preview-root",
                folders = emptyList(),
                folderStats = emptyMap(),
                stats = RagStats(documentCount = 2, memoryCount = 0, graphEntityCount = 8),
                memoryVectors = emptyList(),
                isIndexing = false,
                indexingProgress = 0f,
                canRetryLastFailedIndex = false,
                isRetryingLastFailedIndex = false,
                indexingFileIds = emptySet(),
                kgExtractionStates = emptyMap(),
            ),
            actions = RagHomeScreenActions(),
            documentsContent = { modifier, _, _ -> Box(modifier) },
        )
    }
}

@PreviewTest
@Preview(name = "RAG status error Chinese", widthDp = 180, heightDp = 180, locale = "zh-rCN")
@Composable
fun ragStatusErrorChineseReleasePreview() {
    ReleasePreviewSurface {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            com.promenar.infinity.ui.rag.RagStatusFab(
                state = previewMemoryState(emptyList()).copy(canRetryLastFailedIndex = true),
                actions = RagHomeScreenActions(),
            )
        }
    }
}

@PreviewTest
@Preview(name = "RAG status indexing English", widthDp = 180, heightDp = 180, locale = "en")
@Composable
fun ragStatusIndexingEnglishReleasePreview() {
    ReleasePreviewSurface {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            com.promenar.infinity.ui.rag.RagStatusFab(
                state = previewMemoryState(emptyList()).copy(isIndexing = true, indexingProgress = 0.4f),
                actions = RagHomeScreenActions(),
            )
        }
    }
}

private fun previewMemoryState(memoryVectors: List<MemoryVectorRecord>) = RagHomeScreenState(
    currentTab = PortalTab.MEMORY,
    searchQuery = "",
    selectedIds = mutableListOf(),
    workspaceRootUuid = "preview-root",
    folders = emptyList(),
    folderStats = emptyMap(),
    stats = RagStats(documentCount = 2, memoryCount = memoryVectors.size, graphEntityCount = 8),
    memoryVectors = memoryVectors,
    isIndexing = false,
    indexingProgress = 0f,
    canRetryLastFailedIndex = false,
    isRetryingLastFailedIndex = false,
    indexingFileIds = emptySet(),
    kgExtractionStates = emptyMap(),
)

private fun previewRagFolderState(selectedIds: MutableList<String>) = RagFolderScreenState(
    title = "Release Evidence",
    workspaceRootUuid = PREVIEW_RAG_ROOT_ID,
    documents = listOf(
        previewRagFile(
            uuid = "preview-folder-doc-1",
            parentUuid = "preview-folder",
            name = "Release-readiness-checklist.md",
            mimeType = "text/markdown",
            sizeBytes = 24_576L,
            vectorizedAt = PREVIEW_RAG_NOW_MILLIS - 3_600_000L,
        ),
        previewRagFile(
            uuid = "preview-folder-doc-2",
            parentUuid = "preview-folder",
            name = "Android-device-matrix-and-accessibility-results.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1_572_864L,
        ),
    ),
    folders = emptyList(),
    currentFolderId = "preview-folder",
    selectedIds = selectedIds,
    contentState = RagFolderContentState.Content,
)

@PreviewTest
@Preview(
    name = "DocEditor loading English phone",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun docEditorLoadingEnglishPhoneReleasePreview() {
    ReleasePreviewSurface {
        DocEditorScreenContent(
            state = DocEditorScreenState(
                editorState = DocEditorUiState(
                    phase = DocEditorPhase.Loading,
                    workspaceRootUuid = PREVIEW_DOC_EDITOR_ROOT_ID,
                    documentId = PREVIEW_DOC_EDITOR_DOCUMENT_ID,
                    documentEpoch = PREVIEW_DOC_EDITOR_EPOCH,
                ),
            ),
            actions = DocEditorScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "DocEditor load error Chinese large font",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun docEditorLoadErrorChineseLargeFontReleasePreview() {
    ReleasePreviewSurface {
        DocEditorScreenContent(
            state = DocEditorScreenState(
                editorState = DocEditorUiState(
                    phase = DocEditorPhase.LoadError,
                    workspaceRootUuid = PREVIEW_DOC_EDITOR_ROOT_ID,
                    documentId = PREVIEW_DOC_EDITOR_DOCUMENT_ID,
                    documentEpoch = PREVIEW_DOC_EDITOR_EPOCH,
                    failureCode = DocEditorFailureCode.LoadFailed,
                ),
            ),
            actions = DocEditorScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "DocEditor ready edit dirty English phone",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun docEditorReadyEditDirtyEnglishPhoneReleasePreview() {
    ReleasePreviewSurface {
        DocEditorScreenContent(
            state = DocEditorScreenState(
                editorState = previewDocEditorState(
                    title = "Infinity Workbench release checklist — edited",
                    content = PREVIEW_DOC_EDITOR_EDIT_CONTENT,
                    titleDirty = true,
                    contentDirty = true,
                ),
                viewMode = DocEditorViewMode.EDIT,
            ),
            actions = DocEditorScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "DocEditor index pending Chinese phone",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun docEditorIndexPendingChinesePhoneReleasePreview() {
    ReleasePreviewSurface {
        DocEditorScreenContent(
            state = DocEditorScreenState(
                editorState = previewDocEditorState(
                    title = "发行检查清单",
                    content = PREVIEW_DOC_EDITOR_CONFLICT_CONTENT,
                ).copy(
                    indexPendingTargets = listOf(
                        RenameIndexTarget(
                            fileUuid = PREVIEW_DOC_EDITOR_DOCUMENT_ID,
                            targetHash = PREVIEW_DOC_EDITOR_HASH,
                            targetEpoch = PREVIEW_DOC_EDITOR_EPOCH,
                        ),
                    ),
                    indexQueueFailed = true,
                ),
                viewMode = DocEditorViewMode.EDIT,
            ),
            actions = DocEditorScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "DocEditor save conflict Chinese large font",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun docEditorSaveConflictChineseLargeFontReleasePreview() {
    ReleasePreviewSurface {
        DocEditorScreenContent(
            state = DocEditorScreenState(
                editorState = previewDocEditorState(
                    phase = DocEditorPhase.SaveConflict,
                    title = "发行检查清单（本地修改）",
                    content = PREVIEW_DOC_EDITOR_CONFLICT_CONTENT,
                    titleDirty = true,
                    contentDirty = true,
                    failureCode = DocEditorFailureCode.ContentConflict,
                    conflictCurrentHash = PREVIEW_DOC_EDITOR_CONFLICT_HASH,
                ),
                viewMode = DocEditorViewMode.EDIT,
            ),
            actions = DocEditorScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "DocEditor rich markdown preview English",
    widthDp = 412,
    heightDp = 892,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun docEditorRichMarkdownPreviewEnglishReleasePreview() {
    ReleasePreviewSurface {
        DocEditorScreenContent(
            state = DocEditorScreenState(
                editorState = previewDocEditorState(
                    title = "Release readiness notes",
                    content = PREVIEW_DOC_EDITOR_RICH_MARKDOWN,
                    sizeBytes = 768L,
                ),
                viewMode = DocEditorViewMode.PREVIEW,
            ),
            actions = DocEditorScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "DocEditor rich markdown preview English light",
    widthDp = 412,
    heightDp = 892,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun docEditorRichMarkdownPreviewEnglishLightReleasePreview() {
    ReleasePreviewSurface(darkTheme = false) {
        DocEditorScreenContent(
            state = DocEditorScreenState(
                editorState = previewDocEditorState(
                    title = "Release readiness notes",
                    content = PREVIEW_DOC_EDITOR_RICH_MARKDOWN,
                    sizeBytes = 768L,
                ),
                viewMode = DocEditorViewMode.PREVIEW,
            ),
            actions = DocEditorScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "DocEditor split English tablet",
    widthDp = 840,
    heightDp = 900,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun docEditorSplitEnglishTabletReleasePreview() {
    ReleasePreviewSurface {
        DocEditorScreenContent(
            state = DocEditorScreenState(
                editorState = previewDocEditorState(
                    title = "Architecture decision record",
                    content = PREVIEW_DOC_EDITOR_RICH_MARKDOWN,
                    sizeBytes = 768L,
                ),
                viewMode = DocEditorViewMode.SPLIT,
            ),
            actions = DocEditorScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "DocEditor large file Chinese tablet",
    widthDp = 840,
    heightDp = 900,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun docEditorLargeFileChineseTabletReleasePreview() {
    ReleasePreviewSurface {
        DocEditorScreenContent(
            state = DocEditorScreenState(
                editorState = previewDocEditorState(
                    title = "大型知识库归档.md",
                    content = "",
                    sizeBytes = 1_048_577L,
                    contentAccess = DocEditorContentAccess.MetadataOnly,
                ),
            ),
            actions = DocEditorScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "DocEditor unsaved discard English phone",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun docEditorUnsavedDiscardEnglishPhoneReleasePreview() {
    ReleasePreviewSurface {
        DocEditorScreenContent(
            state = DocEditorScreenState(
                editorState = previewDocEditorState(
                    title = "Unsaved release notes",
                    content = PREVIEW_DOC_EDITOR_EDIT_CONTENT,
                    titleDirty = true,
                    contentDirty = true,
                ),
                viewMode = DocEditorViewMode.EDIT,
                confirmation = DocEditorConfirmation.DiscardChanges,
            ),
            actions = DocEditorScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Resource Explorer files import English",
    widthDp = 412,
    heightDp = 892,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun resourceExplorerFilesImportEnglishReleasePreview() {
    val files = listOf(PREVIEW_RESOURCE_FILE_LONG, PREVIEW_RESOURCE_FILE_SECONDARY)
    ResourceExplorerReleasePreviewContainer {
        ResourceExplorerSheetContent(
            state = ResourceExplorerSheetState(
                workspaceReady = true,
                importItems = PREVIEW_RESOURCE_IMPORT_RESULTS,
            ),
            actions = ResourceExplorerSheetActions(),
            filesContent = {
                FilesPanel(
                    workspaceRootUuid = PREVIEW_RESOURCE_ROOT_ID,
                    workspaceRepo = PREVIEW_RESOURCE_REPOSITORY,
                    rootFiles = files,
                    nowMillis = PREVIEW_RESOURCE_NOW_MILLIS,
                )
            },
        )
    }
}

@PreviewTest
@Preview(
    name = "Resource Explorer empty Chinese",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun resourceExplorerEmptyChineseReleasePreview() {
    ResourceExplorerReleasePreviewContainer {
        ResourceExplorerSheetContent(
            state = ResourceExplorerSheetState(
                workspaceReady = true,
            ),
            actions = ResourceExplorerSheetActions(),
            filesContent = {
                FilesPanel(
                    workspaceRootUuid = PREVIEW_RESOURCE_ROOT_ID,
                    workspaceRepo = PREVIEW_RESOURCE_REPOSITORY,
                    rootFiles = emptyList(),
                    nowMillis = PREVIEW_RESOURCE_NOW_MILLIS,
                    onImport = {},
                )
            },
        )
    }
}

@Composable
private fun PreviewRagDocuments(
    modifier: Modifier,
    selectedIds: MutableList<String>,
    requestDelete: (Collection<String>, (com.promenar.infinity.ui.chat.components.FileBatchOperationResult) -> Unit) -> Unit,
) {
    Box(modifier = modifier.padding(top = 4.dp)) {
        FilesPanel(
            workspaceRootUuid = PREVIEW_RAG_ROOT_ID,
            workspaceRepo = PREVIEW_RAG_REPOSITORY,
            rootFiles = PREVIEW_RAG_ROOT_FILES,
            allowTreeExpansion = false,
            onReindex = {},
            onDelete = requestDelete,
            onRename = { _, _ -> },
            onMove = { _, _ -> },
            onExtractKG = {},
            onViewKG = {},
            onCopy = {},
            externalSelectedIds = selectedIds,
            showSelectionOverlay = false,
            onFolderClick = { _, _ -> },
            onFileClick = {},
            nowMillis = PREVIEW_RAG_NOW_MILLIS,
        )
    }
}

@PreviewTest
@Preview(
    name = "User settings app Chinese tablet",
    widthDp = 840,
    heightDp = 900,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun userSettingsAppChineseTabletReleasePreview() {
    ReleasePreviewSurface {
        UserSettingsHomeScreenContent(
            state = UserSettingsHomeScreenState(
                userName = "黎明",
                localInferenceAvailable = false,
            ),
            actions = UserSettingsHomeScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "User settings app Chinese phone",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun userSettingsAppChinesePhoneReleasePreview() {
    ReleasePreviewSurface {
        UserSettingsHomeScreenContent(
            state = UserSettingsHomeScreenState(
                userName = "黎明",
                localInferenceAvailable = false,
            ),
            actions = UserSettingsHomeScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "User settings integrated navigation Chinese phone",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun userSettingsIntegratedNavigationChinesePhoneReleasePreview() {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = InfinityThemeMode.DARK,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        AdaptiveNavigationSurface(
            expanded = false,
            selectedTab = AppTab.SETTINGS,
            onTabSelected = {},
        ) {
            UserSettingsHomeScreenContent(
                state = UserSettingsHomeScreenState(
                    userName = "黎明",
                    localInferenceAvailable = false,
                ),
                actions = UserSettingsHomeScreenActions(),
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "User settings app Chinese compact large font",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun userSettingsAppChineseCompactLargeFontReleasePreview() {
    ReleasePreviewSurface {
        UserSettingsHomeScreenContent(
            state = UserSettingsHomeScreenState(
                userName = "黎明",
                localInferenceAvailable = false,
            ),
            actions = UserSettingsHomeScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "User settings provider English large font",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun userSettingsProviderLargeFontReleasePreview() {
    ReleasePreviewSurface {
        ProviderListScreenContent(
            state = ProviderListScreenState(
                providers = listOf(
                    ProviderListItem(
                        id = "provider-openai-internal",
                        name = "OpenAI Compatible Internal Aggregator with very long enterprise label",
                        baseUrl = "https://internal-aggregator.openai-proxy.example.com/api/v1/openai/compatible",
                        protocolType = ProtocolType.Generic_OpenAI_Compat,
                        hasApiKey = true,
                        enabled = true,
                    ),
                    ProviderListItem(
                        id = "provider-vertex-production",
                        name = "Google Vertex AI Production Workspace for regional deployments",
                        baseUrl = "https://generativelanguage.googleapis.com/v1beta/projects/infinity-production",
                        protocolType = ProtocolType.Google_VertexAI,
                        hasVertexCredentials = false,
                        enabled = false,
                    ),
                ),
                unsupportedProviders = listOf(
                    UnsupportedProviderListItem(
                        id = "provider-historical-unsupported",
                        name = "Historical provider retained for safe removal",
                        rawProtocolId = "Future_Unknown_Protocol",
                        enabled = false,
                        hasApiKey = true,
                        hasVertexCredentials = false,
                    ),
                ),
            ),
            actions = ProviderListScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "User settings provider empty Chinese large font",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun userSettingsProviderEmptyChineseLargeFontReleasePreview() {
    ReleasePreviewSurface {
        ProviderListScreenContent(
            state = ProviderListScreenState(
                providers = emptyList(),
            ),
            actions = ProviderListScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "User settings provider empty Chinese landscape large font",
    widthDp = 800,
    heightDp = 360,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun userSettingsProviderEmptyChineseLandscapeLargeFontReleasePreview() {
    ReleasePreviewSurface {
        ProviderListScreenContent(
            state = ProviderListScreenState(
                providers = emptyList(),
            ),
            actions = ProviderListScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Default models Chinese phone",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun defaultModelsChinesePhoneReleasePreview() {
    ReleasePreviewSurface {
        DefaultModelsScreenContent(
            state = previewDefaultModelsState(),
            actions = DefaultModelsScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Default models Chinese large font",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun defaultModelsChineseLargeFontReleasePreview() {
    ReleasePreviewSurface {
        DefaultModelsScreenContent(
            state = previewDefaultModelsState(),
            actions = DefaultModelsScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Default models English landscape",
    widthDp = LANDSCAPE_WIDTH_DP,
    heightDp = LANDSCAPE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun defaultModelsEnglishLandscapeReleasePreview() {
    ReleasePreviewSurface {
        DefaultModelsScreenContent(
            state = previewDefaultModelsState(),
            actions = DefaultModelsScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Default models Chinese tablet",
    widthDp = 840,
    heightDp = 900,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun defaultModelsChineseTabletReleasePreview() {
    ReleasePreviewSurface {
        DefaultModelsScreenContent(
            state = previewDefaultModelsState(),
            actions = DefaultModelsScreenActions(),
        )
    }
}

private fun previewDefaultModelsState() = DefaultModelsScreenState(
    summaryModelName = "Gemini 3.5 Flash",
    imageModelName = "Gemini 3.1 Flash Lite Image",
    embeddingModelName = "BAAI/bge-m3",
    rerankModelName = "BAAI/bge-reranker-v2-m3",
)

@PreviewTest
@Preview(
    name = "Empty chat English landscape",
    widthDp = LANDSCAPE_WIDTH_DP,
    heightDp = LANDSCAPE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun emptyChatEnglishLandscapeReleasePreview() {
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession(),
                    agentName = "Infinity Workbench Assistant",
                ),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Streaming chat Chinese landscape",
    widthDp = LANDSCAPE_WIDTH_DP,
    heightDp = LANDSCAPE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun streamingChatChineseLandscapeReleasePreview() {
    ReleasePreviewSurface {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = previewChatSession(),
                    agentName = "Infinity Workbench 助手",
                    messages = listOf(
                        previewMessage("user-1", MessageRole.USER, "请总结这份发行检查清单。"),
                        previewMessage(
                            "assistant-1",
                            MessageRole.ASSISTANT,
                            "正在核对安全、备份、后台生成与视觉回归门禁……",
                            modelId = PREVIEW_CHAT_MODEL_ID,
                        ),
                    ),
                    isGenerating = true,
                    status = GenerationStatus.RECEIVING,
                    streamingContent = "正在核对安全、备份、后台生成与视觉回归门禁……",
                ),
                modelDisplayNames = mapOf(PREVIEW_CHAT_MODEL_ID to PREVIEW_METADATA_SHORT_NAME),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "DocEditor ready edit dirty English landscape",
    widthDp = LANDSCAPE_WIDTH_DP,
    heightDp = LANDSCAPE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun docEditorReadyEditDirtyEnglishLandscapeReleasePreview() {
    ReleasePreviewSurface {
        DocEditorScreenContent(
            state = DocEditorScreenState(
                editorState = previewDocEditorState(
                    title = "Infinity Workbench release checklist — edited",
                    content = PREVIEW_DOC_EDITOR_EDIT_CONTENT,
                    titleDirty = true,
                    contentDirty = true,
                ),
                viewMode = DocEditorViewMode.EDIT,
            ),
            actions = DocEditorScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "DocEditor exact 800x360 edit dirty English",
    widthDp = 800,
    heightDp = 360,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun docEditorExact800x360EditDirtyEnglishReleasePreview() {
    ReleasePreviewSurface {
        DocEditorScreenContent(
            state = DocEditorScreenState(
                editorState = previewDocEditorState(
                    title = "Infinity Workbench release checklist — edited",
                    content = PREVIEW_DOC_EDITOR_EDIT_CONTENT,
                    titleDirty = true,
                    contentDirty = true,
                ),
                viewMode = DocEditorViewMode.EDIT,
            ),
            actions = DocEditorScreenActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Provider models Chinese landscape",
    widthDp = LANDSCAPE_WIDTH_DP,
    heightDp = LANDSCAPE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun providerModelsChineseLandscapeReleasePreview() {
    ReleasePreviewSurface {
        ProviderModelsScreenContent(
            state = ProviderModelsScreenState(
                providerName = "腾讯云-企业模型服务入口（稳定版本）",
                providerId = "provider-preview-beta",
                isFetching = false,
                syncNotice = null,
                modelTestStates = mapOf(
                    "provider-preview-beta::qwen3-235b-long-name-with-capabilities" to ModelTestState.Success(298),
                ),
                models = listOf(
                    ModelInfo(
                        name = "超长模型名称示例：多模态推理与结构化输出协同增强版",
                        id = "provider-preview-beta::qwen3-235b-long-name-with-capabilities",
                        description = "preview",
                        enabled = true,
                        type = "chat",
                        contextLength = 131072,
                        capabilities = listOf("chat", "structuredoutput", "computeruse", "audioinput", "audiooutput"),
                        providerId = "provider-preview-beta",
                        providerName = "Cloud Beta",
                        remoteModelId = "qwen3-235b-long-name-with-capabilities",
                        maxOutputTokens = 12000,
                        knowledgeCutoff = "20240201",
                    ),
                ),
            ),
            actions = ProviderModelsScreenActions(
                onRefresh = {},
                onAdd = { _, _ -> true },
                onDisableAll = {},
                onDeleteAll = {},
                onUpdate = {},
                onToggle = {},
                onTest = {},
                onCancelTest = {},
                onDelete = {},
                onClearNotice = {},
            ),
            onNavigateBack = {},
        )
    }
}

private fun previewMessage(
    id: String,
    role: MessageRole,
    content: String,
    reasoning: String? = null,
    modelId: String? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    userDocuments: List<MessageDocumentAttachment>? = null,
) = Message(
    id = id,
    role = role,
    content = content,
    reasoning = reasoning,
    modelId = modelId,
    isError = isError,
    errorMessage = errorMessage,
    userDocuments = userDocuments,
    createdAt = PREVIEW_LOCAL_TIME.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
)

private fun previewDocument(id: String, name: String, sizeBytes: Long) =
    MessageDocumentAttachment(
        id = id,
        name = name,
        mimeType = if (name.endsWith(".txt")) "text/plain" else "text/markdown",
        content = "Preview full-context content",
        sizeBytes = sizeBytes,
        sha256 = "preview-$id",
        estimatedTokens = 8,
    )

private fun previewChatSession() = Session(
    id = "preview-chat",
    agentId = "preview-agent",
    title = "New Chat",
    modelId = PREVIEW_CHAT_MODEL_ID,
)

private fun previewDocEditorState(
    phase: DocEditorPhase = DocEditorPhase.Ready,
    title: String = "Infinity Workbench release checklist",
    content: String = PREVIEW_DOC_EDITOR_EDIT_CONTENT,
    titleDirty: Boolean = false,
    contentDirty: Boolean = false,
    sizeBytes: Long = 2_048L,
    contentAccess: DocEditorContentAccess = DocEditorContentAccess.Editable,
    failureCode: DocEditorFailureCode? = null,
    conflictCurrentHash: String? = null,
) = DocEditorUiState(
    phase = phase,
    workspaceRootUuid = PREVIEW_DOC_EDITOR_ROOT_ID,
    documentId = PREVIEW_DOC_EDITOR_DOCUMENT_ID,
    documentEpoch = PREVIEW_DOC_EDITOR_EPOCH,
    title = title,
    content = content,
    persistedTitle = "Infinity Workbench release checklist",
    persistedContent = PREVIEW_DOC_EDITOR_PERSISTED_CONTENT,
    currentHash = PREVIEW_DOC_EDITOR_HASH,
    totalLines = content.lineSequence().count(),
    wordCount = content.split(Regex("\\s+")).count(String::isNotBlank),
    lastModified = PREVIEW_DOC_EDITOR_LAST_MODIFIED,
    sizeBytes = sizeBytes,
    titleDirty = titleDirty,
    contentDirty = contentDirty,
    contentAccess = contentAccess,
    hasLoadedDocument = true,
    failureCode = failureCode,
    conflictCurrentHash = conflictCurrentHash,
)

private const val PREVIEW_DOC_EDITOR_ROOT_ID = "preview-root-0001"
private const val PREVIEW_DOC_EDITOR_DOCUMENT_ID = "preview-document-0001"
private const val PREVIEW_DOC_EDITOR_EPOCH = 7L
private const val PREVIEW_DOC_EDITOR_LAST_MODIFIED = 1_784_006_400_000L
private const val PREVIEW_DOC_EDITOR_HASH = "sha256:preview-doc-editor-0001"
private const val PREVIEW_DOC_EDITOR_CONFLICT_HASH = "sha256:preview-doc-editor-remote-0002"
private const val PREVIEW_DOC_EDITOR_PERSISTED_CONTENT = "# Release checklist\n\n- Verify safety gates"
private const val PREVIEW_DOC_EDITOR_EDIT_CONTENT = """# Release checklist

- Verify signed build
- Run accessibility checks
- Review screenshot baselines

Status: ready for final review.
"""
private const val PREVIEW_DOC_EDITOR_CONFLICT_CONTENT = """# 发行检查清单

- 保留本地安全修订
- 对比远端最新版本
- 确认后再重新加载
"""
private const val PREVIEW_DOC_EDITOR_RICH_MARKDOWN = """# Release readiness

This **deterministic preview** verifies production Markdown rendering.

- Security gates are complete
- Accessibility checks are repeatable

`./gradlew :app:validateDebugScreenshotTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

| Gate | Result |
| --- | --- |
| Unit tests | Pass |
| Visual review | Pending |
"""

private const val PREVIEW_RAG_ROOT_ID = "preview-root"
private const val PREVIEW_RAG_NOW_MILLIS = 1_784_006_400_000L

private val PREVIEW_RAG_PRODUCT_FOLDER = previewRagFile(
    uuid = "preview-folder-product",
    parentUuid = PREVIEW_RAG_ROOT_ID,
    name = "Product research",
    isDirectory = true,
)
private val PREVIEW_RAG_NESTED_FOLDER = previewRagFile(
    uuid = "preview-folder-release",
    parentUuid = PREVIEW_RAG_PRODUCT_FOLDER.uuid,
    name = "Release readiness",
    isDirectory = true,
)
private val PREVIEW_RAG_DEEP_FOLDER = previewRagFile(
    uuid = "preview-folder-evidence",
    parentUuid = PREVIEW_RAG_NESTED_FOLDER.uuid,
    name = "Evidence archive",
    isDirectory = true,
)
private val PREVIEW_RAG_DEEP_FILE = previewRagFile(
    uuid = "preview-doc-deep",
    parentUuid = PREVIEW_RAG_DEEP_FOLDER.uuid,
    name = "Commercial delivery evidence.md",
    mimeType = "text/markdown",
    sizeBytes = 18_432L,
    vectorizedAt = PREVIEW_RAG_NOW_MILLIS - 7_200_000L,
)
private val PREVIEW_RAG_ROOT_FILES = listOf(
    PREVIEW_RAG_PRODUCT_FOLDER,
    previewRagFile(
        uuid = "preview-doc-1",
        parentUuid = PREVIEW_RAG_ROOT_ID,
        name = "Infinity Workbench release checklist.pdf",
        mimeType = "application/pdf",
        sizeBytes = 253_952L,
        vectorizedAt = PREVIEW_RAG_NOW_MILLIS - 3_600_000L,
    ),
    previewRagFile(
        uuid = "preview-doc-2",
        parentUuid = PREVIEW_RAG_ROOT_ID,
        name = "Architecture decisions.md",
        mimeType = "text/markdown",
        sizeBytes = 18_432L,
        vectorizedAt = PREVIEW_RAG_NOW_MILLIS - 7_200_000L,
    ),
)
private val PREVIEW_RAG_CHILDREN = mapOf(
    PREVIEW_RAG_PRODUCT_FOLDER.uuid to listOf(PREVIEW_RAG_NESTED_FOLDER),
    PREVIEW_RAG_NESTED_FOLDER.uuid to listOf(PREVIEW_RAG_DEEP_FOLDER),
    PREVIEW_RAG_DEEP_FOLDER.uuid to listOf(PREVIEW_RAG_DEEP_FILE),
)
private val PREVIEW_RAG_REPOSITORY = Proxy.newProxyInstance(
    IWorkspaceRepository::class.java.classLoader,
    arrayOf(IWorkspaceRepository::class.java),
) { proxy, method, args ->
    when (method.name) {
        "observeChildren" -> {
            val parentUuid = args?.get(1) as String
            flowOf(PREVIEW_RAG_CHILDREN[parentUuid].orEmpty())
        }
        "searchByName" -> flowOf(emptyList<FileEntry>())
        "toString" -> "ReleasePreviewRagRepository"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        else -> throw UnsupportedOperationException("预览只读仓库不支持 ${method.name}")
    }
} as IWorkspaceRepository

private fun previewRagFile(
    uuid: String,
    parentUuid: String,
    name: String,
    isDirectory: Boolean = false,
    mimeType: String? = null,
    sizeBytes: Long = 0L,
    vectorizedAt: Long? = null,
) = FileEntry(
    uuid = uuid,
    workspaceRootUuid = PREVIEW_RAG_ROOT_ID,
    parentUuid = parentUuid,
    name = name,
    hash = "sha256:$uuid",
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    isDirectory = isDirectory,
    physicalRootPath = "/preview/infinity/rag",
    materializedPath = "/$name",
    vectorizedAt = vectorizedAt,
    createdAt = PREVIEW_RAG_NOW_MILLIS - 86_400_000L,
    updatedAt = PREVIEW_RAG_NOW_MILLIS - 10_800_000L,
)

private const val PREVIEW_RESOURCE_ROOT_ID = "resource-root-0001"
private const val PREVIEW_RESOURCE_NOW_MILLIS = 1_784_006_400_000L

private val PREVIEW_RESOURCE_FILE_LONG = previewResourceFile(
    uuid = "resource-file-0001",
    name = "Quarterly-release-readiness-and-commercial-delivery-evidence-archive.md",
    hash = "sha256:resource-file-0001",
    mimeType = "text/markdown",
    sizeBytes = 24_576L,
    materializedPath = "/Quarterly-release-readiness-and-commercial-delivery-evidence-archive.md",
)

private val PREVIEW_RESOURCE_FILE_SECONDARY = previewResourceFile(
    uuid = "resource-file-0002",
    name = "signed-apk-verification-report.pdf",
    hash = "sha256:resource-file-0002",
    mimeType = "application/pdf",
    sizeBytes = 262_144L,
    materializedPath = "/signed-apk-verification-report.pdf",
    vectorizedAt = PREVIEW_RESOURCE_NOW_MILLIS - 3_600_000L,
)

private val PREVIEW_RESOURCE_IMPORT_RESULTS = listOf(
    ShareImportItem(
        uri = Uri.parse("content://preview/resource-import-created-0001"),
        displayName = "commercial-release-readiness-evidence-with-a-very-long-name.md",
        mimeType = "text/markdown",
        sizeBytes = 12_288L,
        status = ShareImportStatus.Created,
        created = PREVIEW_RESOURCE_FILE_LONG,
        fileUuid = PREVIEW_RESOURCE_FILE_LONG.uuid,
        indexTaskId = "resource-index-task-0001",
    ),
    ShareImportItem(
        uri = Uri.parse("content://preview/resource-import-rejected-0002"),
        displayName = "index-scheduling-failure-retry-contract-with-a-very-long-name.pdf",
        mimeType = "application/pdf",
        sizeBytes = 524_288L,
        status = ShareImportStatus.Rejected,
        reason = ShareRejectReason.IndexScheduleFailed,
        fileUuid = "resource-import-file-0002",
    ),
)

private val PREVIEW_RESOURCE_REPOSITORY = Proxy.newProxyInstance(
    IWorkspaceRepository::class.java.classLoader,
    arrayOf(IWorkspaceRepository::class.java),
) { proxy, method, args ->
    when (method.name) {
        "observeChildren", "searchByName" -> flowOf(emptyList<FileEntry>())
        "toString" -> "ReleasePreviewResourceRepository"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        else -> throw UnsupportedOperationException("预览只读仓库不支持 ${method.name}")
    }
} as IWorkspaceRepository

private fun previewResourceFile(
    uuid: String,
    name: String,
    hash: String,
    mimeType: String,
    sizeBytes: Long,
    materializedPath: String,
    vectorizedAt: Long? = null,
    recycledAt: Long? = null,
    originalMaterializedPath: String? = null,
) = FileEntry(
    uuid = uuid,
    workspaceRootUuid = PREVIEW_RESOURCE_ROOT_ID,
    parentUuid = PREVIEW_RESOURCE_ROOT_ID,
    name = name,
    hash = hash,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    physicalRootPath = "/preview/infinity/resource-explorer",
    materializedPath = materializedPath,
    vectorizedAt = vectorizedAt,
    inRecycleBin = recycledAt != null,
    recycledAt = recycledAt,
    originalParentUuid = PREVIEW_RESOURCE_ROOT_ID,
    originalMaterializedPath = originalMaterializedPath,
    createdAt = PREVIEW_RESOURCE_NOW_MILLIS - 86_400_000L,
    updatedAt = PREVIEW_RESOURCE_NOW_MILLIS - 3_600_000L,
)

private val PREVIEW_LOCAL_TIME: LocalDateTime = LocalDateTime.of(2026, 7, 14, 8, 0)

@Composable
private fun ResourceExplorerReleasePreviewContainer(content: @Composable () -> Unit) {
    ReleasePreviewSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.resource_explorer_title),
                style = InfinityTypography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(16.dp))
            // 与 InfinityBottomSheet 默认的 contentColorFor(surfaceContainer) 保持一致。
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ModelPickerReleasePreview(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                content()
            }
        }
    }
}

@PreviewTest
@Preview(
    name = "RAG global Chinese dark large font",
    widthDp = 360,
    heightDp = 800,
    locale = "zh-rCN",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragGlobalChineseDarkLargeFontReleasePreview() {
    RagSettingsPreviewSurface(dark = true) {
        KnowledgeBaseSettingsContent(
            state = KnowledgeBaseSettingsState(
                config = RagConfiguration(
                    currentPreset = "writing",
                    docChunkSize = 1200,
                    chunkOverlap = 180,
                    summaryTemplate = "请先整理资料，再生成适合移动端阅读的简洁答案。",
                ),
                embeddingModelId = "text-embedding-3-small",
            ),
            actions = KnowledgeBaseSettingsActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Advanced retrieval Chinese light",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun advancedRetrievalChineseLightReleasePreview() {
    RagSettingsPreviewSurface(dark = false) {
        KnowledgeDebugSettingsContent(
            state = KnowledgeDebugSettingsState(
                config = RagConfiguration(
                    enableHybridSearch = true,
                    enableQueryRewrite = true,
                    memoryLimit = 12,
                    docLimit = 16,
                ),
                vectorCount = 2048,
                initiallyExpanded = true,
            ),
            actions = KnowledgeDebugSettingsActions(),
        )
    }
}

@PreviewTest
@Preview(
    name = "Agent knowledge English light large font",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun agentKnowledgeEnglishLightLargeFontReleasePreview() {
    RagSettingsPreviewSurface(dark = false) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            com.promenar.infinity.ui.hub.AgentKnowledgeMemorySection(
                retrievalMode = com.promenar.infinity.data.agent.AgentRetrievalMode.BOTH,
                fileAccess = com.promenar.infinity.data.agent.KnowledgeFileAccess.ON_REQUEST,
                policy = com.promenar.infinity.data.agent.AgentMemoryPolicy(shareWithOtherAgents = false),
                onRetrievalModeChange = {},
                onFileAccessChange = {},
                onPolicyChange = {},
            )
            com.promenar.infinity.ui.hub.AgentScopeSection(
                policy = com.promenar.infinity.data.agent.AgentMemoryPolicy(),
                onPolicyChange = {},
                onOpenKnowledgeSettings = {},
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "RAG advanced English dark",
    widthDp = PHONE_WIDTH_DP,
    heightDp = PHONE_HEIGHT_DP,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ragAdvancedEnglishDarkReleasePreview() {
    RagSettingsPreviewSurface(dark = true) {
        KnowledgeGraphSettingsContent(
            state = KnowledgeGraphSettingsState(
                config = RagConfiguration(
                    kgExtractionTimeoutSeconds = 90,
                    kgExtractionPrompt = "Extract entities, concepts, and relationships with source references.",
                ),
                entityCount = 42,
            ),
            actions = KnowledgeGraphSettingsActions(),
        )
    }
}

@Composable
private fun RagSettingsPreviewSurface(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            content()
        }
    }
}

@Composable
private fun ReleasePreviewSurface(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (darkTheme) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            content()
        }
    }
}

private object PreviewBackupOperations : BackupOperations {
    override suspend fun export(options: BackupExportOptions): ByteArray = error("preview only")
    override suspend fun upload(config: WebDavConfig, options: BackupExportOptions): BackupUploadReceipt =
        error("preview only")
    override suspend fun testRemote(config: WebDavConfig): Result<Unit> = error("preview only")
    override suspend fun listRemote(config: WebDavConfig): List<RemoteBackup> = error("preview only")
    override suspend fun stageLocalRestore(
        operationId: String,
        input: InputStream,
        password: CharArray?,
    ): PendingRestoreMetadata = error("preview only")
    override suspend fun stageRemoteRestore(
        operationId: String,
        config: WebDavConfig,
        selected: RemoteBackup,
        password: CharArray?,
    ): PendingRestoreMetadata = error("preview only")
    override suspend fun discardPendingRestore(operationId: String) = Unit
    override suspend fun authorizePendingRestore(operationId: String) = Unit
}

private object PreviewBackupSettings : BackupSettingsStore {
    override var webDavEnabled = false
    override var webDavUrl = ""
    override var webDavUser = ""
    override var lastBackupTime = 0L
    override var webDavPasswordPlaintext: String? = null
}

private class PreviewSecretStore : SecretStore {
    private val values = mutableMapOf<SecretId, ByteArray>()

    override fun put(id: SecretId, value: ByteArray) {
        values.remove(id)?.fill(0)
        values[id] = value.copyOf()
    }

    override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
    override fun contains(id: SecretId): Boolean = id in values
    override fun remove(id: SecretId) {
        values.remove(id)?.fill(0)
    }
}

private fun releasePreviewSkill(
    name: String,
    description: String,
    files: List<String>,
    status: com.promenar.infinity.data.skills.SkillStatus,
) = com.promenar.infinity.data.skills.DiscoveredSkill(
    id = "user:$name",
    scope = com.promenar.infinity.data.skills.SkillScope.USER,
    name = name,
    description = description,
    displayName = null,
    shortDescription = null,
    virtualDir = "/skills/$name",
    files = files,
    status = status,
    modifiedSinceConfirmed = false,
    allowImplicitInvocation = true,
    brokenReason = null,
    metadata = null,
)
