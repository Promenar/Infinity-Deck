package com.promenar.infinity.data.production

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 制作面板的领域模型：项目 → 季 → 集 → 场 → 镜头 → 候选，横切资产库与阶段。
 * 整个项目以一份快照保存；数据库是事实源，工作区 `<rootPath>/production.json` 是同步副本。
 * 文件路径一律是会话工作区内的虚拟路径。
 */

@Serializable
enum class ProductionKind { @SerialName("film") FILM, @SerialName("series") SERIES }

/** 制作流程的七个阶段，顺序即流程顺序。 */
@Serializable
enum class ProductionStage {
    @SerialName("script") SCRIPT,
    @SerialName("visual_dev") VISUAL_DEV,
    @SerialName("storyboard") STORYBOARD,
    @SerialName("animatic") ANIMATIC,
    @SerialName("generation") GENERATION,
    @SerialName("sound") SOUND,
    @SerialName("edit") EDIT,
}

@Serializable
enum class StageStatus { @SerialName("not_started") NOT_STARTED, @SerialName("in_progress") IN_PROGRESS, @SerialName("locked") LOCKED, @SerialName("skipped") SKIPPED }

@Serializable
enum class ShotStatus { @SerialName("todo") TODO, @SerialName("generating") GENERATING, @SerialName("review") REVIEW, @SerialName("locked") LOCKED, @SerialName("redo") REDO }

@Serializable
enum class AssetStatus { @SerialName("draft") DRAFT, @SerialName("review") REVIEW, @SerialName("locked") LOCKED, @SerialName("redo") REDO }

@Serializable
enum class AssetKind { @SerialName("character") CHARACTER, @SerialName("location") LOCATION, @SerialName("prop") PROP, @SerialName("style") STYLE, @SerialName("sound") SOUND }

/** 生成模式；与具体服务无关。 */
@Serializable
enum class ShotMode { @SerialName("text") TEXT, @SerialName("first_frame") FIRST_FRAME, @SerialName("first_last") FIRST_LAST, @SerialName("reference") REFERENCE }

/** 镜头输入的职责。关键帧在任何模式下都可作为锁定（首帧、尾帧、锚点）或强参考（reference）。 */
@Serializable
enum class InputRole { @SerialName("first_frame") FIRST_FRAME, @SerialName("last_frame") LAST_FRAME, @SerialName("anchor") ANCHOR, @SerialName("reference") REFERENCE }

/** 预演素材类型。 */
@Serializable
enum class PreviewKind { @SerialName("keyframe") KEYFRAME, @SerialName("draft_clip") DRAFT_CLIP, @SerialName("text_card") TEXT_CARD }

/** 最后一次改动来自谁；用户的决定优先于智能体。 */
@Serializable
enum class ChangeSource { @SerialName("agent") AGENT, @SerialName("user") USER }

@Serializable
data class StageState(
    val stage: ProductionStage,
    val status: StageStatus = StageStatus.NOT_STARTED,
    val note: String? = null,
    val changedBy: ChangeSource = ChangeSource.AGENT,
    /** 最后一次改动所在的项目修订号。 */
    val changedAt: Long = 0,
)

@Serializable
data class DocumentRef(val title: String, val path: String)

@Serializable
data class ShotInput(
    val role: InputRole,
    val path: String,
    /** 显示名，例如“林晚”“首帧”。 */
    val label: String? = null,
    /** 关联资产；资产换版本时便于定位受影响镜头。 */
    val assetId: String? = null,
    /** 仅 [InputRole.ANCHOR]：锚定的时间点（秒）。 */
    val atSeconds: Double? = null,
)

@Serializable
data class TakeSource(
    /** 服务显示名，例如“Seedance 2.5”“本机 H3”。 */
    val service: String,
    val model: String? = null,
    val mode: ShotMode? = null,
    /** 第 2 期起由生成任务填写，指向用量专区的记账条目。 */
    val usageRef: String? = null,
)

@Serializable
data class Take(
    val id: String,
    val path: String,
    val source: TakeSource,
    val createdAt: Long,
    val note: String? = null,
    /** 第 2 期起由生成任务填写。 */
    val taskId: String? = null,
    val planSnapshot: TakePlanSnapshot? = null,
)

