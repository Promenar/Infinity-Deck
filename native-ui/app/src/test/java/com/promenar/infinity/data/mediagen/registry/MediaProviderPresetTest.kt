package com.promenar.infinity.data.mediagen.registry

import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.ui.settings.PROVIDER_PRESETS
import com.promenar.infinity.ui.settings.matchProviderPreset
import com.promenar.infinity.ui.settings.isProviderEndpointAllowed
import org.junit.Assert.*
import org.junit.Test

class MediaProviderPresetTest {
    @Test fun genericProvidersKeepCustomProtocolControls() {
        assertEquals("Custom", matchProviderPreset(PROVIDER_PRESETS, ProtocolType.Generic_OpenAI_Compat, "https://other.test/v1")!!.name)
        assertEquals("Custom", matchProviderPreset(PROVIDER_PRESETS, ProtocolType.Generic_OpenAI_Compat, "https://[invalid")!!.name)
        val minimax = PROVIDER_PRESETS.single { it.name == "MiniMax" }
        assertTrue(isProviderEndpointAllowed(minimax.protocolType, minimax.defaultBaseUrl))
        assertEquals("MiniMax", matchProviderPreset(PROVIDER_PRESETS, ProtocolType.Generic_OpenAI_Compat, "https://api.minimax.io/v1")!!.name)
        assertEquals("MiniMax", matchProviderPreset(PROVIDER_PRESETS, ProtocolType.Generic_OpenAI_Compat, "https://api.minimaxi.com")!!.name)
        assertEquals("豆包", matchProviderPreset(PROVIDER_PRESETS, ProtocolType.Doubao_ByteDance, "https://ark.cn-beijing.volces.com/api")!!.name)
    }
}
