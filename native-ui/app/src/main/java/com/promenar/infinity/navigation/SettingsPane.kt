package com.promenar.infinity.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import com.promenar.infinity.ui.common.LocalInfinitySettingsPaneRoot

/**
 * 设置子页之间的跳转。主导航中 [outside] 为 null，行为与直接调用 NavController 相同；
 * 宽屏设置右列中 [nav] 是右列自己的导航，打开不属于右列的页面（[isSettingsPaneRoute] 为 false）时交给 [outside]，
 * [paneRootId] 给出右列根层页面的条目 id。
 */
class SettingsNavigator internal constructor(
    private val nav: NavHostController,
    private val outside: ((String) -> Unit)? = null,
    private val paneRootId: (() -> String?)? = null,
) {
    fun open(route: String, builder: NavOptionsBuilder.() -> Unit = {}) {
        val handOff = outside
        if (handOff != null && !isSettingsPaneRoute(route)) handOff(route) else nav.navigate(route, builder)
    }

    fun back() {
        nav.popBackStack()
    }

    /** 右列根层（左列选中项对应的页面）不显示返回：离开它靠左列或主导航。 */
    @Composable
    fun Page(entry: NavBackStackEntry, content: @Composable () -> Unit) {
        val paneRoot = paneRootId != null && paneRootId.invoke() == entry.id
        CompositionLocalProvider(LocalInfinitySettingsPaneRoot provides paneRoot, content = content)
    }
}

/** 设置首页可直接打开的子页：宽屏时即左列条目，右列以它们为根。 */
internal val SettingsPaneRoots: List<String> = listOf(
    NavDestinations.THEME_CONFIG,
    NavDestinations.PROVIDER_LIST,
    NavDestinations.DEFAULT_MODELS,
    NavDestinations.LOCAL_MODELS,
    NavDestinations.AGENT_BUDGET,
    NavDestinations.BUILTIN_TOOLS,
    NavDestinations.SKILLS_CONFIG,
    NavDestinations.MCP_SERVERS,
    NavDestinations.RAG_GLOBAL_CONFIG,
    NavDestinations.RAG_ADVANCED_KG,
    NavDestinations.LONG_TERM_MEMORY,
    NavDestinations.RAG_DEBUG,
    NavDestinations.LINUX_ENVIRONMENT,
    NavDestinations.TERMUX_CONFIG,
    NavDestinations.SSH_CONFIG,
    NavDestinations.TAILSCALE_CONFIG,
    NavDestinations.NETWORK_ACCESS,
    NavDestinations.BACKGROUND_ACTIVITY,
    NavDestinations.KEYCHAIN,
    NavDestinations.TOKEN_USAGE,
    NavDestinations.BACKUP_SETTINGS,
    NavDestinations.DEVELOPER,
)

/** 能在右列内打开的设置页（路由的首段）；终端、文件、知识图谱、会话等整页内容不在其中。 */
private val SettingsPaneRouteBases: Set<String> = SettingsPaneRoots.toSet() + setOf(
    NavDestinations.RAG_VECTOR_STATS,
    "provider_form",
    "provider_models",
    NavDestinations.LINUX_PACKAGES,
    NavDestinations.LINUX_MIRRORS,
    "visual_lab",
    "keychain_entry",
    NavDestinations.KEYCHAIN_SSH_KEYS,
    "keychain_edit",
)

internal fun isSettingsPaneRoute(route: String): Boolean =
    route.substringBefore('?').substringBefore('/') in SettingsPaneRouteBases

/**
 * 把路由模板与参数还原为可再次导航的具体路由：路径参数直接代入，可空查询参数为 null 时整项去掉。
 * 例如 `provider_form?providerId={providerId}` 与 providerId=extra_1 得到 `provider_form?providerId=extra_1`。
 */
internal fun fillRoutePattern(
    pattern: String,
    encode: (String) -> String = android.net.Uri::encode,
    argument: (String) -> String?,
): String {
    val path = pattern.substringBefore('?')
    val filledPath = PLACEHOLDER.replace(path) { match -> encode(argument(match.groupValues[1]).orEmpty()) }
    val query = pattern.substringAfter('?', "")
    if (query.isEmpty()) return filledPath
    val params = query.split('&').mapNotNull { pair ->
        val key = pair.substringBefore('=')
        val placeholder = PLACEHOLDER.find(pair.substringAfter('=', ""))
        if (placeholder == null) pair else argument(placeholder.groupValues[1])?.let { "$key=${encode(it)}" }
    }
    return if (params.isEmpty()) filledPath else filledPath + "?" + params.joinToString("&")
}

internal fun NavBackStackEntry.concreteRoute(): String? {
    val pattern = destination.route ?: return null
    val args = arguments
    @Suppress("DEPRECATION")
    return fillRoutePattern(pattern) { name -> args?.get(name)?.toString() }
}

private val PLACEHOLDER = Regex("\\{([^}]+)\\}")

