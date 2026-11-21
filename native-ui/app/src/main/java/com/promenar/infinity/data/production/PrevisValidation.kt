package com.promenar.infinity.data.production

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 离线 3D 白模预演 DSL 的解析、序列化与领域约束校验。
 * 所有错误消息使用简洁中文，便于面板展示与智能体直接定位修正。
 */
object PrevisCodec {
    const val SUPPORTED_VERSION = 1
    const val MAX_PROPS = 80
    const val MAX_ACTORS = 16
    const val MAX_SHOTS = 120
    const val MAX_JSON_BYTES = 512 * 1024

    val ALLOWED_SHAPES = setOf("box", "cylinder", "sphere", "plane", "ramp")
    val ALLOWED_POSES = setOf("stand", "walk", "sit", "wave", "talk", "point", "lean")
    val ALLOWED_EASINGS = setOf("linear", "easeInOut", "easeIn", "easeOut")

    private val strictJson = Json {
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = false
    }

    private val compactJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    private val hexColorRegex = Regex("^#[0-9a-fA-F]{6}$")

    fun encode(scene: PrevisScene, episode: Episode? = null): String {
        validatePrevisScene(scene, episode)
        return strictJson.encodeToString(scene).also { text ->
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
                "3D 预演文件超过 512 KiB 上限，请精简道具或镜头数据。"
            }
        }
    }

    fun decode(rawJson: String, episode: Episode? = null): PrevisScene {
        val bytes = rawJson.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "3D 预演文件超过 512 KiB 上限。" }
        val scene = try {
            strictJson.decodeFromString<PrevisScene>(rawJson)
        } catch (e: SerializationException) {
            val detail = e.message.orEmpty().substringBefore("JSON input:").take(200)
            throw IllegalArgumentException("3D 预演 JSON 格式无效：$detail", e)
        }
        validatePrevisScene(scene, episode)
        return scene
    }

    internal fun compactEncode(scene: PrevisScene): String = compactJson.encodeToString(scene)

    internal fun validateColor(raw: String?, field: String) {
        if (raw != null) {
            require(hexColorRegex.matches(raw)) { "$field 颜色须为 #RRGGBB 十六进制格式，当前为 $raw。" }
        }
    }
}

