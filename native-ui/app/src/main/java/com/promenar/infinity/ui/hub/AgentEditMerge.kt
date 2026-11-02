package com.promenar.infinity.ui.hub

import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.AgentField

/**
 * 编辑页三方合并的结果。
 * - [merged]：数据库现值叠加本地改动后的 Agent；冲突字段保留远端值；
 * - [toWrite]：只在本地改过、需要写入的字段；
 * - [remoteOnly]：只有远端改过、本地应静默刷新的字段；
 * - [conflicts]：两边都改且取值不同的字段，不自动覆盖，由用户选择。
 */
data class AgentEditMerge(
    val merged: Agent,
    val toWrite: Set<AgentField>,
    val remoteOnly: Set<AgentField>,
    val conflicts: Set<AgentField>,
)

/**
 * [base] 是页面加载或上次保存成功时的值，[local] 是界面当前值，[remote] 是数据库当前值。
 * 本地改过的字段取本地值，本地没改的字段取远端值；两边改成同一个值视为一致，不算冲突也不必写入。
 */
fun mergeAgentEdit(base: Agent, local: Agent, remote: Agent): AgentEditMerge {
    val localChanged = AgentField.changed(base, local)
    val remoteChanged = AgentField.changed(base, remote)
    val conflicts = localChanged.filterTo(linkedSetOf()) { it in remoteChanged && it.read(local) != it.read(remote) }
    val toWrite = localChanged.filterTo(linkedSetOf()) { it !in conflicts && it.read(local) != it.read(remote) }
    val remoteOnly = remoteChanged.filterTo(linkedSetOf()) { it !in localChanged }
    val merged = toWrite.fold(remote) { agent, field -> field.copy(agent, local) }
    return AgentEditMerge(merged, toWrite, remoteOnly, conflicts)
}