@Serializable
data class Shot(
    /** 集内唯一，例如 `SC02-03`。 */
    val code: String,
    val title: String,
    /** 景别，例如“近景”。 */
    val size: String? = null,
    val durationSeconds: Double,
    val mode: ShotMode = ShotMode.TEXT,
    val prompt: String = "",
    val inputs: List<ShotInput> = emptyList(),
    val preview: PreviewKind? = null,
    val previewPath: String? = null,
    val status: ShotStatus = ShotStatus.TODO,
    val takes: List<Take> = emptyList(),
    val selectedTakeId: String? = null,
    /** 用户批注，例如打回原因。 */
    val userNote: String? = null,
    /** 智能体写给用户的说明，例如“缺关键帧”。 */
    val agentNote: String? = null,
    val changedBy: ChangeSource = ChangeSource.AGENT,
    val changedAt: Long = 0,
    val camera: String? = null,
    val cameraMovement: String? = null,
    val visual: String? = null,
    val performance: String? = null,
    val dialogue: List<ShotDialogue> = emptyList(),
    val sound: String? = null,
    val transition: String? = null,
    val continuity: String? = null,
    val appearances: List<ShotAppearance> = emptyList(),
    val locationAssetId: String? = null,
    val propAssetIds: List<String> = emptyList(),
    val channelId: String? = null,
    val promptUserEdited: Boolean = false,
    val audio: List<ShotAudio> = emptyList(),
)

@Serializable
enum class DialogueAudioSource { @SerialName("native") NATIVE, @SerialName("dubbed") DUBBED }

@Serializable
data class ShotDialogue(val characterAssetId: String, val text: String, val tone: String? = null, val audioSource: DialogueAudioSource = DialogueAudioSource.NATIVE)

@Serializable
data class ShotAppearance(val characterAssetId: String, val lookId: String, val referenceSlotIds: List<String> = emptyList())

@Serializable
data class TakePlanSnapshot(val channelId: String? = null, val mode: ShotMode, val inputs: List<ShotInput>, val prompt: String,
    val mediaInputs: List<com.promenar.infinity.domain.mediagen.MediaInput> = emptyList(),
    val specs: com.promenar.infinity.domain.mediagen.GenerationSpecs = com.promenar.infinity.domain.mediagen.GenerationSpecs(),
    val generationMode: com.promenar.infinity.domain.mediagen.GenerationMode? = null)

@Serializable
data class ShotAudio(val id: String, val path: String, val dialogueIndex: Int, val taskId: String, val planSnapshot: TakePlanSnapshot, val createdAt: Long)

@Serializable
data class ReferenceSlot(val id: String, val name: String, val path: String? = null, val version: Int? = null)

@Serializable
data class CharacterLook(val id: String, val name: String, val slots: List<ReferenceSlot> = defaultReferenceSlots())

fun defaultReferenceSlots() = listOf(ReferenceSlot("front", "正面全身"), ReferenceSlot("side", "侧面全身"), ReferenceSlot("back", "背面全身"))

@Serializable
data class ReferenceVoice(val path: String? = null, val transcript: String? = null, val voiceSetting: String? = null, val tone: String? = null)

@Serializable
data class ShotChangeRequest(val id: String, val episodeId: String, val sceneCode: String, val shotCode: String, val changes: Map<String, ShotFieldChange>, val source: ChangeSource, val note: String? = null, val approved: Boolean = false)

@Serializable
data class ShotFieldChange(val oldValue: kotlinx.serialization.json.JsonElement, val newValue: kotlinx.serialization.json.JsonElement)

@Serializable
data class Scene(
    /** 集内唯一，例如 `SC02`。 */
    val code: String,
    val number: Int,
    /** 场景标题，例如“外 码头栈桥 夜”。 */
    val heading: String,
    val storyboardLocked: Boolean = false,
    val shots: List<Shot> = emptyList(),
    val changedBy: ChangeSource = ChangeSource.AGENT,
    val changedAt: Long = 0,
)

@Serializable
data class Episode(
    /** 项目内唯一，例如 `S1E01`。 */
    val id: String,
    val number: Int,
    val title: String,
    val scriptPath: String? = null,
    val scriptLocked: Boolean = false,
    val scenes: List<Scene> = emptyList(),
    val changedBy: ChangeSource = ChangeSource.AGENT,
    val changedAt: Long = 0,
)

