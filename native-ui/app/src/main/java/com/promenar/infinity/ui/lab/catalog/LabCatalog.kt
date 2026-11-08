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
                            LabVersion("ops-approval", "chat.state.ops-approval", R.string.lab_v_ops_approval, R.string.lab_v_ops_approval_desc, LabVersionKind.STATE),
                            LabVersion("ops-report", "chat.state.ops-report", R.string.lab_v_ops_report, R.string.lab_v_ops_report_desc, LabVersionKind.STATE),
                            LabVersion("queue", "chat.state.queue", R.string.lab_v_queue, R.string.lab_v_queue_desc, LabVersionKind.STATE),
                            LabVersion("ask-user", "chat.state.ask-user", R.string.lab_v_ask_user, R.string.lab_v_ask_user_desc, LabVersionKind.STATE),
                            LabVersion("secret-input", "chat.state.secret-input", R.string.lab_v_secret_input, R.string.lab_v_secret_input_desc, LabVersionKind.STATE),
                            LabVersion("touch-lock", "chat.state.touch-lock", R.string.lab_v_touch_lock, R.string.lab_v_touch_lock_desc, LabVersionKind.STATE),
                            LabVersion("model-network", "chat.state.model-network", R.string.lab_v_model_network, R.string.lab_v_model_network_desc, LabVersionKind.STATE),
                            LabVersion("tailnet-wait", "chat.state.tailnet-wait", R.string.lab_v_tailnet_wait, R.string.lab_v_tailnet_wait_desc, LabVersionKind.STATE),
                        ),
                    ),
                    LabNode(
                        segment = "composer",
                        title = R.string.lab_node_composer,
                        children = listOf(
                            LabVersion("empty", "composer.state.empty", R.string.lab_v_composer_empty, R.string.lab_v_composer_empty_desc, LabVersionKind.STATE),
                            LabVersion("idle", "composer.state.idle", R.string.lab_v_composer_idle, R.string.lab_v_composer_idle_desc, LabVersionKind.STATE),
                            LabVersion("streaming", "composer.state.streaming", R.string.lab_v_composer_streaming, R.string.lab_v_composer_streaming_desc, LabVersionKind.STATE),
                            LabVersion("tools", "composer.state.tools", R.string.lab_v_composer_tools, R.string.lab_v_composer_tools_desc, LabVersionKind.STATE),
                        ),
                    ),
                    LabNode(
                        segment = "inspector",
                        title = R.string.lab_node_inspector,
                        children = listOf(
                            LabVersion("git-worktrees", "inspector.state.git-worktrees", R.string.lab_v_git_worktrees, R.string.lab_v_git_worktrees_desc, LabVersionKind.STATE),
                            LabVersion("git-commits", "inspector.state.git-commits", R.string.lab_v_git_commits, R.string.lab_v_git_commits_desc, LabVersionKind.STATE),
                            LabVersion("plan", "inspector.state.plan", R.string.lab_v_inspector_plan, R.string.lab_v_inspector_plan_desc, LabVersionKind.STATE),
                            LabVersion("files-repo", "inspector.state.files-repo", R.string.lab_v_files_repo, R.string.lab_v_files_repo_desc, LabVersionKind.STATE),
                            LabVersion("open-shift", "inspector.workflow.open-shift", R.string.lab_v_open_shift, R.string.lab_v_open_shift_desc, LabVersionKind.WORKFLOW),
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
                    LabVersion("ssh-web01", "terminal.state.ssh-web01", R.string.lab_v_ssh_web01, R.string.lab_v_ssh_web01_desc, LabVersionKind.STATE),
                ),
            ),
            LabNode(
                segment = "browser",
                title = R.string.lab_node_browser,
                children = listOf(
                    LabVersion("agent", "browser.state.agent", R.string.lab_v_browser_agent, R.string.lab_v_browser_agent_desc, LabVersionKind.STATE),
                    LabVersion("takeover", "browser.state.takeover", R.string.lab_v_browser_takeover, R.string.lab_v_browser_takeover_desc, LabVersionKind.STATE),
                    LabVersion("login", "browser.state.login", R.string.lab_v_browser_login, R.string.lab_v_browser_login_desc, LabVersionKind.STATE),
                ),
            ),
            LabNode(
                segment = "library",
                title = R.string.lab_node_library,
                children = listOf(
                    LabVersion("ask", "library.state.ask", R.string.lab_v_library_ask, R.string.lab_v_library_ask_desc, LabVersionKind.STATE, LabScreenFit.ANY),
                    LabVersion("locate", "library.state.locate", R.string.lab_v_library_locate, R.string.lab_v_library_locate_desc, LabVersionKind.STATE, LabScreenFit.ANY),
                ),
            ),
            LabNode(
                segment = "settings",
                title = R.string.lab_node_settings,
                children = listOf(
                    LabVersion("tailscale-peers", "settings.workflow.tailscale-peers", R.string.lab_v_tailscale_peers, R.string.lab_v_tailscale_peers_desc, LabVersionKind.WORKFLOW),
                    LabVersion("provider-tailnet", "settings.state.provider-tailnet", R.string.lab_v_provider_tailnet, R.string.lab_v_provider_tailnet_desc, LabVersionKind.STATE),
                    LabVersion("provider-gateway-picker", "settings.state.provider-gateway-picker", R.string.lab_v_provider_picker, R.string.lab_v_provider_picker_desc, LabVersionKind.STATE),
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
