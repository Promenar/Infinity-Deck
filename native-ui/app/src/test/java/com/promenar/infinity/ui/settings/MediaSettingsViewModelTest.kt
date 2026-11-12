package com.promenar.infinity.ui.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import com.promenar.infinity.data.mediagen.comfy.*
import com.promenar.infinity.data.model.MediaModelSettings
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun persistsGroupOrderAndReferenceEditsThroughTheStore() = runTest(dispatcher) {
        val store = FakeMediaSettingsStore()
        val vm = MediaSettingsViewModel({ store }, dispatcher)
        runCurrent()
        assertTrue(vm.state.value.loaded)
        vm.moveChannelUp(MediaGroup.VIDEO, "cloud:b")
        advanceUntilIdle()
        assertEquals(listOf("cloud:b", "cloud:a"), store.lastOrder)
        assertTrue(vm.state.value.groups.getValue(MediaGroup.VIDEO).first().isDefault)
        assertEquals("cloud:b", vm.state.value.groups.getValue(MediaGroup.VIDEO).first().channel.id)
        vm.setReferenceMode("a", CharacterReferenceMode.COMPOSITE)
        advanceUntilIdle()
        assertEquals(CharacterReferenceMode.COMPOSITE, store.lastMode)
        vm.moveReferenceUp("a", "side")
        advanceUntilIdle()
        assertEquals(listOf("side", "front"), store.lastReferenceOrder)
        vm.viewModelScopeCancelForTest()
    }

    @Test fun openingAndDismissingConfirmationNeverUnlocksTheTemplate() = runTest(dispatcher) {
        val store = FakeMediaSettingsStore()
        val vm = MediaSettingsViewModel({ store }, dispatcher)
        runCurrent()
        vm.confirmUnlock()
        vm.requestUnlock("tpl")
        assertEquals("tpl", vm.state.value.unlock.pendingId)
        assertEquals(0, store.unlockCalls)
        vm.cancelUnlock()
        vm.confirmUnlock()
        advanceUntilIdle()
        assertEquals(0, store.unlockCalls)
        vm.requestUnlock("tpl")
        vm.confirmUnlock()
        advanceUntilIdle()
        assertEquals(1, store.unlockCalls)
        assertNull(vm.state.value.unlock.pendingId)
        assertFalse(vm.state.value.snapshot.templates.single().template.locked)
        vm.lockTemplate("tpl", listOf("side", "front"))
        advanceUntilIdle()
        assertTrue(vm.state.value.snapshot.templates.single().template.locked)
        assertEquals(listOf("side", "front"), store.lastReferenceOrder)
        vm.viewModelScopeCancelForTest()
    }

    @Test fun failedSavesKeepTheEditorOpenAndInvalidBillingNeverWrites() = runTest(dispatcher) {
        val store = FakeMediaSettingsStore()
        val vm = MediaSettingsViewModel({ store }, dispatcher)
        runCurrent()
        var closed = false
        vm.saveTariff("a", MediaTariffDraft(rates = listOf(MediaRateDraft(amount = "-2")))) { closed = true }
        advanceUntilIdle()
        assertEquals(0, store.tariffWrites)
        assertFalse(closed)
        store.fail = true
        vm.saveTariff("a", MediaTariffDraft()) { closed = true }
        advanceUntilIdle()
        assertFalse(closed)
        assertTrue(vm.state.value.failed)
        assertFalse(vm.state.value.busy)
        store.fail = false
        vm.saveTariff("a", MediaTariffDraft()) { closed = true }
        advanceUntilIdle()
        assertTrue(closed)
        assertFalse(vm.state.value.failed)
        vm.viewModelScopeCancelForTest()
    }

    @Test fun checkingAHostReportsConnectivityIndependentlyOfTemplateReadiness() = runTest(dispatcher) {
        val store = FakeMediaSettingsStore()
        val vm = MediaSettingsViewModel({ store }, dispatcher)
        runCurrent()
        vm.checkConnections()
        assertEquals(ComfyConnection.CHECKING, vm.state.value.connections["h"])
        advanceUntilIdle()
        assertEquals(ComfyConnection.ONLINE, vm.state.value.connections["h"])
        assertEquals(PreflightStatus.REVIEW, vm.state.value.snapshot.templates.single().preflight.status)
        store.fail = true
        vm.checkConnections()
        advanceUntilIdle()
        assertEquals(ComfyConnection.OFFLINE, vm.state.value.connections["h"])
        vm.viewModelScopeCancelForTest()
    }
}

private fun MediaSettingsViewModel.viewModelScopeCancelForTest() {
    viewModelScope.cancel()
}

private class FakeMediaSettingsStore : MediaSettingsStore {
    private val caps = MediaCapabilities(setOf(GenerationMode.REFERENCE), maxImages = 2, referenceOrder = listOf("front", "side"))
    private val models = listOf("a", "b").map { ModelInfo(it, it, "", true, "video", providerId = "p",
        mediaSettings = MediaModelSettings(MediaGroup.VIDEO, caps)) }
    val snapshots = MutableStateFlow(MediaSettingsSnapshot(
        channels = models.map { MediaChannel("cloud:${it.id}", MediaGroup.VIDEO, caps) }, models = models,
        hosts = listOf(ComfyHost("h", "MAIN", "http://main:8188")),
        templates = listOf(ComfyTemplateState(ComfyTemplate("tpl", JsonObject(emptyMap()),
            listOf(ComfySlot("1", "image_0", SlotKind.REFERENCE_IMAGES, capacity = 2)),
            locked = true, capabilities = caps, hostId = "h"))),
    ))
    var lastOrder = emptyList<String>()
    var lastMode: CharacterReferenceMode? = null
    var lastReferenceOrder = emptyList<String>()
    var unlockCalls = 0
    var tariffWrites = 0
    var fail = false
    override fun observe() = snapshots
    override suspend fun reorder(group: MediaGroup, ids: List<String>) {
        lastOrder = ids
        val byId = snapshots.value.channels.associateBy { it.id }
        snapshots.value = snapshots.value.copy(channels = ids.map { byId.getValue(it) })
    }
    override suspend fun changeReferences(modelId: String, mode: CharacterReferenceMode, order: List<String>) {
        lastMode = mode; lastReferenceOrder = order
        snapshots.value = snapshots.value.copy(models = snapshots.value.models.map { if (it.id == modelId)
            it.copy(mediaSettings = it.mediaSettings!!.withCharacterReferences(mode, order)) else it })
    }
    override suspend fun saveTariff(modelId: String, draft: MediaTariffDraft) { if (fail) error("fixture"); tariffWrites++ }
    override suspend fun saveHost(host: ComfyHost, credential: ComfyCredentialInput) = Unit
    override suspend fun removeHost(id: String) = Unit
    override suspend fun probe(id: String) { if (fail) error("fixture") }
    override suspend fun preflight(id: String) = Unit
    override suspend fun unlock(id: String) {
        unlockCalls++
        snapshots.value = snapshots.value.copy(templates = snapshots.value.templates.map { it.copy(template = it.template.unlock(true)) })
    }
    override suspend fun lock(id: String, referenceOrder: List<String>) {
        lastReferenceOrder = referenceOrder
        snapshots.value = snapshots.value.copy(templates = snapshots.value.templates.map { it.copy(template = it.template.copy(locked = true)) })
    }
}
