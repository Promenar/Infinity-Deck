package com.promenar.infinity.data.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * 内置指令只从 APK assets 读取，不解压、不挂载到命令可写的文件系统。
 * 正文只放选用规则，细节放在 [REFERENCE_FILES] 列出的附带文件中按需读取。
 */
class BuiltinSkillCatalog(
    private val delegate: SkillCatalog,
    private val includePetAuthoring: Boolean = false,
    private val readAsset: (String) -> String,
) : SkillCatalog by delegate {
    private fun text(name: String = NAME): String = readAsset("builtin_skills/$name/SKILL.md")

    private fun descriptor(name: String): DiscoveredSkill {
        val metadata = SkillMarkdownParser.parse(text(name)).metadata
        check(metadata.name == name) { "内置技能名称与打包路径不一致" }
        return DiscoveredSkill(
            id = "builtin:$name", scope = SkillScope.BUILTIN, name = name, description = metadata.description,
            displayName = null, shortDescription = null, virtualDir = "/.infinity/builtin-skills/$name",
            files = if (name == NAME) REFERENCE_FILES else emptyList(),
            status = SkillStatus.ENABLED, modifiedSinceConfirmed = false, allowImplicitInvocation = true,
            brokenReason = null, metadata = metadata,
        )
    }

    override suspend fun scan(workspace: SkillWorkspace?): SkillSnapshot = withContext(Dispatchers.IO) {
        val builtins = (if (includePetAuthoring) listOf(NAME, PET_NAME) else listOf(NAME)).map(::descriptor)
        val snapshot = try {
            delegate.scan(workspace)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            com.promenar.infinity.utils.InfinityLogger.log("[AgentSkills] 普通技能扫描失败，保留内置指南：${failure.javaClass.simpleName}")
            SkillSnapshot(workspace, emptyList())
        }
        snapshot.copy(skills = builtins + snapshot.skills.filterNot { it.scope == SkillScope.BUILTIN })
    }

    override suspend fun readDocument(skill: DiscoveredSkill): ParsedSkillDocument? = withContext(Dispatchers.IO) {
        if (isBuiltin(skill)) SkillMarkdownParser.parse(text(skill.name)) else delegate.readDocument(skill)
    }

    override suspend fun readFile(skill: DiscoveredSkill, path: String): String = withContext(Dispatchers.IO) {
        if (!isBuiltin(skill)) return@withContext delegate.readFile(skill, path)
        when (val relative = AgentSkillStore.normalizeRelativePath(path)) {
            AgentSkillStore.SKILL_FILE -> text(skill.name)
            in REFERENCE_FILES -> {
                if (skill.name != NAME) throw AgentSkillFormatException("内置技能没有此附带文件：$path")
                readAsset("$ASSET_DIR/$relative")
            }
            else -> throw AgentSkillFormatException("内置指南没有此附带文件：$path")
        }
    }

    override suspend fun installApproved(files: Map<String, ByteArray>, overwrite: Boolean, enableAfterInstall: Boolean?): DiscoveredSkill =
        delegate.installApproved(files, overwrite, enableAfterInstall)

    override suspend fun setEnabled(skill: DiscoveredSkill, enabled: Boolean) {
        requireMutable(skill)
        delegate.setEnabled(skill, enabled)
    }

    override suspend fun delete(skill: DiscoveredSkill) {
        requireMutable(skill)
        delegate.delete(skill)
    }

    override suspend fun trust(skill: DiscoveredSkill) {
        requireMutable(skill)
        delegate.trust(skill)
    }

    override suspend fun acknowledgeModified(skill: DiscoveredSkill) {
        requireMutable(skill)
        delegate.acknowledgeModified(skill)
    }

    private fun isBuiltin(skill: DiscoveredSkill): Boolean {
        if (skill.scope != SkillScope.BUILTIN && !skill.id.startsWith("builtin:")) return false
        val allowed = skill.name == NAME || (includePetAuthoring && skill.name == PET_NAME)
        require(allowed && skill.scope == SkillScope.BUILTIN && skill.id == "builtin:${skill.name}" &&
            skill.virtualDir == "/.infinity/builtin-skills/${skill.name}") {
            "内置技能句柄无效"
        }
        return true
    }

    private fun requireMutable(skill: DiscoveredSkill) {
        check(!isBuiltin(skill)) { "内置技能只读，不能编辑、删除或切换启用状态" }
    }

    companion object {
        const val NAME = "infinity-deck"
        const val PET_NAME = "pet-pack-authoring"
        const val ID = "builtin:$NAME"
        // 复用所有文件入口既有的 .infinity 保留名门禁；此路径没有对应的 shell 目录。
        const val VIRTUAL_DIR = "/.infinity/builtin-skills/$NAME"
        const val ASSET_DIR = "builtin_skills/$NAME"
        const val ASSET_PATH = "$ASSET_DIR/SKILL.md"

        /** 随 APK 打包的附带文件白名单（相对技能目录，按字典序）；新增文件须同时登记在这里。 */
        val REFERENCE_FILES = listOf("references/background-processes.md", "references/command-execution.md", "references/production.md")

        fun isVirtualPath(path: String): Boolean {
            val segments = path.replace('\\', '/').split('/').filter(String::isNotEmpty)
            return segments.zipWithNext().any { (parent, child) ->
                parent.equals(".infinity", ignoreCase = true) && child.equals("builtin-skills", ignoreCase = true)
            }
        }
    }
}