@Serializable
data class Season(
    /** 项目内唯一，例如 `S1`。 */
    val id: String,
    val number: Int,
    val title: String? = null,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class AssetVersion(val number: Int, val path: String, val createdAt: Long, val note: String? = null)

@Serializable
data class Asset(
    /** 项目内唯一，例如 `char-linwan`。 */
    val id: String,
    val kind: AssetKind,
    val name: String,
    val description: String? = null,
    val versions: List<AssetVersion> = emptyList(),
    val currentVersion: Int? = null,
    val status: AssetStatus = AssetStatus.DRAFT,
    val userNote: String? = null,
    val changedBy: ChangeSource = ChangeSource.AGENT,
    val changedAt: Long = 0,
    val looks: List<CharacterLook> = emptyList(),
    val referenceVoice: ReferenceVoice? = null,
)

@Serializable
data class Production(
    val id: String,
    val title: String,
    val kind: ProductionKind,
    /** 工作区内的项目根目录，清单写在 `<rootPath>/production.json`。 */
    val rootPath: String,
    val logline: String? = null,
    /** 画幅，例如 `16:9`。 */
    val aspectRatio: String? = null,
    val bible: List<DocumentRef> = emptyList(),
    val stages: List<StageState> = ProductionStage.entries.map { StageState(it) },
    val seasons: List<Season> = emptyList(),
    val assets: List<Asset> = emptyList(),
    val previewEnabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val experimental: Boolean = false,
    val budgetCents: Long? = null,
    val changeRequests: List<ShotChangeRequest> = emptyList(),
    val policyChangedBy: ChangeSource = ChangeSource.AGENT,
    val policyChangedAt: Long = 0,
)

/** 清单文件与数据库的同步状态。 */
sealed interface ManifestSync {
    data object InSync : ManifestSync
    data object Pending : ManifestSync
    /** 写入失败，[reason] 为简短原因。 */
    data class WriteFailed(val reason: String) : ManifestSync
    /** 文件在应用之外被改动，内容与数据库不同；[fileRevision] 为文件里声明的修订号，无法解析时为空。 */
    data class ExternalChange(val fileRevision: Long?) : ManifestSync
    /** 文件被改坏，无法解析或校验失败。 */
    data class Invalid(val reason: String) : ManifestSync
}

data class ProductionSnapshot(
    val production: Production,
    /** 每次提交加一。 */
    val revision: Long,
    val manifest: ManifestSync,
)

/** 用户在面板里的操作；全部记为 [ChangeSource.USER]，失败时抛 [IllegalArgumentException]，消息可直接显示。 */
sealed interface ProductionUserAction {
    data class EditShotContent(val episodeId: String, val shotCode: String, val proposed: Shot) : ProductionUserAction
    data class ResolveChange(val requestId: String, val approve: Boolean) : ProductionUserAction
    data class SetExperimental(val enabled: Boolean) : ProductionUserAction
    data class SetBudget(val cents: Long?) : ProductionUserAction
    data class SetShotPlan(val episodeId: String, val shotCode: String, val channelId: String?, val mode: ShotMode) : ProductionUserAction
    data class RecompilePrompt(val episodeId: String, val shotCode: String, val prompt: String) : ProductionUserAction
    /** 生成层登记已完成任务；保留用户状态与内容决定。 */
    data class RegisterGeneratedTask(val task: com.promenar.infinity.domain.mediagen.MediaTask) : ProductionUserAction
    data class ClearPromptEdited(val episodeId: String, val shotCode: String) : ProductionUserAction
    data class SetCharacterLook(val episodeId: String, val shotCode: String, val characterAssetId: String, val lookId: String) : ProductionUserAction
    data class ToggleReferenceSlot(val episodeId: String, val shotCode: String, val characterAssetId: String, val slotId: String, val selected: Boolean) : ProductionUserAction
    data class SetAppearances(val episodeId: String, val shotCode: String, val appearances: List<ShotAppearance>) : ProductionUserAction
    data class SetDialogueSource(val episodeId: String, val shotCode: String, val index: Int, val source: DialogueAudioSource) : ProductionUserAction
    data class AddLook(val assetId: String, val look: CharacterLook) : ProductionUserAction
    data class AddReferenceSlot(val assetId: String, val lookId: String, val slot: ReferenceSlot) : ProductionUserAction
    data class RemoveReferenceSlot(val assetId: String, val lookId: String, val slotId: String) : ProductionUserAction
    data class SelectTake(val episodeId: String, val shotCode: String, val takeId: String) : ProductionUserAction
    data class RequestRedo(val episodeId: String, val shotCode: String, val note: String?) : ProductionUserAction
    /** 已定镜头回到待审。 */
    data class UnlockShot(val episodeId: String, val shotCode: String) : ProductionUserAction
    data class EditPrompt(val episodeId: String, val shotCode: String, val prompt: String) : ProductionUserAction
    data class SetAssetStatus(val assetId: String, val status: AssetStatus, val note: String?) : ProductionUserAction
    data class LockScene(val episodeId: String, val sceneCode: String, val locked: Boolean) : ProductionUserAction
    data class LockScript(val episodeId: String, val locked: Boolean) : ProductionUserAction
    data class SetStage(val stage: ProductionStage, val status: StageStatus) : ProductionUserAction
}

enum class ManifestResolution {
    /** 以文件内容覆盖数据库（文件通过校验时）。 */
    ADOPT_FILE,
    /** 以数据库内容重写文件。 */
    OVERWRITE_FILE,
}

/** 界面使用的入口；实现负责 IO 调度。 */
interface ProductionPanelSource {
    /** 会话绑定的项目；没有时为 null。打开面板时实现应顺带核对清单文件。 */
    fun observe(sessionId: String): Flow<ProductionSnapshot?>
    suspend fun apply(sessionId: String, expectedRevision: Long, action: ProductionUserAction): ProductionSnapshot
    suspend fun resolveManifest(sessionId: String, resolution: ManifestResolution): ProductionSnapshot
    /** 会话是否曾经绑定过项目；用于功能面板入口的可用性。 */
    fun observeEverBound(sessionId: String): Flow<Boolean>
}
