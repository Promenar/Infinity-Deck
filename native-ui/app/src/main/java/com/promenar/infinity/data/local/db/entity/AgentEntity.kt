package com.promenar.infinity.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String = "",
    @ColumnInfo(name = "name_customized")
    val nameCustomized: Boolean = false,
    @ColumnInfo(name = "description_customized")
    val descriptionCustomized: Boolean = false,
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String = "",
    val model: String = "",
    val icon: String = com.promenar.infinity.domain.avatar.AgentAvatarCodec.NEW_AGENT_DEFAULT,
    val color: String = com.promenar.infinity.domain.avatar.AgentAvatarPalette.forSeed(id).hex,
    @ColumnInfo(name = "avatar_path")
    val avatarPath: String? = null,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    val temperature: Double? = null,
    val top_p: Double? = null,
    val max_tokens: Int? = null,
    @ColumnInfo(name = "top_k")
    val topK: Int? = null,
    @ColumnInfo(name = "frequency_penalty")
    val frequencyPenalty: Double? = null,
    @ColumnInfo(name = "presence_penalty")
    val presencePenalty: Double? = null,
    @ColumnInfo(name = "repetition_penalty")
    val repetitionPenalty: Double? = null,
    /** 助手会话默认的推理强度；为空时按模型默认。 */
    @ColumnInfo(name = "reasoning_effort")
    val reasoningEffort: String? = null,
    @ColumnInfo(name = "rag_config")
    val ragConfig: com.promenar.infinity.data.agent.AgentRagConfig? = null,
    @ColumnInfo(name = "retrieval_config")
    val retrievalConfig: com.promenar.infinity.data.agent.AgentRetrievalConfig? = null,
    @ColumnInfo(name = "use_inherited_config")
    val useInheritedConfig: Boolean = true,
    @ColumnInfo(name = "execution_mode", defaultValue = "semi")
    val executionMode: String = "semi",
    @ColumnInfo(name = "skill_ids", defaultValue = "[]")
    val skillIds: String = "[]",
    @ColumnInfo(name = "mcp_server_ids", defaultValue = "[]")
    val mcpServerIds: String = "[]",
    /** 新建会话继承的自选工作区；为空时使用会话私有工作区。 */
    @ColumnInfo(name = "default_workspace_id")
    val defaultWorkspaceId: String? = null,
    /** 已停用，只保留列与映射。 */
    @ColumnInfo(name = "user_rules", defaultValue = "''")
    val userRules: String = "",
    @ColumnInfo(name = "tools_enabled_default", defaultValue = "1")
    val toolsEnabledByDefault: Boolean = true,
)
