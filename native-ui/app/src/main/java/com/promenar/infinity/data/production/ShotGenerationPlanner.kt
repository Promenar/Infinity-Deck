package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.InputKind
import com.promenar.infinity.domain.mediagen.InputRole
import com.promenar.infinity.domain.mediagen.MediaChannel
import com.promenar.infinity.domain.mediagen.Money
import com.promenar.infinity.domain.mediagen.PromptFormat

/**
 * 镜头生成规划：把镜头的规划字段解析为某个通道的实际输入与提交提示词，并据此提交生成任务。
 * 界面与智能体工具都只通过这个接口取预览、提交生成。
 */
interface ShotGenerationPlanner {
    /** 预览当前规划；不提交、不改数据。 */
    suspend fun preview(sessionId: String, episodeId: String, shotCode: String): ShotPlanPreview

    /** 按当前规划提交生成任务；场未锁定（非试验项目）、通道缺失、输入超限等情况返回拒绝原因。 */
    suspend fun generate(sessionId: String, episodeId: String, shotCode: String): ShotGenerateResult

    suspend fun generate(sessionId: String, episodeId: String, shotCode: String, params: Map<String, kotlinx.serialization.json.JsonPrimitive>): ShotGenerateResult =
        if (params.isEmpty()) generate(sessionId, episodeId, shotCode) else ShotGenerateResult.Rejected("当前规划器不支持模板参数。")

    /** 按当前通道的提示词格式从分镜字段重新编译提示词并写回，同时清除“已改写”标记。界面调用前需用户确认。 */
    suspend fun recompilePrompt(sessionId: String, episodeId: String, shotCode: String): String
}

data class ShotPlanPreview(
    /** 解析出的通道；没有支持该模式的通道时为 null。 */
    val channel: MediaChannel?,
    val channelIsDefault: Boolean,
    /** 例如“Seedance 2.5 · 火山方舟”。 */
    val channelLabel: String,
    val mode: ShotMode,
    /** 已按通道入参（合图或分张、参考位顺序）打包并排序的输入。 */
    val inputs: List<PlannedInput>,
    /** 超出通道上限、不会提交的输入。 */
    val overflow: List<PlannedInput>,
    val prompt: String,
    val promptEdited: Boolean,
    val promptFormat: PromptFormat,
    /** 预估费用；免费为 0，未知为 null。 */
    val estimate: Money?,
    /** 不可生成的原因；null 表示可生成。 */
    val blockedReason: String?,
    /** 非阻断提示，例如出场角色参考包待审。 */
    val warnings: List<String>,
    val audioPlans: List<PlannedDialogueAudio> = emptyList(),
    val specs: com.promenar.infinity.domain.mediagen.GenerationSpecs = com.promenar.infinity.domain.mediagen.GenerationSpecs(),
    val params: Map<String, kotlinx.serialization.json.JsonPrimitive> = emptyMap(),
)

data class PlannedInput(
    /** 图片的“图N”编号（与提示词引用一致）；非图片为 null。 */
    val number: Int?,
    /** 例如“林晚 · 风衣 · 合图”。 */
    val title: String,
    /** 例如“正面全身 / 侧面全身 / 背面全身”。 */
    val detail: String,
    val kind: InputKind,
    val role: InputRole,
    /** 工作区相对路径；合图在提交时即时拼合，预览时为 null。 */
    val path: String?,
    /** 合图由哪些参考位图片（工作区相对路径）按顺序组成；非合图为空。 */
    val compositeOf: List<String> = emptyList(),
)

data class PlannedDialogueAudio(val dialogueIndex: Int, val characterAssetId: String, val tone: String?,
    val request: com.promenar.infinity.domain.mediagen.MediaGenerationRequest)

sealed interface ShotGenerateResult {
    data class Submitted(val taskId: String, val awaitingApproval: Boolean, val audioTaskIds: List<String> = emptyList(), val warnings: List<String> = emptyList()) : ShotGenerateResult
    data class Rejected(val reason: String) : ShotGenerateResult
}
