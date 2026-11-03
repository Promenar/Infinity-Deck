package com.promenar.infinity.data.skills

import kotlinx.coroutines.CancellationException

/** 内置运行指南由应用资产提供且保持常开；可切换项来自用户或项目目录。 */
data class SkillSourceGroup(val scope: SkillScope, val skills: List<DiscoveredSkill>) {
    val canToggle: Boolean get() = scope != SkillScope.BUILTIN
    val enabledCount: Int get() = skills.count { it.status == SkillStatus.ENABLED }
}

fun SkillSnapshot.sourceGroups(): List<SkillSourceGroup> = listOf(SkillScope.BUILTIN, SkillScope.USER, SkillScope.PROJECT)
    .map { scope -> SkillSourceGroup(scope, skills.filter { it.scope == scope }) }

/** 批量操作允许部分成功；失败信息按稳定技能 id 返回，界面可显示具体未完成项。 */
data class SkillBatchResult(
    val updatedIds: List<String>,
    val skippedIds: List<String>,
    val failures: Map<String, String>,
)

suspend fun SkillCatalog.setGroupEnabled(snapshot: SkillSnapshot, scope: SkillScope?, enabled: Boolean): SkillBatchResult {
    val updated = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val failures = linkedMapOf<String, String>()
    snapshot.skills.filter { scope == null || it.scope == scope }.forEach { skill ->
        if (skill.scope == SkillScope.BUILTIN || skill.status == SkillStatus.BROKEN ||
            !enabled && skill.status == SkillStatus.PENDING_TRUST
        ) {
            skipped += skill.id
        } else try {
            // 批量启用是对各项目当前内容的信任操作；用户级使用逐项启用的确认哈希机制。
            if (enabled && skill.scope == SkillScope.PROJECT) trust(skill) else setEnabled(skill, enabled)
            updated += skill.id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures[skill.id] = failure.message?.take(160) ?: "技能操作失败"
        }
    }
    return SkillBatchResult(updated, skipped, failures)
}
