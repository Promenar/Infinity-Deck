package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import com.promenar.infinity.ui.theme.forInput
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing

// ── 输入框密钥提醒：检测器（纯函数，界面与测试共用）──

/** 输入框里识别出的疑似密钥种类；只有 GitHub 令牌提供“保存到 Git 凭据”。 */
internal enum class ComposerSecretKind(val gitHubToken: Boolean = false) {
    /** `ghp_`/`gho_`/`ghu_`/`ghs_`/`ghr_` 开头的经典令牌。 */
    GITHUB_TOKEN(gitHubToken = true),
    /** `github_pat_` 开头的细粒度令牌。 */
    GITHUB_FINE_GRAINED(gitHubToken = true),
    /** `sk-`、`sk-proj-`、`sk-ant-` 等模型服务商密钥。 */
    API_KEY,
    /** `AKIA` 开头的 AWS 访问密钥 ID。 */
    AWS_ACCESS_KEY,
    /** PEM 私钥块。 */
    PRIVATE_KEY,
}

/** 一处命中：种类与在原文中的区间（含首尾）。不携带命中内容，调用方按区间自行取用。 */
internal data class ComposerSecretMatch(val kind: ComposerSecretKind, val range: IntRange)

private val GitHubClassic = Regex("(?<![A-Za-z0-9_])gh[pousr]_([A-Za-z0-9]{36,251})(?![A-Za-z0-9_])")
private val GitHubFineGrained = Regex("(?<![A-Za-z0-9_])github_pat_([A-Za-z0-9_]{70,240})(?![A-Za-z0-9_])")
private val SkKey = Regex("(?<![A-Za-z0-9_-])sk-(?:proj-|ant-(?:api\\d{2}-)?|svcacct-|admin-)?([A-Za-z0-9_-]{32,256})(?![A-Za-z0-9_-])")
private val AwsAccessKey = Regex("(?<![A-Z0-9])AKIA([A-Z0-9]{16})(?![A-Z0-9])")
private val PrivateKeyBegin = Regex("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----")
private val PrivateKeyEnd = Regex("-----END (?:[A-Z0-9]+ )*PRIVATE KEY-----")

/**
 * 找出文本中的高置信度密钥。误报边界：
 * - 只认固定前缀加足够长度的格式，普通的 `sk-` 单词、`ghp_xxx` 这类短占位符不会命中；
 * - 主体字符种类过少（如 36 个 x、全 0）视为文档占位符；`sk-` 主体还须同时含字母与数字，排除连字符串起的长单词；
 * - AWS 文档示例 `…EXAMPLE` 不算；
 * - 私钥只认 BEGIN 行，块延伸到对应 END 行，没有 END 时到文末。
 * 返回按起点排序、互不重叠的区间。
 */
internal fun detectComposerSecrets(text: String): List<ComposerSecretMatch> {
    if (text.length < 20) return emptyList()
    val found = mutableListOf<ComposerSecretMatch>()
    GitHubClassic.findAll(text).forEach { match ->
        if (looksRandom(match.groupValues[1])) found += ComposerSecretMatch(ComposerSecretKind.GITHUB_TOKEN, match.range)
    }
    GitHubFineGrained.findAll(text).forEach { match ->
        if (looksRandom(match.groupValues[1])) found += ComposerSecretMatch(ComposerSecretKind.GITHUB_FINE_GRAINED, match.range)
    }
    SkKey.findAll(text).forEach { match ->
        val body = match.groupValues[1]
        if (body.any(Char::isDigit) && body.any(Char::isLetter) && looksRandom(body)) {
            found += ComposerSecretMatch(ComposerSecretKind.API_KEY, match.range)
        }
    }
    AwsAccessKey.findAll(text).forEach { match ->
        val body = match.groupValues[1]
        if (!body.endsWith("EXAMPLE") && looksRandom(body, minDistinct = 6)) {
            found += ComposerSecretMatch(ComposerSecretKind.AWS_ACCESS_KEY, match.range)
        }
    }
    PrivateKeyBegin.findAll(text).forEach { begin ->
        val end = PrivateKeyEnd.find(text, begin.range.last + 1)
        found += ComposerSecretMatch(ComposerSecretKind.PRIVATE_KEY, begin.range.first..(end?.range?.last ?: text.lastIndex))
    }
    // 去掉被更早、更长的命中覆盖的区间（例如私钥块内部恰好出现的 sk- 串）。
    val sorted = found.sortedWith(compareBy<ComposerSecretMatch> { it.range.first }.thenByDescending { it.range.last })
    val result = mutableListOf<ComposerSecretMatch>()
    sorted.forEach { match -> if (result.lastOrNull()?.let { match.range.first <= it.range.last } != true) result += match }
    return result
}

