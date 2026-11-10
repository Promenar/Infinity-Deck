package com.promenar.infinity.domain.pet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** 宠物包清单，见规格第 3 节。 */
@Serializable
data class PetManifest(
    val contract: String,
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val license: PetLicense,
    val display: PetDisplay,
    val clips: Map<String, PetClip>,
    val expressions: PetExpressions = PetExpressions(),
    val map: Map<String, PetReaction>,
)

/** 许可及分发限制，见规格第 3 节。 */
@Serializable
data class PetLicense(
    val name: String,
    val redistribute: Boolean,
    val commercial: Boolean,
)

/** 角色显示尺寸与朝向，见规格第 3 节。 */
@Serializable
data class PetDisplay(
    @SerialName("height_dp") val heightDp: Int,
    val facing: String,
)

/** 素材片段及类型参数，见规格第 3.1 节。 */
@Serializable
data class PetClip(
    val type: String,
    val file: String,
    val anchor: List<Double>,
    val scale: Double = 1.0,
    val rig: PetRig? = null,
    val motion: String? = null,
    val cols: Int? = null,
    val rows: Int? = null,
    val count: Int? = null,
    val fps: Double? = null,
    val loop: Boolean? = null,
    val alpha: String? = null,
    val fallback: String? = null,
)

/** 程序化运动参数，见规格第 3.1 节；hairTop 是归一化纵向位置。 */
@Serializable
data class PetRig(
    val neck: List<Double>? = null,
    @SerialName("hair_top") val hairTop: Double? = null,
    val tail: PetTail? = null,
    val sitting: Boolean? = null,
    val lying: Boolean? = null,
)

/** 尾巴的支点、末端和半径，见规格第 3.1 节。 */
@Serializable
data class PetTail(
    val root: List<Double>,
    val tip: List<Double>,
    val radius: Double,
)

/** 表情素材集，见规格第 3.2 节。 */
@Serializable
data class PetExpressions(
    @SerialName("base_clip") val baseClip: String? = null,
    val face: PetFace? = null,
    val set: Map<String, String> = emptyMap(),
)

/** 表情替换区域的归一化椭圆，见规格第 3.2 节。 */
@Serializable
data class PetFace(
    val center: List<Double>,
    val radius: List<Double>,
)

/** 信号对应的表现，见规格第 3.3 节。 */
@Serializable
data class PetReaction(
    val clip: String? = null,
    val enter: String? = null,
    @SerialName("enter_ms") val enterMs: Long? = null,
    val sometimes: List<PetSometimes> = emptyList(),
    val behaviors: List<PetBehavior> = emptyList(),
    val expression: String? = null,
    val say: Boolean = false,
    val sequence: List<PetSequenceStep> = emptyList(),
)

/** 持续期间的插入动作，见规格第 3.3 节。 */
@Serializable
data class PetSometimes(
    val clip: String,
    @SerialName("every_s") val everyS: Double,
    @SerialName("for_ms") val forMs: Long,
)

/** 空闲行为及其权重和时长，见规格第 3.3 节。 */
@Serializable
data class PetBehavior(
    val clip: String,
    val weight: Double = 1.0,
    val move: Boolean = false,
    @SerialName("min_s") val minS: Double? = null,
    @SerialName("max_s") val maxS: Double? = null,
)

/** 按顺序播放的动作，见规格第 3.3 节。 */
@Serializable
data class PetSequenceStep(
    val clip: String,
    val launch: Long? = null,
    @SerialName("into_island") val intoIsland: Boolean = false,
)

/** 角色台词配置，见规格第 7.1 节。 */
@Serializable
data class PetPersona(
    val name: String,
    val self: String = "",
    val user: String = "",
    val personality: String = "",
    val style: String = "",
    val catchphrases: List<String> = emptyList(),
    val avoid: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val fallback: Map<String, List<String>> = emptyMap(),
)

/** 忽略未来次版本的未知字段，见规格第 1 节。 */
object PetParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseManifest(value: String): Result<PetManifest> = runCatching {
        json.decodeFromString<PetManifest>(value)
    }

    fun parsePersona(value: String): Result<PetPersona> = runCatching {
        json.decodeFromString<PetPersona>(value)
    }
}
