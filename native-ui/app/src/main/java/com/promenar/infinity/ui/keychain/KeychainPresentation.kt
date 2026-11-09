package com.promenar.infinity.ui.keychain

import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainItem
import java.security.SecureRandom

/** 列表分组顺序：钥匙串自己的三类在前，其后是汇总的既有密钥。 */
internal val KeychainCategoryOrder: List<KeychainCategory> = listOf(
    KeychainCategory.CUSTOM,
    KeychainCategory.WEB_LOGIN,
    KeychainCategory.SSH_KEY,
    KeychainCategory.GIT,
    KeychainCategory.SSH_PASSWORD,
    KeychainCategory.PROVIDER,
    KeychainCategory.SERVICE,
)

internal data class KeychainGroup(val category: KeychainCategory, val items: List<KeychainItem>)

/** 按类别筛选并按关键字匹配名称、用户名、网址与备注（不区分大小写），结果按 [KeychainCategoryOrder] 分组。 */
internal fun groupKeychainItems(items: List<KeychainItem>, filter: KeychainCategory?, query: String): List<KeychainGroup> {
    val needle = query.trim().lowercase()
    val matched = items.filter { item ->
        (filter == null || item.category == filter) &&
            (needle.isEmpty() || listOfNotNull(item.name, item.username, item.url, item.note).any { it.lowercase().contains(needle) })
    }
    return KeychainCategoryOrder.mapNotNull { category ->
        matched.filter { it.category == category }.takeIf { it.isNotEmpty() }?.let { KeychainGroup(category, it) }
    }
}

internal fun keychainCounts(items: List<KeychainItem>): Map<KeychainCategory, Int> = items.groupingBy { it.category }.eachCount()

/** 值能在钥匙串里查看与复制的类别；SSH 私钥只在密钥页导出。 */
internal fun KeychainItem.hasRevealableValue(): Boolean = category != KeychainCategory.SSH_KEY

/** 名称、网址、用户名、值与备注都在钥匙串里编辑的类别。 */
internal fun KeychainItem.editableInKeychain(): Boolean =
    category == KeychainCategory.CUSTOM || category == KeychainCategory.WEB_LOGIN

/** 提供商可能同时有 API Key 与 Vertex 服务账号，各自是一个值。 */
internal fun KeychainItem.valueRefs(): List<String> = secretRefs.ifEmpty { listOf(ref) }

/** 提供商条目的分量是 Vertex 服务账号还是 API Key。 */
internal fun isVertexValueRef(valueRef: String): Boolean = valueRef.removePrefix("secret:").startsWith("vertex_service_account:")

/** 汇总条目在应用里的原设置页。 */
enum class KeychainSettingsLink { SSH_HOSTS, PROVIDERS, BUILTIN_TOOLS, DEFAULT_MODELS, BACKUP, TAILSCALE, MCP }

internal fun KeychainItem.settingsLink(): KeychainSettingsLink? = when (category) {
    KeychainCategory.SSH_PASSWORD -> KeychainSettingsLink.SSH_HOSTS
    KeychainCategory.PROVIDER -> KeychainSettingsLink.PROVIDERS
    KeychainCategory.SERVICE -> {
        val id = ref.removePrefix("secret:")
        when {
            id == "tavily_api_key" || id == "keenable_api_key" -> KeychainSettingsLink.BUILTIN_TOOLS
            id == "embedding_api_key" -> KeychainSettingsLink.DEFAULT_MODELS
            id.startsWith("webdav_") || id == "automatic_backup_password" -> KeychainSettingsLink.BACKUP
            id == "tailnet_auth_key_v1" -> KeychainSettingsLink.TAILSCALE
            id.startsWith("mcp_bearer_v1:") -> KeychainSettingsLink.MCP
            else -> null
        }
    }
    else -> null
}

/** 自动备份口令永不随钥匙串导出。 */
internal fun KeychainItem.excludedFromExport(): Boolean = ref == "secret:automatic_backup_password"

/** Git 汇总条目的主机名（引用为 `git:<host>`）。 */
internal fun KeychainItem.gitHost(): String? = ref.takeIf { category == KeychainCategory.GIT }?.removePrefix("git:")

private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
private const val DIGITS = "23456789"
private const val SYMBOLS = "!#$%&*+-=?@"

/** 生成随机密码：去掉易混字符，大写、小写、数字与符号各至少一个。返回的数组由调用方清零。 */
internal fun generateKeychainPassword(length: Int = 20, random: SecureRandom = SecureRandom()): CharArray {
    require(length >= 4)
    val all = UPPER + LOWER + DIGITS + SYMBOLS
    val out = CharArray(length) { all[random.nextInt(all.length)] }
    // 前四位各取一类，再整体洗牌，保证每类至少出现一次且位置随机。
    listOf(UPPER, LOWER, DIGITS, SYMBOLS).forEachIndexed { index, set -> out[index] = set[random.nextInt(set.length)] }
    for (i in out.indices.reversed()) {
        val j = random.nextInt(i + 1)
        val t = out[i]; out[i] = out[j]; out[j] = t
    }
    return out
}
