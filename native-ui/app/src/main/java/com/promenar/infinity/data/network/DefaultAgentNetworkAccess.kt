package com.promenar.infinity.data.network

import com.promenar.infinity.data.proxy.BuiltInProxyController
import com.promenar.infinity.data.proxy.BuiltInProxyState
import com.promenar.infinity.data.tailnet.TailnetController
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetSettings
import com.promenar.infinity.data.tailnet.TailnetState
import com.promenar.infinity.domain.network.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn

class DefaultAgentNetworkAccess(
    private val tailnet: TailnetController,
    scope: CoroutineScope,
    private val builtInProxy: BuiltInProxyController? = null,
) : AgentNetworkAccess {
    private val proxyState = builtInProxy?.state ?: MutableStateFlow(BuiltInProxyState())
    override val snapshot: StateFlow<AgentNetworkSnapshot> = combine(
        tailnet.state, tailnet.settings, proxyState, ::snapshot,
    ).stateIn(scope, SharingStarted.Eagerly, snapshot(tailnet.state.value, tailnet.settings.value, proxyState.value))

    private fun snapshot(state: TailnetState, options: TailnetSettings, proxy: BuiltInProxyState) = AgentNetworkSnapshot(
        tailnetAvailable = tailnet.available,
        tailnetEnabled = options.enabled,
        tailnetRunning = state.phase == TailnetPhase.RUNNING,
        rows = AgentNetworkPolicy.rows,
        builtInProxyAvailable = builtInProxy?.available == true,
        builtInProxyEnabled = proxy.enabled,
        builtInProxyRunning = proxy.running,
    )
}
