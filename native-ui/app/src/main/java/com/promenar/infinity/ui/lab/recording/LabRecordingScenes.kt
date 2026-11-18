package com.promenar.infinity.ui.lab.recording

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.recording.RecordingAsrModelState
import com.promenar.infinity.recording.RecordingDialogGroup
import com.promenar.infinity.recording.RecordingDialogRow
import com.promenar.infinity.recording.RecordingLanguage
import com.promenar.infinity.recording.RecordingMode
import com.promenar.infinity.recording.RecordingOrganizeSource
import com.promenar.infinity.recording.RecordingPageContent
import com.promenar.infinity.recording.RecordingPhase
import com.promenar.infinity.recording.RecordingRoute
import com.promenar.infinity.recording.RecordingScreenState
import com.promenar.infinity.recording.RecordingSegment
import com.promenar.infinity.recording.RecordingSession
import com.promenar.infinity.recording.RecordingSpeaker
import com.promenar.infinity.recording.RecordingWaveformSamples
import com.promenar.infinity.recording.RecordingWorkspaceSettings
import com.promenar.infinity.ui.common.ImmersiveSystemBars
import com.promenar.infinity.ui.lab.chat.LAB_MODEL
import com.promenar.infinity.ui.lab.chat.isChinese
import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.stage.LabSceneScope
import com.promenar.infinity.ui.lab.stage.LabStateScene
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 录音与语音整理目录下的版本：工作台双栏对照（逐字稿、说话人与整理稿）以及录音整理设置弹层（外屏上滑面板 / 内屏双栏弹窗）。
 */
internal val labRecordingScenes: Map<String, () -> LabScene> = mapOf(
    "recording.state.workbench" to {
        LabStateScene { RecordingWorkbenchScene(showSettings = false) }
    },
    "recording.state.settings" to {
        LabStateScene { RecordingWorkbenchScene(showSettings = true) }
    },
)

@Composable
private fun LabSceneScope.RecordingWorkbenchScene(showSettings: Boolean) {
    val zh = isChinese(LocalContext.current.resources)
    val session = remember(zh) { sampleSession(zh) }
    val history = remember(zh, session) { sampleHistory(zh, session) }
    val waveform = remember {
        MutableStateFlow(RecordingWaveformSamples(List(160) { ((it * 11 + 3) % 17) / 17f }, 160))
    }
    var organized by remember(zh) { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        RecordingPageContent(
            state = RecordingScreenState(
                sessions = history,
                durationMs = session.durationMs,
                ready = true,
                cloudLabel = "DeepSeek · $LAB_MODEL", // wrap-check: allow 沿用 RecordingModel.cloud 的单行服务商与模型短语
                localLabel = "Qwen3-4B (Q8_0)",
            ),
            current = session,
            organized = organized,
            route = RecordingRoute.Cloud,
            waveform = waveform,
            playing = playing,
            positionMs = 24_000f,
            onBack = onExit,
            onOrganizedChange = { organized = it },
            onTogglePlayback = { playing = !playing },
            asrModel = RecordingAsrModelState.Installed,
            onExport = {},
        )
    }

    if (showSettings) {
        RecordingWorkspaceSettings(
            mode = RecordingMode.Auto,
            language = if (zh) RecordingLanguage.Chinese else RecordingLanguage.English,
            live = true,
            speakers = true,
            fallback = true,
            single = false,
            dismiss = onExit,
            onSave = { _, _, _, _, _ -> onExit() },
            localInferenceAvailable = true,
        ) {
            ImmersiveSystemBars(true)
            RecordingDialogGroup(stringResource(R.string.recording_dialog_resources)) {
                RecordingDialogRow(stringResource(R.string.recording_setup_title), onClick = {}, icon = Icons.Rounded.FileDownload)
                RecordingDialogRow(stringResource(R.string.recording_hotword_library), onClick = {}, icon = Icons.Rounded.Description)
                RecordingDialogRow(stringResource(R.string.recording_speaker_library), onClick = {}, icon = Icons.Rounded.Person)
            }
        }
    }
}

