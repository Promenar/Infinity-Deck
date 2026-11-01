package com.promenar.infinity.ui.lab.catalog

import androidx.annotation.StringRes
import com.promenar.infinity.R

/** 版本类型：状态是某页面停在一个时刻，工作流是按时间轴回放的一段过程。 */
enum class LabVersionKind { STATE, WORKFLOW }

/** 版本适合在哪块屏上看：内屏宽布局、外屏窄布局，或两者都可以。 */
enum class LabScreenFit { ANY, INNER, OUTER }

/** 目录里的一项：子目录或叶子版本，按 App 路由分层。 */
sealed interface LabEntry {
    /** 在父目录内唯一的路径段。 */
    val segment: String
}

data class LabNode(
    override val segment: String,
    @StringRes val title: Int,
    val children: List<LabEntry>,
) : LabEntry

/** 叶子版本；[id] 全局唯一，深链与截图脚本用它直接打开。 */
data class LabVersion(
    override val segment: String,
    val id: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val kind: LabVersionKind,
    val fit: LabScreenFit = LabScreenFit.ANY,
) : LabEntry

/**
 * 视觉实验室的目录：第一期只收宣传片与 README 截图要用的版本，路由骨架按 App 的真实层级排布，
 * 后续新增页面时在对应目录下追加节点即可。
 */
object LabCatalog {
    val root: LabNode = LabNode(
        segment = "",
        title = R.string.lab_title,
        children = listOf(
            LabNode(
                segment = "chat",
                title = R.string.lab_node_chat,
                children = listOf(
                    LabNode(
                        segment = "conversation",
                        title = R.string.lab_node_conversation,
                        children = listOf(
                            LabVersion("ade-json", "chat.workflow.ade-json", R.string.lab_v_ade_json, R.string.lab_v_ade_json_desc, LabVersionKind.WORKFLOW),
                            LabVersion("ops-502", "chat.workflow.ops-502", R.string.lab_v_ops_502, R.string.lab_v_ops_502_desc, LabVersionKind.WORKFLOW),
                            LabVersion("subagents", "chat.state.subagents", R.string.lab_v_subagents, R.string.lab_v_subagents_desc, LabVersionKind.STATE),
                            LabVersion("background-job", "chat.state.background-job", R.string.lab_v_background_job, R.string.lab_v_background_job_desc, LabVersionKind.STATE),
                            LabVersion("branch-plan", "chat.state.branch-plan", R.string.lab_v_branch_plan, R.string.lab_v_branch_plan_desc, LabVersionKind.STATE),
                        ),
                    ),
                    LabNode(
                        segment = "inspector",
                        title = R.string.lab_node_inspector,
                        children = listOf(
                            LabVersion("git-worktrees", "inspector.state.git-worktrees", R.string.lab_v_git_worktrees, R.string.lab_v_git_worktrees_desc, LabVersionKind.STATE, LabScreenFit.INNER),
                            LabVersion("plan", "inspector.state.plan", R.string.lab_v_inspector_plan, R.string.lab_v_inspector_plan_desc, LabVersionKind.STATE, LabScreenFit.INNER),
                        ),
                    ),
                ),
            ),
            LabNode(
                segment = "terminal",
                title = R.string.lab_node_terminal,
                children = listOf(
                    LabVersion("fastfetch", "terminal.state.fastfetch", R.string.lab_v_fastfetch, R.string.lab_v_fastfetch_desc, LabVersionKind.STATE),
                    LabVersion("apk-add", "terminal.workflow.apk-add", R.string.lab_v_apk_add, R.string.lab_v_apk_add_desc, LabVersionKind.WORKFLOW),
                ),
            ),
        ),
    )

    /** 按路径找目录节点：空串为根，路径段之间用 `/` 分隔；找不到返回 null。 */
    fun node(path: String): LabNode? {
        var current = root
        for (segment in path.split('/').filter(String::isNotEmpty)) {
            current = current.children.filterIsInstance<LabNode>().firstOrNull { it.segment == segment } ?: return null
        }
        return current
    }

    /** 全部版本与其所在目录路径，按目录先序排列。 */
    fun versionsWithPath(): List<Pair<String, LabVersion>> {
        val out = mutableListOf<Pair<String, LabVersion>>()
        fun walk(node: LabNode, path: String) {
            for (child in node.children) when (child) {
                is LabNode -> walk(child, if (path.isEmpty()) child.segment else "$path/${child.segment}")
                is LabVersion -> out += path to child
            }
        }
        walk(root, "")
        return out
    }

    fun version(id: String): LabVersion? = versionsWithPath().firstOrNull { it.second.id == id }?.second

    /** 目录下（含子目录）共有多少个版本，列表行用它做副标题。 */
    fun versionCount(node: LabNode): Int = node.children.sumOf { child ->
        when (child) {
            is LabNode -> versionCount(child)
            is LabVersion -> 1
        }
    }
}
