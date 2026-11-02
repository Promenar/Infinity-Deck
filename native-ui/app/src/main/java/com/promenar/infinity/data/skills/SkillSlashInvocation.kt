package com.promenar.infinity.data.skills

/**
 * 输入框斜杠技能命令 `/技能名 补充说明` 的语法与选取规则。
 *
 * 发送前校验（界面）与生成时预加载（运行时）共用这一份规则，排队、重新生成或审批后续写时
 * 只凭用户消息原文即可还原同一个技能。
 */
object SkillSlashInvocation {
    /** 行首 `/` 后紧跟的第一个词；词内再出现 `/` 时视为路径而不是命令。 */
    private val COMMAND = Regex("^/([^\\s/]+)(?:\\s+([\\s\\S]*))?$")
    private val SKILL_NAME = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    /** [token] 为原样输入的命令词（不含 `/`），[argument] 为其后的补充说明（已去掉首尾空白）。 */
    data class Parsed(val token: String, val argument: String) {
        /** 技能名统一按小写比较；不符合技能名格式时为 null。 */
        val skillName: String? get() = token.lowercase().takeIf { SKILL_NAME.matches(it) && it.length <= 64 }
    }

    /** 选取结果：可用、存在但不可用（停用、待信任、损坏）、不存在。 */
    sealed interface Selection {
        data class Ready(val skill: DiscoveredSkill) : Selection
        data class Unavailable(val skill: DiscoveredSkill) : Selection
        data object NotFound : Selection
    }

    /** 文本以 `/` 开头且第一个词非空时返回命令词与补充说明，否则返回 null。 */
    fun parse(text: String): Parsed? {
        val match = COMMAND.matchEntire(text) ?: return null
        return Parsed(match.groupValues[1], match.groupValues[2].trim())
    }

    /**
     * 按名称选取技能。同名时内置优先；其它来源优先已启用的，均启用时项目级优先于用户级，
     * 与“工作区内的约定覆盖个人默认”的直觉一致。
     */
    fun select(skills: List<DiscoveredSkill>, name: String): Selection {
        val matches = skills.filter { it.name == name }
        if (matches.isEmpty()) return Selection.NotFound
        val preferred = compareByDescending<DiscoveredSkill> { it.scope == SkillScope.BUILTIN }
            .thenByDescending { it.scope == SkillScope.PROJECT }
        matches.filter { it.status == SkillStatus.ENABLED }.sortedWith(preferred).firstOrNull()
            ?.let { return Selection.Ready(it) }
        return Selection.Unavailable(matches.sortedWith(preferred).first())
    }
}
