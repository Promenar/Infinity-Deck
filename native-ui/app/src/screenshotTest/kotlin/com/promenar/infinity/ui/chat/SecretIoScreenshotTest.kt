package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.common.StaticMenuPanel
import com.promenar.infinity.domain.secret.PendingSecretInput
import com.promenar.infinity.domain.secret.SecretDestination
import com.promenar.infinity.domain.secret.SecretExposure
import com.promenar.infinity.domain.secret.SecretInputRequest
import com.promenar.infinity.domain.secret.SecretKind
import com.promenar.infinity.domain.secret.SecretOutputCard
import com.promenar.infinity.domain.secret.SecretSlot
import com.promenar.infinity.domain.secret.SecretSlotState
import com.promenar.infinity.domain.secret.SecretSource
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private const val PreviewNow = 0L
private const val PreviewExpiry = 600_000L

private fun pendingSecret(
    purpose: String,
    kind: SecretKind,
    destination: SecretDestination,
    label: String,
    exposure: SecretExposure = SecretExposure.APP_ONLY,
    needsUsername: Boolean = false,
    offersGitHub: Boolean = false,
) = PendingSecretInput(
    sessionId = "preview",
    assistantMessageId = "a1",
    toolCallId = "call-$label",
    request = SecretInputRequest(purpose, kind, destination),
    destinationLabel = label,
    exposure = exposure,
    needsUsername = needsUsername,
    offersGitHubBrowserLogin = offersGitHub,
    expiresAtMillis = PreviewExpiry,
)

private val GitInput = pendingSecret(
    purpose = "需要 GitHub 令牌来创建 Release 并上传安装包。",
    kind = SecretKind.TOKEN,
    destination = SecretDestination.GitCredential("github.com"),
    label = "设置 › Git 凭据 · github.com",
    needsUsername = true,
    offersGitHub = true,
)

private val JobInput = pendingSecret(
    purpose = "命令在等待部署密钥的旧口令。",
    kind = SecretKind.PASSPHRASE,
    destination = SecretDestination.JobInput("3"),
    label = "任务 #3 的终端输入（自动回车）",
)

private val EnvInput = pendingSecret(
    purpose = "运行评测脚本需要 OpenAI API Key。",
    kind = SecretKind.API_KEY,
    destination = SecretDestination.SessionEnv("OPENAI_API_KEY"),
    label = "\$OPENAI_API_KEY",
    exposure = SecretExposure.COMMAND_READABLE,
)

private val EnglishInput = pendingSecret(
    purpose = "A GitHub token is needed to create the release and upload the APK.",
    kind = SecretKind.TOKEN,
    destination = SecretDestination.GitCredential("github.com"),
    label = "Settings › Git credentials · github.com",
    needsUsername = true,
    offersGitHub = true,
)

private fun outputCard(purpose: String, label: String, state: SecretSlotState = SecretSlotState.AVAILABLE) = SecretOutputCard(
    sessionId = "preview",
    assistantMessageId = "a1",
    toolCallId = "call-out",
    slot = SecretSlot("preview", "GITEA_ADMIN_PASSWORD", label, SecretSource.CommandStdout("openssl rand"), 32, state),
    purpose = purpose,
)

private val GiteaCard = outputCard("Gitea 管理员 admin 的初始密码。", "Gitea 管理员初始密码")
private val EnglishCard = outputCard("Initial password for the Gitea admin account.", "Gitea admin password")
/** 应用重启后从会话记录恢复的卡片：长度未知，值已清除。 */
private val ClearedCard = GiteaCard.copy(slot = GiteaCard.slot.copy(byteLength = 0, state = SecretSlotState.CLEARED))

private const val GuardText = "我的令牌是 ghp_8xQ2aB3dE5fG7hJ9kL1mN2pQ4rS6tU8vW0xY 请帮我查仓库"