private fun sampleSession(zh: Boolean): RecordingSession = RecordingSession(
    id = "lab-rec-1",
    createdAt = 1_763_000_000_000L,
    title = if (zh) "产品周会：离线语音与知识库检索" else "Product Sync: Offline Voice & Hybrid Search",
    phase = RecordingPhase.Finished,
    audioBytes = 104_000L * 32,
    segments = if (zh) {
        listOf(
            RecordingSegment(0L, 24_000L, "本周先核对 X-ASR 与 SenseVoice 二遍精修在不同内存档位下的退避阈值。", speakerId = 0),
            RecordingSegment(24_000L, 49_000L, "连续两段 RTF 超过 0.65 会自动降级到纯流式识别，保证会议长录不卡顿。", speakerId = 1),
            RecordingSegment(49_000L, 76_000L, "热词库和本地声纹库已经接入加密备份包，导入前会自动校验成员向量。", speakerId = 0),
            RecordingSegment(76_000L, 104_000L, "下一轮把资料库带编号引用与录音原声片段定位合并联调，周四前给出结果。", speakerId = 1),
        )
    } else {
        listOf(
            RecordingSegment(0L, 24_000L, "Let's review the backoff thresholds for SenseVoice two-pass refinement across memory tiers.", speakerId = 0),
            RecordingSegment(24_000L, 49_000L, "Two consecutive chunks above RTF 0.65 fall back to streaming-only so long recordings stay responsive.", speakerId = 1),
            RecordingSegment(49_000L, 76_000L, "Hotwords and voiceprints are now included in encrypted backup archives with pre-import checks.", speakerId = 0),
            RecordingSegment(76_000L, 104_000L, "Next, we'll link numbered library citations directly back to transcript timestamps by Thursday.", speakerId = 1),
        )
    },
    organized = if (zh) {
        """
        ### 会议结论

        **林妍**

        本周明确了 SenseVoice 二遍精修与 X-ASR 流式转写在不同内存机型上的自动分级参数。热词库与本地声纹库已完成加密导出整合，授权会话中的 Agent 可读写 `/mnt/knowledge` 下的录音文档与只读音频。

        [00:00](recording://seek/0) [00:49](recording://seek/49000)

        | 档位 | 二遍精修策略 | 熔断阈值 |
        |---|---|---|
        | 旗舰内存 | 句末精修 ＋ 兜底 | $\text{RTF} > 0.65$ |
        | 中端内存 | 停顿后延迟精修 | 连续 2 段熔断 |
        | 入门机型 | 纯流式转写 | — |

        ```mermaid
        flowchart LR
            A[X-ASR 流式转写] --> B{内存与 RTF 门限}
            B -->|达标| C[SenseVoice 精修]
            B -->|连续超限| D[回退纯流式]
            C --> E[云端富格式梳理 ＋ 1:1 导出]
        ```

        ```echarts
        {
          "title": { "text": "各环节平均耗时（ms）" },
          "xAxis": { "data": ["流式首包", "二遍精修", "声纹聚合", "富格式导出"] },
          "series": [{ "type": "bar", "data": [110, 240, 145, 290] }]
        }
        ```

        $$\text{ExportWidth}_{\text{px}} = \max\!\left(1440,\; 2 \times W_{\text{scroll}}\right), \quad \text{Scale} = 1:1$$

        **陈默**

        当连续两段计算 RTF 超过 `0.65` 时，系统自动熔断并退回纯流式模式，保证长时间会议持续录制不发热卡顿。

        [00:24](recording://seek/24000)

        ### 后续行动

        本周四前完成知识库带编号引用与录音原声时间戳跳转的联调验证，并同步发布新版默认热词预设包。

        [01:16](recording://seek/76000)
        """.trimIndent()
    } else {
        """
        ### Key Decisions

        **Lin Yan**

        Confirmed automatic RAM-tier thresholds for SenseVoice two-pass refinement and X-ASR streaming fallback. Hotwords and voiceprints are bundled into encrypted backups, and authorized agents can access recording documents and virtual `.wav` audio under `/mnt/knowledge`.

        [00:00](recording://seek/0) [00:49](recording://seek/49000)

        | Tier | Refinement Mode | Breaker |
        |---|---|---|
        | Flagship | Sentence-end ＋ fallback | $\text{RTF} > 0.65$ |
        | Mid-tier | Idle pause deferred | 2 slow chunks |
        | Entry | Streaming only | — |

        ```mermaid
        flowchart LR
            A[X-ASR Stream] --> B{RAM & RTF Gate}
            B -->|Pass| C[SenseVoice Refine]
            B -->|Tripped| D[Streaming Fallback]
            C --> E[Rich Organize ＋ 1:1 Export]
        ```

        ```echarts
        {
          "title": { "text": "Stage Latency (ms)" },
          "xAxis": { "data": ["Stream", "Refine", "Voice", "Export"] },
          "series": [{ "type": "bar", "data": [110, 240, 145, 290] }]
        }
        ```

        $$\text{ExportWidth}_{\text{px}} = \max\!\left(1440,\; 2 \times W_{\text{scroll}}\right), \quad \text{Scale} = 1:1$$

        **Chen Mo**

        If two consecutive segments exceed RTF `0.65`, refinement trips a circuit breaker and falls back to streaming-only so long sessions stay smooth and cool.

        [00:24](recording://seek/24000)

        ### Next Actions

        Wire numbered library citations directly to transcript timestamps and ship updated glossary defaults before Thursday.

        [01:16](recording://seek/76000)
        """.trimIndent()
    },
    mode = RecordingMode.Auto,
    organizedThrough = 4,
    organizedSource = RecordingOrganizeSource(
        cloud = true,
        provider = "DeepSeek",
        model = LAB_MODEL,
        completedAt = 1_763_000_120_000L,
        language = if (zh) RecordingLanguage.Chinese else RecordingLanguage.English,
    ),
    distinguishSpeakers = true,
    speakers = listOf(
        RecordingSpeaker(0, if (zh) "林妍" else "Lin Yan"),
        RecordingSpeaker(1, if (zh) "陈默" else "Chen Mo"),
    ),
    speakerAnalyzedBytes = 104_000L * 32,
    outputLanguage = if (zh) RecordingLanguage.Chinese else RecordingLanguage.English,
    liveOrganization = true,
    documentId = if (zh) "/会议纪要/产品周会-1a2b3c4d.md" else "/Meetings/Product-Sync-1a2b3c4d.md",
)

private fun sampleHistory(zh: Boolean, primary: RecordingSession): List<RecordingSession> = listOf(
    primary,
    RecordingSession(
        id = "lab-rec-2",
        createdAt = 1_762_913_600_000L,
        title = if (zh) "客户访谈：本地 GGUF 知识问答" else "User Interview: On-device GGUF Q&A",
        phase = RecordingPhase.Finished,
        audioBytes = 840_000L * 32,
    ),
    RecordingSession(
        id = "lab-rec-3",
        createdAt = 1_762_827_200_000L,
        title = if (zh) "片场配音与分镜复盘" else "Storyboard & Dubbing Sync",
        phase = RecordingPhase.Finished,
        audioBytes = 1_920_000L * 32,
    ),
)
