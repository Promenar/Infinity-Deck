package com.promenar.infinity.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.data.remote.oauth.OpenRouterCallbackResult
import com.promenar.infinity.data.remote.oauth.OpenRouterExchangeResult
import com.promenar.infinity.data.remote.oauth.OpenRouterLoopbackServer
import com.promenar.infinity.data.remote.oauth.OpenRouterOAuth
import com.promenar.infinity.ui.settings.ProviderBadgeTone
import com.promenar.infinity.ui.settings.ProviderPresetBadge
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class OpenRouterOAuthFailureKind {
    NETWORK_OR_BLOCKED,
    REJECTED,
    STATE_MISMATCH,
    BROWSER_UNAVAILABLE,
}

sealed interface OpenRouterOAuthUiState {
    data object Idle : OpenRouterOAuthUiState
    data class WaitingBrowser(val authUrl: String) : OpenRouterOAuthUiState
    data object Exchanging : OpenRouterOAuthUiState
    data class Failed(val kind: OpenRouterOAuthFailureKind) : OpenRouterOAuthUiState
}

@Stable
class OpenRouterOAuthController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onApiKeyReceived: (String) -> Unit,
) {
    var uiState by mutableStateOf<OpenRouterOAuthUiState>(OpenRouterOAuthUiState.Idle)
        private set

    private var activeJob: Job? = null
    private var activeServer: OpenRouterLoopbackServer? = null

    fun start() {
        cancel()
        val server = try {
            OpenRouterLoopbackServer.bind()
        } catch (_: Exception) {
            uiState = OpenRouterOAuthUiState.Failed(OpenRouterOAuthFailureKind.NETWORK_OR_BLOCKED)
            return
        }
        activeServer = server
        val pkce = OpenRouterOAuth.generatePkcePair()
        val authUrl = OpenRouterOAuth.buildAuthorizationUrl(
            callbackUrl = server.callbackUrl,
            codeChallenge = pkce.codeChallenge,
            state = pkce.state,
        )
        if (!openExternalBrowserUrl(context, authUrl)) {
            server.close()
            activeServer = null
            uiState = OpenRouterOAuthUiState.Failed(OpenRouterOAuthFailureKind.BROWSER_UNAVAILABLE)
            return
        }
        uiState = OpenRouterOAuthUiState.WaitingBrowser(authUrl)
        activeJob = scope.launch {
            try {
                when (val callback = server.awaitAuthorizationCode(expectedState = pkce.state)) {
                    is OpenRouterCallbackResult.Authorized -> {
                        uiState = OpenRouterOAuthUiState.Exchanging
                        when (val exchange = OpenRouterOAuth.exchangeAuthorizationCode(callback.code, pkce.codeVerifier)) {
                            is OpenRouterExchangeResult.Success -> {
                                uiState = OpenRouterOAuthUiState.Idle
                                onApiKeyReceived(exchange.apiKey)
                            }
                            is OpenRouterExchangeResult.NetworkOrBlocked -> {
                                uiState = OpenRouterOAuthUiState.Failed(OpenRouterOAuthFailureKind.NETWORK_OR_BLOCKED)
                            }
                            is OpenRouterExchangeResult.Rejected, OpenRouterExchangeResult.InvalidResponse -> {
                                uiState = OpenRouterOAuthUiState.Failed(OpenRouterOAuthFailureKind.REJECTED)
                            }
                        }
                    }
                    OpenRouterCallbackResult.StateMismatch -> {
                        uiState = OpenRouterOAuthUiState.Failed(OpenRouterOAuthFailureKind.STATE_MISMATCH)
                    }
                    OpenRouterCallbackResult.TimedOut -> {
                        uiState = OpenRouterOAuthUiState.Failed(OpenRouterOAuthFailureKind.NETWORK_OR_BLOCKED)
                    }
                    OpenRouterCallbackResult.Cancelled -> {
                        if (uiState is OpenRouterOAuthUiState.WaitingBrowser) {
                            uiState = OpenRouterOAuthUiState.Idle
                        }
                    }
                    is OpenRouterCallbackResult.Failed -> {
                        uiState = OpenRouterOAuthUiState.Failed(OpenRouterOAuthFailureKind.REJECTED)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                server.close()
                if (activeServer === server) activeServer = null
            }
        }
    }

    fun reopenBrowser() {
        val current = uiState as? OpenRouterOAuthUiState.WaitingBrowser ?: return
        openExternalBrowserUrl(context, current.authUrl)
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        activeServer?.close()
        activeServer = null
        uiState = OpenRouterOAuthUiState.Idle
    }

    fun clearError() {
        if (uiState is OpenRouterOAuthUiState.Failed) {
            uiState = OpenRouterOAuthUiState.Idle
        }
    }
}

@Composable
fun rememberOpenRouterOAuthController(
    onApiKeyReceived: (String) -> Unit,
): OpenRouterOAuthController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestCallback by rememberUpdatedState(onApiKeyReceived)
    val controller = remember(context, scope) {
        OpenRouterOAuthController(
            context = context,
            scope = scope,
            onApiKeyReceived = { latestCallback(it) },
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }
    return controller
}

/** 安全拉起外部系统浏览器访问控制台或 OAuth 授权页。 */
fun openExternalBrowserUrl(context: Context, url: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
}.getOrDefault(false)

/** 预设名称后置的紧凑权益小徽章（一键登录 / 含免费模型 / 免费额度 / 每日免费 / 新客试用等）。 */
@Composable
fun ProviderPresetBadgeChip(
    badge: ProviderPresetBadge,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val (bg, fg) = when (badge.tone) {
        ProviderBadgeTone.PRIMARY -> scheme.primary.copy(alpha = 0.16f) to scheme.primary
        ProviderBadgeTone.SUCCESS -> domain.success.copy(alpha = 0.15f) to domain.success
        ProviderBadgeTone.NEUTRAL -> scheme.surfaceContainerHighest.copy(alpha = 0.90f) to scheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(badge.labelRes),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = fg,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
