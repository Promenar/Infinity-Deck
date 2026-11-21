package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.production.PRODUCTION_BATCH_ERROR_LIMIT
import com.promenar.infinity.data.production.PrevisCodec
import com.promenar.infinity.data.production.PrevisScene
import com.promenar.infinity.data.production.PrevisShot
import com.promenar.infinity.data.production.ProductionManifestCodec
import com.promenar.infinity.data.production.ProductionPrevisStore
import com.promenar.infinity.data.production.ProductionSnapshot
import com.promenar.infinity.data.production.defaultPrevisPath
import com.promenar.infinity.data.production.totalDurationSeconds
import com.promenar.infinity.data.production.validatePrevisScene
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * 为指定集配置离线 3D 白模分镜预演（Previs DSL）。
 * 完整坐标系与 DSL 规范在 `infinity-deck` 技能的 `references/production-previs.md` 中按需读取，不在工具定义内膨胀常驻上下文。
 */
class ProductionPrevisSkill(
    private val load: suspend (String) -> ProductionSnapshot?,
    private val setEpisodePrevis: suspend (sessionId: String, episodeId: String, previsPath: String?) -> ProductionSnapshot,
    private val store: ProductionPrevisStore,
) : SkillDefinition {
    override val id = "production_previs"
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val description =
        "读写指定集的离线 3D 白模分镜预演（Previs DSL v1）。使用前请按需读取内置指南 `infinity-deck` 的 `references/production-previs.md`。get 读配置；set 用 scene 对象或已有工作区 path 校验绑定；patch_shot 更新单镜 camera/actors/duration；clear 解除绑定。"
    override val parametersSchema =
        """{"type":"object","properties":{"action":{"type":"string","enum":["get","set","patch_shot","clear"]},"episode_id":{"type":"string"},"path":{"type":"string"},"scene":{"type":"object"},"shot_code":{"type":"string"},"shot":{"type":"object"}},"required":["action","episode_id"],"additionalProperties":false}"""

    private val shotJson = Json { ignoreUnknownKeys = false }

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
        validateProductionToolKeys(
            args,
            setOf("action", "episode_id"),
            setOf("action", "episode_id", "path", "scene", "shot_code", "shot"),
        )
        val action = (args["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("action 必须是 get、set、patch_shot 或 clear。")
        require(action in setOf("get", "set", "patch_shot", "clear")) {
            "action 仅支持 get、set、patch_shot、clear。"
        }
        val episodeId = (args["episode_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("episode_id 必须是非空字符串。")

        val snapshot = requireNotNull(load(context.sessionId)) { "当前会话没有制作项目。" }
        val production = snapshot.production
        val episode = production.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId }
            ?: throw IllegalArgumentException("集 $episodeId 不存在，请先创建该集。")

        val body = when (action) {
            "get" -> {
                val path = episode.previsPath
                if (path == null) {
                    buildJsonObject {
                        put("has_previs", false)
                        put("episode_id", episodeId)
                        put("default_path", defaultPrevisPath(production.rootPath, episodeId))
                    }.toString()
                } else {
                    val scene = store.load(context.sessionId, path, episode = null)
                    val encoded = scene?.let { PrevisCodec.encode(it, episode = null) }
                    buildJsonObject {
                        put("has_previs", scene != null)
                        put("episode_id", episodeId)
                        put("path", path)
                        if (scene != null && encoded != null) {
                            put("shot_count", scene.shots.size)
                            put("total_duration", scene.totalDurationSeconds())
                            if (encoded.toByteArray(Charsets.UTF_8).size <= 20 * 1024) {
                                put("scene", Json.parseToJsonElement(encoded))
                            } else {
                                put("truncated", true)
                                put("hint", "场景 JSON 超过 20 KiB，请使用 file_read 查看 $path 或用 patch_shot 局部更新。")
                            }
                        } else {
                            put("warning", "已登记路径 $path，但工作区文件缺失。")
                        }
                    }.toString()
                }
            }

            "set" -> {
                val rawPath = (args["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                val rawScene = args["scene"]
                require(rawPath != null || rawScene != null) {
                    "set 需要提供 scene 对象（写入默认路径），或提供已有工作区 path（校验并绑定）。"
                }
                val resolvedPath = if (rawPath != null) {
                    store.normalizePath(context.sessionId, rawPath, production.rootPath).also { normalized ->
                        require(normalized.startsWith(production.rootPath + "/")) {
                            "3D 预演文件必须位于项目根目录 ${production.rootPath}/ 内（当前为 $normalized）。"
                        }
                    }
                } else {
                    defaultPrevisPath(production.rootPath, episodeId)
                }
                ProductionManifestCodec.validatePath(resolvedPath)

                val savedScene = if (rawScene != null) {
                    val sceneObj = rawScene as? JsonObject ?: throw IllegalArgumentException("scene 必须是 JSON 对象。")
                    val decoded = try {
                        shotJson.decodeFromJsonElement<PrevisScene>(sceneObj)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("scene 结构无效：${e.message?.substringBefore("JSON input:")?.take(200)}", e)
                    }
                    store.saveScene(context.sessionId, resolvedPath, decoded, episode)
                } else {
                    store.load(context.sessionId, resolvedPath, episode)
                        ?: throw IllegalArgumentException("工作区中未找到预演文件：$resolvedPath")
                }

                val updated = setEpisodePrevis(context.sessionId, episodeId, resolvedPath)
                buildJsonObject {
                    put("action", "set")
                    put("episode_id", episodeId)
                    put("path", resolvedPath)
                    put("shot_count", savedScene.shots.size)
                    put("total_duration", savedScene.totalDurationSeconds())
                    put("revision", updated.revision)
                }.toString()
            }

            "patch_shot" -> {
                val shotCode = (args["shot_code"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("patch_shot 必填 shot_code。")
                val shotObj = args["shot"] as? JsonObject
                    ?: throw IllegalArgumentException("patch_shot 必填 shot 对象。")
                val path = episode.previsPath
                    ?: throw IllegalArgumentException("集 $episodeId 尚未设置 3D 预演，请先调用 action=\"set\" 建立基础场景与角色。")
                val currentScene = store.load(context.sessionId, path, episode = null)
                    ?: throw IllegalArgumentException("预演文件不存在：$path")
                val patchedShot = try {
                    shotJson.decodeFromJsonElement<PrevisShot>(shotObj)
                } catch (e: Exception) {
                    throw IllegalArgumentException("shot 结构无效：${e.message?.substringBefore("JSON input:")?.take(200)}", e)
                }
                require(patchedShot.code == shotCode) { "shot.code（${patchedShot.code}）必须与参数 shot_code（$shotCode）一致。" }
                val idx = currentScene.shots.indexOfFirst { it.code == shotCode }
                val nextShots = if (idx >= 0) {
                    currentScene.shots.mapIndexed { i, item -> if (i == idx) patchedShot else item }
                } else {
                    currentScene.shots + patchedShot
                }
                val nextScene = currentScene.copy(shots = nextShots)
                validatePrevisScene(nextScene, episode)
                store.saveScene(context.sessionId, path, nextScene, episode)
                val updated = setEpisodePrevis(context.sessionId, episodeId, path)
                buildJsonObject {
                    put("action", "patch_shot")
                    put("episode_id", episodeId)
                    put("shot_code", shotCode)
                    put("path", path)
                    put("shot_count", nextScene.shots.size)
                    put("revision", updated.revision)
                }.toString()
            }

            else -> {
                val updated = setEpisodePrevis(context.sessionId, episodeId, null)
                buildJsonObject {
                    put("action", "clear")
                    put("episode_id", episodeId)
                    put("revision", updated.revision)
                }.toString()
            }
        }
        ToolResult(id = id, content = body)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val reason = error.message.orEmpty().ifBlank { "3D 预演参数无效，请参考 references/production-previs.md。" }
            .take(PRODUCTION_BATCH_ERROR_LIMIT)
        ToolResult(
            id = id,
            status = "error",
            content = buildJsonObject {
                put("error", if ("当前会话没有制作项目" in reason) "no_project" else "INVALID_PRODUCTION_PREVIS")
                put("message", reason)
                put("suggestion", productionErrorSuggestion(reason))
            }.toString(),
        )
    }
}