/** 随机串的粗判：不同字符数够多。真实令牌是 base62，36 位通常有 20 种以上字符。 */
private fun looksRandom(body: String, minDistinct: Int = 10): Boolean = body.toSet().size >= minDistinct

/** 把命中区间替换为 [replacement]（从后往前替换，区间不受前面替换影响）。 */
internal fun redactComposerSecrets(text: String, matches: List<ComposerSecretMatch>, replacement: String): String {
    val builder = StringBuilder(text)
    matches.sortedByDescending { it.range.first }.forEach { match ->
        if (match.range.first >= 0 && match.range.last < builder.length) {
            builder.replace(match.range.first, match.range.last + 1, replacement)
        }
    }
    return builder.toString()
}

/** 提醒面板上的识别示意：只露出前缀与前 4 位，其余以省略号代替。 */
internal fun composerSecretPreview(text: String, match: ComposerSecretMatch): String {
    val value = text.substring(match.range.first, match.range.last + 1)
    if (match.kind == ComposerSecretKind.PRIVATE_KEY) return value.lineSequence().first()
    val prefixEnd = when (match.kind) {
        ComposerSecretKind.GITHUB_TOKEN -> 4
        ComposerSecretKind.GITHUB_FINE_GRAINED -> "github_pat_".length
        ComposerSecretKind.AWS_ACCESS_KEY -> 4
        else -> value.indexOf('-', startIndex = 3).let { if (it in 3..10) it + 1 else 3 }
    }
    return value.take((prefixEnd + 4).coerceAtMost(value.length - 1)) + "…"
}

// ── 提醒面板 ──

/** 发送被拦下、等待用户决定的一次发送；[send] 是原本的发送动作。 */
internal class ComposerSecretGuardRequest(val text: String, val matches: List<ComposerSecretMatch>, val send: () -> Unit)

@Composable
private fun composerSecretTitle(kind: ComposerSecretKind): String = stringResource(
    when (kind) {
        ComposerSecretKind.GITHUB_TOKEN, ComposerSecretKind.GITHUB_FINE_GRAINED -> R.string.secret_guard_title_github
        ComposerSecretKind.API_KEY -> R.string.secret_guard_title_api_key
        ComposerSecretKind.AWS_ACCESS_KEY -> R.string.secret_guard_title_aws
        ComposerSecretKind.PRIVATE_KEY -> R.string.secret_guard_title_private_key
    },
)

/** 发送前识别到疑似密钥时的底部提醒面板；关闭面板等同“返回编辑”。 */
@Composable
internal fun ComposerSecretGuardSheet(
    request: ComposerSecretGuardRequest,
    modelName: String?,
    defaultUsername: String,
    saving: Boolean,
    saveFailed: Boolean,
    onSaveToGit: (host: String, username: String) -> Unit,
    onEdit: () -> Unit,
    onSendAnyway: () -> Unit,
) {
    InfinityBottomSheet(
        show = true,
        onDismiss = { if (!saving) onEdit() },
        title = composerSecretTitle(request.matches.first().kind),
        height = InfinitySheetHeight.Content,
        modifier = Modifier.testTag(UiTags.CHAT_SECRET_GUARD_SHEET),
    ) {
        ComposerSecretGuardContent(
            text = request.text,
            matches = request.matches,
            modelName = modelName,
            defaultUsername = defaultUsername,
            saving = saving,
            saveFailed = saveFailed,
            onSaveToGit = onSaveToGit,
            onEdit = onEdit,
            onSendAnyway = onSendAnyway,
        )
    }
}

