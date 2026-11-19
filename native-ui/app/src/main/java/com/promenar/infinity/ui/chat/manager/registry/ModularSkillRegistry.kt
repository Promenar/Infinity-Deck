package com.promenar.infinity.ui.chat.manager.registry

import com.promenar.infinity.data.remote.protocol.ProtocolTool

class ModularSkillRegistry(
    private val registries: List<SkillRegistry>
) : AsyncSkillRegistry {
    
    override fun getSkill(name: String): SkillDefinition? {
        for (registry in registries) {
            val skill = registry.getSkill(name)
            if (skill != null) return skill
        }
        return null
    }

    override fun getAllSkills(): List<SkillDefinition> {
        return registries.flatMap { it.getAllSkills() }
    }

    override suspend fun getAllToolsAsync(allowedIds: List<String>?): List<ProtocolTool> =
        registries.flatMap { it.getAllToolsAsync(allowedIds) }

    override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> {
        return registries.flatMap { it.getAllTools(allowedIds) }
    }
}
