package com.promenar.infinity.data.mediagen.registry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, manifest = Config.NONE)
class MediaChannelRegistryTest {
    private fun prefs() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("media-orders-test", 0).also { it.edit().clear().commit() }
    @Test fun mergesReordersAndRemovesInvalidChannels() = runTest {
        val store = MediaChannelOrderStore(prefs())
        val model = ModelInfo("H3", "p::MiniMax-H3", "", true, providerId = "p", remoteModelId = "MiniMax-H3")
        val models = MutableStateFlow(listOf(model))
        val providers = MutableStateFlow(listOf(ProviderListItem(id = "p")))
        val comfyChannel = MediaChannel("comfy:test", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.TEXT)))
        val comfy = MutableStateFlow(listOf(comfyChannel))
        val registry = DefaultMediaChannelRegistry(models, providers, ComfyChannelSource { comfy }, store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(listOf("cloud:p::MiniMax-H3", "comfy:test"), registry.observeOrder(MediaGroup.VIDEO).first())
        registry.setOrder(MediaGroup.VIDEO, listOf("comfy:test", "cloud:p::MiniMax-H3"))
        runCurrent()
        assertEquals("comfy:test", registry.observeChannels().first().first().id)
        models.value = listOf(model.copy(enabled = false)); runCurrent()
        assertEquals(listOf("comfy:test"), registry.observeOrder(MediaGroup.VIDEO).first())
        models.value = listOf(model); runCurrent()
        assertEquals(listOf("comfy:test", "cloud:p::MiniMax-H3"), registry.observeOrder(MediaGroup.VIDEO).first())
        assertEquals(model.toMediaChannel(), registry.channel("cloud:p::MiniMax-H3"))
        providers.value = listOf(ProviderListItem(id = "p", enabled = false)); runCurrent()
        assertNull(registry.channel("cloud:p::MiniMax-H3"))
    }
    @Test fun persistsAllGroupsAndMigratesLegacyImageDefault() = runTest {
        val p = prefs(); p.edit().putString("preset_image_model", "p::image-b").commit()
        val store = MediaChannelOrderStore(p)
        val caps = MediaCapabilities(setOf(GenerationMode.TEXT))
        val channels = listOf(MediaChannel("cloud:p::image-a", MediaGroup.IMAGE, caps), MediaChannel("cloud:p::image-b", MediaGroup.IMAGE, caps), MediaChannel("v", MediaGroup.VIDEO, caps), MediaChannel("a", MediaGroup.AUDIO, caps))
        store.reconcile(channels)
        assertEquals(listOf("cloud:p::image-b", "cloud:p::image-a"), store.orders.value[MediaGroup.IMAGE])
        store.reorder(MediaGroup.IMAGE, listOf("cloud:p::image-a", "cloud:p::image-b"))
        val restored = MediaChannelOrderStore(p)
        assertEquals(store.orders.value, restored.orders.value)
        assertEquals(listOf("v"), restored.orders.value[MediaGroup.VIDEO])
        assertEquals(listOf("a"), restored.orders.value[MediaGroup.AUDIO])
        assertThrows(IllegalArgumentException::class.java) { restored.reorder(MediaGroup.VIDEO, listOf("a")) }
    }
}