/**
 * 主导航从设置标签进入的整页设置子页（主标签页之上全部是右列可承载的页面、且最底下是设置首页条目）：
 * 窗口变宽时把它们收回右列。返回要交给右列的具体路由，不满足条件时返回 null。
 */
internal fun settingsPagesAboveMainTabs(routesAboveMain: List<String?>): List<String>? {
    if (routesAboveMain.isEmpty() || routesAboveMain.any { it == null || !isSettingsPaneRoute(it) }) return null
    val routes = routesAboveMain.filterNotNull()
    return routes.takeIf { it.first() in SettingsPaneRoots && it.first() !in OpenedFromSessionRoots }
}

/**
 * 首页“执行环境与连接”各页也能从会话里直接打开（例如可达范围跳网络访问），
 * 只凭栈底分不清来源，变宽时不收回。
 */
private val OpenedFromSessionRoots: Set<String> = setOf(
    NavDestinations.LINUX_ENVIRONMENT,
    NavDestinations.TERMUX_CONFIG,
    NavDestinations.SSH_CONFIG,
    NavDestinations.TAILSCALE_CONFIG,
    NavDestinations.NETWORK_ACCESS,
    NavDestinations.BACKGROUND_ACTIVITY,
)

/** 返回栈的逐层操作：公开的 NavController 接口只能看到栈顶与其下一层，收回整页时逐层弹出。 */
internal interface SettingsBackStack {
    /** 栈顶页面的具体路由；没有页面时为 null。 */
    val topRoute: String?
    val hasPrevious: Boolean
    fun pop()
    fun push(route: String)
}

/**
 * 窗口变宽时收回主标签页之上的整页设置子页：逐层弹出右列可承载的页面直到主标签页，
 * 返回它们（底层在前）。中途遇到其它页面、到不了主标签页或底层不是设置首页条目时，按原顺序压回并返回 null。
 */
internal fun SettingsBackStack.takeSettingsPagesAboveMainTabs(): List<String>? =
    takePagesAboveMainTabs(::isSettingsPaneRoute) { settingsPagesAboveMainTabs(it) }

/**
 * 逐层弹出 [accept] 认可的页面直到主标签页，交给 [collect] 判定（底层在前）；
 * 中途遇到其它页面、到不了主标签页或 [collect] 返回 null 时，按原顺序压回并返回 null。
 */
internal fun SettingsBackStack.takePagesAboveMainTabs(
    accept: (String) -> Boolean,
    collect: (List<String>) -> List<String>?,
): List<String>? {
    val popped = ArrayList<String>()
    var reachedMainTabs = false
    while (true) {
        val route = topRoute ?: break
        if (route == NavDestinations.MAIN_TAB_SCAFFOLD) {
            reachedMainTabs = true
            break
        }
        if (!accept(route) || !hasPrevious) break
        popped += route
        pop()
    }
    val pages = if (reachedMainTabs) collect(popped.asReversed()) else null
    if (pages == null) popped.asReversed().forEach(::push)
    return pages
}

internal class NavControllerSettingsBackStack(private val nav: NavController) : SettingsBackStack {
    override val topRoute: String? get() = nav.currentBackStackEntry?.concreteRoute()
    override val hasPrevious: Boolean get() = nav.previousBackStackEntry != null
    override fun pop() {
        nav.popBackStack()
    }
    override fun push(route: String) {
        nav.navigate(route)
    }
}

/** 右列返回栈中的一层：条目 id 区分同一路由的不同实例。 */
internal data class SettingsPaneEntry(val id: String, val route: String)

/** 右列层级跨 Activity 重建保存：条目 id 与路由交替排列。 */
internal val SettingsPaneEntriesSaver = androidx.compose.runtime.saveable.listSaver<List<SettingsPaneEntry>, String>(
    save = { entries -> entries.flatMap { listOf(it.id, it.route) } },
    restore = { flat -> flat.chunked(2) { SettingsPaneEntry(it[0], it[1]) } },
)

/**
 * 右列栈顶变化后的层级：[current] 已在层级中即为后退，截到它为止；否则接在 [previousId] 之后
 * （没有下一层时 [current] 就是新的根）。
 */
internal fun trackSettingsPaneStack(
    stack: List<SettingsPaneEntry>,
    current: SettingsPaneEntry,
    previousId: String?,
): List<SettingsPaneEntry> {
    val existing = stack.indexOfFirst { it.id == current.id }
    if (existing >= 0) return stack.take(existing + 1)
    if (previousId == null) return listOf(current)
    val previous = stack.indexOfFirst { it.id == previousId }
    return if (previous >= 0) stack.take(previous + 1) + current else stack + current
}

/** [entryId] 是否为栏内层级的根页面：栏内换到新的根页面（换设置根页、换会话）属于同级替换，不做视差推入。 */
internal fun isPaneRootEntry(stack: List<SettingsPaneEntry>, entryId: String): Boolean =
    stack.firstOrNull()?.id == entryId
