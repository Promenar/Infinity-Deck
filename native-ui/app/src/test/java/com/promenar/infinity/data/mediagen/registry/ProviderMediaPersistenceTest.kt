package com.promenar.infinity.data.mediagen.registry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.model.catalog.*
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.security.*
import com.promenar.infinity.domain.mediagen.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ProviderMediaPersistenceTest {
    private class Secrets : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun get(id: SecretId) = values[id]?.copyOf()
        override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
        override fun contains(id: SecretId) = id in values
        override fun remove(id: SecretId) { values.remove(id) }
    }
    @Test fun modelSyncSharesProviderAndPreservesUserInputSettingsAcrossReload() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        listOf("infinity_provider", "infinity_settings", "infinity_provider_status").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        val secrets = Secrets()
        val pm = ProviderManager.createForTest(app, secrets)
        pm.updateMainProvider(ProtocolType.Generic_OpenAI_Compat, "https://api.minimax.io", CredentialUpdate.Replace("fake-test"), "MiniMax-M3", "MiniMax")
        pm.syncModelMetadata("default", "MiniMax", "MiniMax-H3")
        pm.syncModelMetadata("default", "MiniMax", "speech-2.8-hd")
        val video = pm.providerModels.value.single { it.remoteModelId == "MiniMax-H3" }
        assertEquals("video", video.type)
        assertEquals(SupportState.UNSUPPORTED, video.chatEndpointCompatible)
        assertFalse(video.enabled)
        val edited = video.effectiveMediaSettings()!!.withCharacterReferences(CharacterReferenceMode.COMPOSITE, listOf("face", "body"))
        pm.setMediaModelSettings(video.id, edited)
        pm.toggleModel(video.id)
        pm.syncModelMetadata("default", "MiniMax", "MiniMax-H3")
        val restored = ProviderManager.createForTest(app, secrets).providerModels.value.single { it.id == video.id }
        assertEquals(edited, restored.mediaSettings)
        assertTrue(restored.enabled)
        assertEquals("default", restored.providerId)
        assertEquals(3, pm.providerModels.value.count { it.providerId == "default" })
        assertFalse(app.getSharedPreferences("infinity_settings", 0).all.values.any { it.toString().contains("fake-test") })
    }
    @Test fun invalidStoredSettingsDisableChannelUntilUserRepairsThem() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        listOf("infinity_provider", "infinity_settings", "infinity_provider_status").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        val secrets = Secrets()
        val pm = ProviderManager.createForTest(app, secrets)
        pm.updateMainProvider(ProtocolType.Generic_OpenAI_Compat, "https://api.minimax.io", CredentialUpdate.Replace("fake-test"), "MiniMax-H3", "MiniMax")
        val id = pm.providerModels.value.single().id
        assertTrue(pm.providerModels.value.single().enabled)
        app.getSharedPreferences("infinity_settings", 0).edit().putString("model_info_${id}_media_generation", "broken").commit()
        pm.loadModels()
        assertTrue(pm.providerModels.value.single().mediaSettingsInvalid)
        assertNull(pm.providerModels.value.single().toMediaChannel())
        assertEquals("broken", app.getSharedPreferences("infinity_settings", 0).getString("model_info_${id}_media_generation", null))
        pm.setMediaModelSettings(id, mediaModelPreset("MiniMax-H3"))
        assertNotNull(pm.providerModels.value.single().toMediaChannel())
    }

    @Test fun catalogIdentifiesGenerationAndLeavesDialogueIntact() {
        val resolver = ModelMetadataResolver()
        assertEquals(ModelWorkload.VIDEO, resolver.resolve("MiniMax-H3").workload)
        assertEquals(ModelWorkload.AUDIO, resolver.resolve("speech-2.8-hd").workload)
        assertEquals(ModelWorkload.GENERATIVE_TEXT, resolver.resolve("minimax-m3").workload)
        assertEquals(SupportState.UNSUPPORTED, resolver.resolve("doubao-seedance-2-0-user-snapshot").capabilities[ModelCapability.CHAT_ENDPOINT])
        assertNull(mediaModelPreset("ep-user-endpoint"))
    }
}
