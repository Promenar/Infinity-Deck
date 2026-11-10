package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.pet.AndroidPetMediaMetadataReader
import com.promenar.infinity.data.pet.PetPreferenceStore
import com.promenar.infinity.domain.pet.PetFile
import com.promenar.infinity.domain.pet.PetPackageValidator
import com.promenar.infinity.domain.pet.PetParser
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.stringArgument
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 基于导入校验规则读取已安装的默认宠物，避免启动时列表尚未加载造成误报。 */
internal suspend fun installedDefaultPetName(filesDir: File, defaultId: String?): String? = withContext(Dispatchers.IO) {
    val reader = AndroidPetMediaMetadataReader()
    val pets = File(filesDir, "pets").listFiles().orEmpty().filter {
        it.isDirectory && it.name.length in 1..64 && !it.name.startsWith('.') && Regex("[a-z0-9._-]+").matches(it.name)
    }.mapNotNull { dir ->
        runCatching {
            val manifest = PetParser.parseManifest(File(dir, "manifest.json").readText()).getOrThrow()
            require(manifest.id == dir.name)
            val personaFile = File(dir, "persona.json")
            val persona = if (personaFile.exists()) PetParser.parsePersona(personaFile.readText()).getOrThrow() else null
            val files = dir.walkTopDown().filter { it.isFile }.map { file ->
                val path = file.relativeTo(dir).invariantSeparatorsPath
                runCatching { reader.read(file, path) }.getOrElse { PetFile(path, file.length()) }
            }.toList()
            require(PetPackageValidator().validate(manifest, persona, files).isEmpty())
            manifest
        }.getOrNull()
    }.sortedBy { it.name }
    (pets.firstOrNull { it.id == defaultId } ?: pets.firstOrNull())?.name
}

class PetSkill(
    private val preferences: PetPreferenceStore,
    private val defaultPetName: suspend (String?) -> String?,
) : SkillDefinition {
    override val id = "pet"
    override val name = "pet"
    override val description = "当用户说宠物不见了、想把宠物叫回来或暂时收起时使用，也可查询宠物状态。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"action":{"type":"string","enum":["summon","dismiss","status"],"description":"唤回、收起或查询状态"}},"required":["action"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        fun result(content: String, status: String = "success") = ToolResult(id = "result_${UUID.randomUUID()}", content = content, status = status)
        if (!preferences.enabled.value) return result("宠物功能未启用，请在 设置 → 宠物 中打开。")
        return when (args.stringArgument("action")) {
            "summon" -> { preferences.setDismissed(false); result("已唤回宠物。") }
            "dismiss" -> { preferences.setDismissed(true); result("已收起宠物。") }
            "status" -> {
                val state = if (preferences.dismissed.value) "已收起" else "未收起"
                val name = defaultPetName(preferences.defaultPetId.value)
                result("宠物功能已启用，$state。" + if (name == null) "没有安装宠物包。" else "默认宠物：$name。")
            }
            else -> result("action 须为 summon、dismiss 或 status。", "error")
        }
    }
}