@Composable
private fun SecretFrame(dark: Boolean = true, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

@Composable
private fun OutputCardPreview(card: SecretOutputCard, revealed: String? = null, copied: Boolean = false) =
    SecretOutputCardContent(
        card = card,
        revealedText = revealed,
        secondsLeft = 28,
        copied = copied,
        notice = null,
        onReveal = {},
        onHide = {},
        onCopy = {},
        onSaveAs = {},
        onDestroy = {},
    )

@PreviewTest
@Preview(name = "保密输入卡 Git 凭据 待输入 深中411", widthDp = 411, heightDp = 760, locale = "zh-rCN")
@Composable
fun secretInputGitDarkPreview() = SecretFrame {
    SecretInputCard(GitInput, onSubmit = { _, _ -> true }, onCancel = {}, onGitHubBrowserLogin = {}, nowMillis = { PreviewNow })
}

@PreviewTest
@Preview(name = "保密输入卡 本会话环境变量与任务终端 浅中411", widthDp = 411, heightDp = 1180, locale = "zh-rCN")
@Composable
fun secretInputEnvAndJobLightPreview() = SecretFrame(dark = false) {
    SecretInputCard(EnvInput, onSubmit = { _, _ -> true }, onCancel = {}, nowMillis = { PreviewNow })
    SecretInputCard(JobInput, onSubmit = { _, _ -> true }, onCancel = {}, error = "任务 #3 已结束", nowMillis = { PreviewNow })
}

@PreviewTest
@Preview(name = "保密输入回执 已保存 取消 过期 深中411", widthDp = 411, heightDp = 300, locale = "zh-rCN")
@Composable
fun secretInputReceiptsDarkPreview() = SecretFrame {
    SecretInputReceiptCard(SecretInputReceipt("preview", "a1", "c1", SecretInputReceipt.Status.SAVED, "设置 › Git 凭据 · github.com", "Promenar"))
    SecretInputReceiptCard(SecretInputReceipt("preview", "a1", "c2", SecretInputReceipt.Status.SAVED, "\$OPENAI_API_KEY"))
    SecretInputReceiptCard(SecretInputReceipt("preview", "a1", "c3", SecretInputReceipt.Status.CANCELLED, ""))
    SecretInputReceiptCard(SecretInputReceipt("preview", "a1", "c4", SecretInputReceipt.Status.EXPIRED, ""))
}

@PreviewTest
@Preview(name = "保密输出卡 遮蔽 显示 失效记录 深中411", widthDp = 411, heightDp = 1080, locale = "zh-rCN")
@Composable
fun secretOutputCardDarkPreview() = SecretFrame {
    OutputCardPreview(GiteaCard)
    OutputCardPreview(GiteaCard, revealed = "Xq7r-Lm2V9pWt4Zs8KcN0bYhEe", copied = true)
    SecretOutputSpentRow(GiteaCard, SecretSlotState.DESTROYED)
    SecretOutputSpentRow(ClearedCard, SecretSlotState.CLEARED)
    SecretOutputSpentRow(GiteaCard, SecretSlotState.REPLACED)
}

// 遮蔽状态：验证后显示为主色胶囊，复制为中性胶囊，保存为凭据与销毁收进行尾“更多”菜单（静态浮层示意展开）。
@PreviewTest
@Preview(name = "保密输出卡与令牌提醒 更多菜单 浅中360", widthDp = 360, heightDp = 1180, locale = "zh-rCN")
@Composable
fun secretOutputMenuLightPreview() = SecretFrame(dark = false) {
    OutputCardPreview(GiteaCard)
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        StaticMenuPanel(minWidth = 200.dp) {
            SecretOutputMoreMenuItems(onSaveAs = {}, onDestroy = {})
        }
    }
    ComposerSecretGuardContent(
        text = GuardText, matches = detectComposerSecrets(GuardText), modelName = "Qwen3.8 Flash Next", defaultUsername = "",
        saving = false, saveFailed = false, onSaveToGit = { _, _ -> }, onEdit = {}, onSendAnyway = {},
    )
}

@PreviewTest
@Preview(name = "保存为凭据面板 Git 浅中411", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Composable
fun secretSaveAsLightPreview() = SecretFrame(dark = false) {
    SecretSaveAsContent(
        targets = SecretSaveTargets(gitHosts = listOf("github.com" to "Promenar")),
        saving = false,
        error = null,
        onConfirm = { _, _, _ -> },
    )
}

@PreviewTest
@Preview(name = "输入框令牌提醒 三个选项与保存表单 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun composerSecretGuardDarkPreview() = SecretFrame {
    val matches = detectComposerSecrets(GuardText)
    ComposerSecretGuardContent(
        text = GuardText, matches = matches, modelName = "Qwen3.8 Flash Next", defaultUsername = "",
        saving = false, saveFailed = false, onSaveToGit = { _, _ -> }, onEdit = {}, onSendAnyway = {},
    )
    ComposerSecretGuardContent(
        text = GuardText, matches = matches, modelName = "Qwen3.8 Flash Next", defaultUsername = "Promenar",
        saving = false, saveFailed = true, onSaveToGit = { _, _ -> }, onEdit = {}, onSendAnyway = {},
        initiallySaving = true,
    )
}

@PreviewTest
@Preview(name = "Secret cards English 360dp font130", widthDp = 360, heightDp = 2100, locale = "en", fontScale = 1.3f)
@Composable
fun secretIoEnglishLargeFontPreview() = SecretFrame {
    SecretInputCard(EnglishInput, onSubmit = { _, _ -> true }, onCancel = {}, onGitHubBrowserLogin = {}, nowMillis = { PreviewNow })
    OutputCardPreview(EnglishCard)
    OutputCardPreview(EnglishCard, revealed = "Xq7r-Lm2V9pWt4Zs8KcN0bYhEe", copied = true)
    SecretOutputSpentRow(EnglishCard, SecretSlotState.CLEARED)
    ComposerSecretGuardContent(
        text = GuardText, matches = detectComposerSecrets(GuardText), modelName = "Opus 5.5", defaultUsername = "",
        saving = false, saveFailed = false, onSaveToGit = { _, _ -> }, onEdit = {}, onSendAnyway = {},
    )
}