fun validatePrevisScene(scene: PrevisScene, episode: Episode? = null) {
    require(scene.version == PrevisCodec.SUPPORTED_VERSION) {
        "不支持的预演版本 version=${scene.version}，当前仅支持 version=1。"
    }
    require(scene.unit == "m") { "预演坐标单位 unit 必须为 \"m\"（米）。" }
    PrevisCodec.validateColor(scene.skyColor, "skyColor")
    require(scene.props.size <= PrevisCodec.MAX_PROPS) { "props 最多 ${PrevisCodec.MAX_PROPS} 项。" }
    require(scene.actors.size <= PrevisCodec.MAX_ACTORS) { "actors 最多 ${PrevisCodec.MAX_ACTORS} 项。" }
    require(scene.shots.size in 1..PrevisCodec.MAX_SHOTS) { "shots 须包含 1～${PrevisCodec.MAX_SHOTS} 个镜头。" }

    fun vec3(values: List<Double>?, field: String, requirePositive: Boolean = false) {
        require(values != null && values.size == 3 && values.all { it.isFinite() }) {
            "$field 必须是长度为 3 的有限数值数组 [x, y, z]。"
        }
        if (requirePositive) {
            require(values.all { it > 0.0 }) { "$field 尺寸各项必须大于 0。" }
        }
    }

    val propIds = mutableSetOf<String>()
    scene.props.forEachIndexed { i, prop ->
        require(prop.id.isNotBlank() && prop.id.length <= 64 && propIds.add(prop.id)) {
            "props[$i].id 必须非空且唯一（当前 ${prop.id}）。"
        }
        require(prop.shape in PrevisCodec.ALLOWED_SHAPES) {
            "props[${prop.id}].shape=${prop.shape} 无效，仅支持 ${PrevisCodec.ALLOWED_SHAPES.joinToString()}。"
        }
        vec3(prop.pos, "props[${prop.id}].pos")
        vec3(prop.size, "props[${prop.id}].size", requirePositive = true)
        require(prop.rotY.isFinite()) { "props[${prop.id}].rotY 必须是有限数值。" }
        prop.rot?.let { vec3(it, "props[${prop.id}].rot") }
        PrevisCodec.validateColor(prop.color, "props[${prop.id}].color")
    }

    val actorIds = mutableSetOf<String>()
    scene.actors.forEachIndexed { i, actor ->
        require(actor.id.isNotBlank() && actor.id.length <= 64 && actorIds.add(actor.id)) {
            "actors[$i].id 必须非空且唯一（当前 ${actor.id}）。"
        }
        require(actor.height.isFinite() && actor.height in 0.3..4.0) {
            "actors[${actor.id}].height 须在 0.3～4.0 米之间。"
        }
        PrevisCodec.validateColor(actor.color, "actors[${actor.id}].color")
        PrevisCodec.validateColor(actor.accent, "actors[${actor.id}].accent")
    }

    val validEpisodeShotCodes = episode?.scenes?.flatMap { it.shots }?.map { it.code }?.toSet()
    val shotCodes = mutableSetOf<String>()
    scene.shots.forEachIndexed { i, shot ->
        require(shot.code.isNotBlank() && shotCodes.add(shot.code)) {
            "shots[$i].code 必须非空且唯一（当前 ${shot.code}）。"
        }
        if (validEpisodeShotCodes != null) {
            require(shot.code in validEpisodeShotCodes) {
                "预演镜头 ${shot.code} 不属于集 ${episode.id}，合法镜号为 ${validEpisodeShotCodes.joinToString("、")}。"
            }
        }
        require(shot.duration.isFinite() && shot.duration > 0.0 && shot.duration <= 600.0) {
            "shots[${shot.code}].duration 须为大于 0 且不超过 600 秒的有限数值。"
        }
        val cam = shot.camera
        vec3(cam.from, "shots[${shot.code}].camera.from")
        cam.to?.let { vec3(it, "shots[${shot.code}].camera.to") }
        vec3(cam.lookAtFrom, "shots[${shot.code}].camera.lookAtFrom")
        cam.lookAtTo?.let { vec3(it, "shots[${shot.code}].camera.lookAtTo") }
        cam.fov?.let {
            require(it.isFinite() && it in 15.0..120.0) { "shots[${shot.code}].camera.fov 须在 15～120 度之间。" }
        }
        cam.focalLengthMm?.let {
            require(it.isFinite() && it in 12.0..200.0) { "shots[${shot.code}].camera.focalLengthMm 须在 12～200 mm 之间。" }
        }
        require(cam.roll.isFinite() && cam.roll in -180.0..180.0) {
            "shots[${shot.code}].camera.roll 须在 -180～180 度之间。"
        }
        require(cam.easing in PrevisCodec.ALLOWED_EASINGS) {
            "shots[${shot.code}].camera.easing=${cam.easing} 无效，仅支持 ${PrevisCodec.ALLOWED_EASINGS.joinToString()}。"
        }
        shot.depthRange?.let { dr ->
            require(dr.size == 2 && dr.all { it.isFinite() } && dr[0] > 0.0 && dr[1] > dr[0]) {
                "shots[${shot.code}].depthRange 须为 [near, far] 且满足 0 < near < far。"
            }
        }
        val frameActorIds = mutableSetOf<String>()
        shot.actors.forEach { ak ->
            require(ak.id in actorIds) { "shots[${shot.code}] 引用了未声明的角色 ${ak.id}。" }
            require(frameActorIds.add(ak.id)) { "shots[${shot.code}] 中角色 ${ak.id} 重复出现。" }
            vec3(ak.from, "shots[${shot.code}].actors[${ak.id}].from")
            ak.to?.let { vec3(it, "shots[${shot.code}].actors[${ak.id}].to") }
            ak.facing?.let { require(it.isFinite()) { "shots[${shot.code}].actors[${ak.id}].facing 须为有限角度。" } }
            ak.lookAt?.let { vec3(it, "shots[${shot.code}].actors[${ak.id}].lookAt") }
            ak.lookAtActor?.let { targetId ->
                require(targetId in actorIds) { "shots[${shot.code}].actors[${ak.id}].lookAtActor=${targetId} 未声明。" }
            }
            require(ak.pose in PrevisCodec.ALLOWED_POSES) {
                "shots[${shot.code}].actors[${ak.id}].pose=${ak.pose} 无效，仅支持 ${PrevisCodec.ALLOWED_POSES.joinToString()}。"
            }
        }
        shot.axis?.let { axis ->
            require(axis.actorA in actorIds && axis.actorB in actorIds && axis.actorA != axis.actorB) {
                "shots[${shot.code}].axis 须指定两名不同的已声明角色。"
            }
            require(axis.side == 1 || axis.side == -1) {
                "shots[${shot.code}].axis.side 仅支持 1 或 -1。"
            }
        }
    }
    require(PrevisCodec.compactEncode(scene).toByteArray(Charsets.UTF_8).size <= PrevisCodec.MAX_JSON_BYTES) {
        "3D 预演文件超过 512 KiB 上限，请精简数据。"
    }
}
