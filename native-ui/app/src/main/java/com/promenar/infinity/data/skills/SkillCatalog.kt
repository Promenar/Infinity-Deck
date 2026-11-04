package com.promenar.infinity.data.skills

import java.io.File
import kotlinx.coroutines.flow.StateFlow

/** 技能来源级别。 */
enum class SkillScope { USER, PROJECT, BUILTIN }

/** 技能当前对模型的可用状态。 */
enum class SkillStatus {
    /** 已启用，进入模型可见范围。 */
    ENABLED,

    /** 用户停用，或新出现的用户级技能尚未启用。 */
    DISABLED,

    /** 项目级技能首次出现或内容变化，等待用户信任。 */
    PENDING_TRUST,

    /** 格式错误、名称与目录不一致或超出大小限制；原因见 [DiscoveredSkill.brokenReason]。 */
    BROKEN,
}

/** 扫描项目级技能所需的工作区信息；只有目录型工作区（项目目录、共享存储目录）有项目级技能。 */
data class SkillWorkspace(
    /** 工作区稳定标识（`WorkspaceEntity.id`），信任与停用记录按它区分。 */
    val key: String,
    /** 工作区真实根目录；项目级技能位于 `<root>/.agents/skills/<name>/`。 */
    val root: File,
)

/**
 * 一次扫描发现的技能。
 *
 * [id] 跨来源唯一且跨扫描稳定：内置为 `builtin:<name>`，用户级为 `user:<name>`，项目级为 `project:<workspaceKey>:<name>`。
 * [virtualDir] 是模型与文件工具使用的虚拟路径：用户级为 `/skills/<name>`，项目级为 `/.agents/skills/<name>`。
 */
data class DiscoveredSkill(
    val id: String,
    val scope: SkillScope,
    /** 目录名；BROKEN 时也有值。 */
    val name: String,
    /** frontmatter 的 description；BROKEN 时可能为空。 */
    val description: String,
    /** `agents/openai.yaml` 的 `interface.display_name`，缺省为 null。 */
    val displayName: String?,
    /** `agents/openai.yaml` 的 `interface.short_description`，缺省为 null。 */
    val shortDescription: String?,
    val virtualDir: String,
    /** 附带文件的相对路径（不含 SKILL.md，不含 `.git/`），按字典序。 */
    val files: List<String>,
    val status: SkillStatus,
    /** 用户级已启用技能在上次确认后内容发生变化；用户确认后清除。 */
    val modifiedSinceConfirmed: Boolean,
    /** `policy.allow_implicit_invocation`，缺省 true；为 false 时只在用户以 `$名称` 点名时提供给模型。 */
    val allowImplicitInvocation: Boolean,
    val brokenReason: String?,
    /** 解析成功时的元数据；BROKEN 时为 null。 */
    val metadata: AgentSkillMetadata?,
)

/** 一次扫描的结果；[workspace] 为 null 时没有项目级技能。 */
data class SkillSnapshot(
    val workspace: SkillWorkspace?,
    val skills: List<DiscoveredSkill>,
) {
    val builtin: List<DiscoveredSkill> get() = skills.filter { it.scope == SkillScope.BUILTIN }
    val user: List<DiscoveredSkill> get() = skills.filter { it.scope == SkillScope.USER }
    val project: List<DiscoveredSkill> get() = skills.filter { it.scope == SkillScope.PROJECT }

    /** 进入模型自动清单的技能：已启用且允许隐式调用。 */
    val implicitCatalog: List<DiscoveredSkill>
        get() = skills.filter { it.status == SkillStatus.ENABLED && it.allowImplicitInvocation }

    fun byName(name: String): List<DiscoveredSkill> = skills.filter { it.name == name }
}

/**
 * 技能目录的统一入口。用户与项目文件是事实源，内置指令来自应用资产；状态不写入技能目录。
 *
 * 所有方法可在任意线程调用，内部在 IO 上执行；扫描按文件修改时间与大小增量复用上次结果。
 */
interface SkillCatalog {
    /** 用户级技能根目录的真实路径（虚拟路径 `/skills`）。 */
    val userRoot: File

    /** 每次状态记录或已知文件变化后递增，界面据此重新扫描。 */
    val revision: StateFlow<Long>

    suspend fun scan(workspace: SkillWorkspace?): SkillSnapshot

    /** 读取 SKILL.md 正文；技能不存在或已损坏时返回 null。 */
    suspend fun readDocument(skill: DiscoveredSkill): ParsedSkillDocument?

    /** 读取附带文本文件；越界、过大或非 UTF-8 时抛出 [AgentSkillFormatException]。 */
    suspend fun readFile(skill: DiscoveredSkill, path: String): String

    /** 启用或停用；对 PENDING_TRUST 与 BROKEN 技能抛出 [IllegalStateException]。 */
    suspend fun setEnabled(skill: DiscoveredSkill, enabled: Boolean)

    /** 信任项目级技能的当前内容并启用。 */
    suspend fun trust(skill: DiscoveredSkill)

    /** 确认用户级技能的当前内容，清除 [DiscoveredSkill.modifiedSinceConfirmed]。 */
    suspend fun acknowledgeModified(skill: DiscoveredSkill)

    /** 永久删除技能目录并清除其状态记录。 */
    suspend fun delete(skill: DiscoveredSkill)

    /** 安装经逐次审批绑定的完整文件快照；调用方必须核验审批，扫描本身不授予安装权限。 */
    suspend fun installApproved(files: Map<String, ByteArray>, overwrite: Boolean = false): DiscoveredSkill =
        throw UnsupportedOperationException("此技能来源不支持安装")
}
