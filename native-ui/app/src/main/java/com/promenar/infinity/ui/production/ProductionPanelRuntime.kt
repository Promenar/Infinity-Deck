package com.promenar.infinity.ui.production

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.production.ProductionPanelSource
import com.promenar.infinity.data.production.ProductionSourceRef
import kotlinx.coroutines.flow.collect

/** 两种会话面板宿主共用装配；安装状态初始为空，首帧无需等待启动恢复。 */
@Composable
fun rememberProductionPanelServices(
    sessionId: String,
    source: ProductionPanelSource,
    openRoute: ((String) -> Unit)?,
    sendPanelMessage: (suspend (String, com.promenar.infinity.data.chat.queue.PanelMessageOrigin) -> com.promenar.infinity.ui.chat.PanelSendResult)? = null,
): ProductionPanelServices {
    val app = LocalContext.current.applicationContext as? InfinityApplication ?: return ProductionPanelServices()
    val runtime by app.installedProductionMediaRuntime.collectAsStateWithLifecycle()
    val conflict = stringResource(R.string.production_wire_conflict)
    val invalid = stringResource(R.string.production_wire_invalid_content)
    val failed = stringResource(R.string.production_panel_action_failed)
    val labels by produceState<Map<String, String>>(emptyMap(), runtime) {
        value = emptyMap()
        if (runtime != null) {
            val providers = ProviderManager.getInstance()
            kotlinx.coroutines.flow.combine(
                requireNotNull(runtime).registry.observeChannels(),
                providers.providerModels,
                app.comfyMedia.repository.observeTemplates(),
            ) { channels, models, templates ->
                val names = models.associate { "cloud:${it.id}" to "${it.name} · ${it.providerName}" } +
                    templates.associate { it.template.channelId to it.template.name }
                channels.associate { it.id to (names[it.id] ?: it.id) } + names
            }.collect { value = it }
        }
    }
    val pending by produceState<Set<String>>(emptySet(), runtime) {
        value = emptySet()
        runtime?.pendingRegistrations?.collect { value = it }
    }
    return remember(runtime, labels, pending, sessionId, source, openRoute, sendPanelMessage, conflict, invalid, failed) {
        val dispatcher = app.productionReviewDispatcher
        val sendReview: (suspend () -> com.promenar.infinity.data.production.ReviewDispatch)? =
            if (sendPanelMessage == null) null else suspend { dispatcher.send(sessionId) { text, origin -> sendPanelMessage(text, origin) } }
        ProductionPanelServices(
            sendReview = sendReview,
            reconcileReviews = { dispatcher.reconcile(sessionId) },
            auditFiles = { app.productionFileAudit.scan(sessionId) },
            cutExport = app.productionCutRuntime.controller,
            planner = runtime?.let { app.shotGenerationPlanner },
            queue = runtime?.let { app.mediaGenerationQueue },
            registry = runtime?.let { app.mediaChannelRegistry },
            projectSourcePrefix = { ProductionSourceRef.projectPrefix(it.id) },
            shotSourceRef = { production, location -> ProductionSourceRef(production.id, location.episode.id, location.shot.code).encode() },
            channelLabel = { labels[it] ?: it },
            openRoute = openRoute,
            requestContentChange = { revision, location, proposed ->
                requestShotContentChange(source, sessionId, revision, location, proposed, conflict, invalid, failed)
            },
            pendingRegistrations = pending,
        )
    }
}