/**
 * 提醒面板内容：说明发送后的去向、识别示意与三个选择。识别到 GitHub 令牌时首选项是保存到 Git 凭据，
 * 点开后填写主机与用户名再保存；保存成功由调用方把令牌替换为占位文字放回输入框，不自动发送。
 */
@Composable
internal fun ComposerSecretGuardContent(
    text: String,
    matches: List<ComposerSecretMatch>,
    modelName: String?,
    defaultUsername: String,
    saving: Boolean,
    saveFailed: Boolean,
    onSaveToGit: (host: String, username: String) -> Unit,
    onEdit: () -> Unit,
    onSendAnyway: () -> Unit,
    modifier: Modifier = Modifier,
    initiallySaving: Boolean = false,
) {
    val first = matches.firstOrNull() ?: return
    val offersGit = matches.any { it.kind.gitHubToken }
    var savingForm by remember { mutableStateOf(initiallySaving) }
    var host by remember { mutableStateOf("github.com") }
    var username by remember(defaultUsername) { mutableStateOf(defaultUsername) }
    Column(modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 4.dp)) {
        Text(
            buildString {
                append(modelName?.takeIf(String::isNotBlank)?.let { stringResource(R.string.secret_guard_body_model, it) } ?: stringResource(R.string.secret_guard_body))
                if (offersGit) append(stringResource(R.string.secret_guard_body_github))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                pluralStringResource(R.plurals.secret_guard_found, matches.size, composerSecretPreview(text, first), matches.size),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        if (offersGit && savingForm) {
            OutlinedTextField(
                value = host,
                textStyle = LocalTextStyle.current.forInput(),
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.secret_save_as_host)) },
                singleLine = true,
                enabled = !saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = username,
                textStyle = LocalTextStyle.current.forInput(),
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.secret_save_as_username)) },
                singleLine = true,
                enabled = !saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (saveFailed) {
                Text(
                    stringResource(R.string.secret_guard_save_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            InfinityPillButton(
                text = stringResource(R.string.secret_guard_save_confirm),
                onClick = { onSaveToGit(host.trim(), username.trim()) },
                tone = InfinityPillTone.PRIMARY,
                enabled = !saving && host.isNotBlank() && username.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag(UiTags.CHAT_SECRET_GUARD_SAVE_CONFIRM),
            )
        } else if (offersGit) {
            InfinityPillButton(
                text = stringResource(R.string.secret_guard_save_git),
                onClick = { savingForm = true },
                tone = InfinityPillTone.PRIMARY,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .testTag(UiTags.CHAT_SECRET_GUARD_SAVE_GIT),
            )
        }
        // 返回编辑与仍然发送并排：后者是有风险的选择，用错误色文字区分；放不下单行时按原顺序竖排。
        InfinityPillRow(
            modifier = Modifier.fillMaxWidth().padding(top = if (offersGit) 4.dp else 14.dp),
            reverseWhenStacked = false,
        ) {
            InfinityPillButton(
                text = stringResource(R.string.secret_guard_edit),
                onClick = onEdit,
                enabled = !saving,
                modifier = Modifier.testTag(UiTags.CHAT_SECRET_GUARD_EDIT),
            )
            InfinityPillButton(
                text = stringResource(R.string.secret_guard_send_anyway),
                onClick = onSendAnyway,
                tone = InfinityPillTone.DANGER,
                enabled = !saving,
                modifier = Modifier.testTag(UiTags.CHAT_SECRET_GUARD_SEND_ANYWAY),
            )
        }
    }
}
