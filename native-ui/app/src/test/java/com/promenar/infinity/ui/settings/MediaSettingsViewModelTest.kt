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

    @Test fun lockingAnUnlockedTemplateWritesTheChosenReferenceOrder() = runTest(dispatcher) {
        val store = FakeMediaSettingsStore()
        store.snapshots.value = store.snapshots.value.copy(templates = store.snapshots.value.templates.map { it.copy(template = it.template.copy(locked = false)) })
        val vm = MediaSettingsViewModel({ store }, dispatcher)
        runCurrent()
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

    @Test fun managesSubmittersAndWorkflowsWithDryRunPreflightAndFailureRecovery() = runTest(dispatcher) {
        val store = FakeMediaSettingsStore()
        val vm = MediaSettingsViewModel({ store }, dispatcher)
        runCurrent()

        var savedSubmitter = false
        vm.saveSubmitter(
            hostId = "h",
            scriptId = "sub_qwen",
            fileName = "submit_qwen.py",
            displayName = "Qwen Submit",
            description = "Qwen 2.1",
            sourceKind = SubmitterSourceKind.DESKTOP_IMPORT,
            protocol = SubmitterInvocationProtocol.CLI_ARGS,
            sourceText = "print('ok')",
            supportFiles = emptyMap(),
            runDryRun = true,
        ) { savedSubmitter = true }
        advanceUntilIdle()
        assertTrue(savedSubmitter)
        assertEquals("sub_qwen", vm.state.value.snapshot.submitters.single().scriptId)
        assertEquals(SubmitterDryRunStatus.PASSED, vm.state.value.snapshot.submitters.single().lastDryRunStatus)

        vm.dryRunSubmitter("h", "sub_qwen")
        advanceUntilIdle()
        assertEquals(listOf("h:sub_qwen"), store.dryRuns)

        val wf = ComfyWorkflow(
            workflowId = "wf_qwen",
            hostId = "h",
            name = "Qwen 2.1",
            mediaCategory = ComfyMediaCategory.IMAGE,
            submitterScriptId = "sub_qwen",
            fields = listOf(WorkflowFieldSpec(key = "prompt", label = "Prompt", mutability = FieldMutability.CONTENT, valueType = FieldValueType.TEXT)),
        )
        var savedWorkflow = false
        vm.saveWorkflow(wf) { savedWorkflow = true }
        advanceUntilIdle()
        assertTrue(savedWorkflow)
        assertEquals("wf_qwen", vm.state.value.snapshot.workflows.single().workflow.workflowId)

        vm.preflightWorkflow("wf_qwen")
        advanceUntilIdle()
        assertEquals(listOf("wf_qwen"), store.workflowPreflights)

        store.fail = true
        var deletedOnFailure = false
        vm.deleteWorkflow("wf_qwen") { deletedOnFailure = true }
        advanceUntilIdle()
        assertFalse(deletedOnFailure)
        assertTrue(vm.state.value.failed)
        assertFalse(vm.state.value.busy)

        store.fail = false
        var deletedWorkflow = false
        vm.deleteWorkflow("wf_qwen") { deletedWorkflow = true }
        advanceUntilIdle()
        assertTrue(deletedWorkflow)
        assertTrue(vm.state.value.snapshot.workflows.isEmpty())

        var deletedSubmitter = false
        vm.deleteSubmitter("h", "sub_qwen") { deletedSubmitter = true }
        advanceUntilIdle()
        assertTrue(deletedSubmitter)
        assertTrue(vm.state.value.snapshot.submitters.isEmpty())
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
    var tariffWrites = 0
    var dryRuns = mutableListOf<String>()
    var workflowPreflights = mutableListOf<String>()
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
    override suspend fun lock(id: String, referenceOrder: List<String>) {
        lastReferenceOrder = referenceOrder
        snapshots.value = snapshots.value.copy(templates = snapshots.value.templates.map { it.copy(template = it.template.copy(locked = true)) })
    }
    override suspend fun saveSubmitter(
        hostId: String,
        scriptId: String,
        fileName: String,
        displayName: String,
        description: String,
        sourceKind: SubmitterSourceKind,
        protocol: SubmitterInvocationProtocol,
        sourceText: String,
        supportFiles: Map<String, ByteArray>,
        runDryRun: Boolean,
    ) {
        if (fail) error("fixture")
        val saved = ComfySubmitterScript(
            scriptId = scriptId,
            hostId = hostId,
            fileName = fileName,
            displayName = displayName,
            description = description,
            sourceKind = sourceKind,
            protocol = protocol,
            sha256 = "0".repeat(64),
            supportFiles = supportFiles.keys.toList(),
            lastDryRunStatus = if (runDryRun) SubmitterDryRunStatus.PASSED else SubmitterDryRunStatus.UNTESTED,
        )
        snapshots.value = snapshots.value.copy(
            submitters = snapshots.value.submitters.filterNot { it.hostId == hostId && it.scriptId == scriptId } + saved,
        )
    }
    override suspend fun dryRunSubmitter(hostId: String, scriptId: String) {
        if (fail) error("fixture")
        dryRuns += "$hostId:$scriptId"
    }
    override suspend fun deleteSubmitter(hostId: String, scriptId: String) {
        if (fail) error("fixture")
        snapshots.value = snapshots.value.copy(
            submitters = snapshots.value.submitters.filterNot { it.hostId == hostId && it.scriptId == scriptId },
        )
    }
    override suspend fun saveWorkflow(workflow: ComfyWorkflow) {
        if (fail) error("fixture")
        val state = ComfyWorkflowState(workflow, PreflightResult(PreflightStatus.PASS))
        snapshots.value = snapshots.value.copy(
            workflows = snapshots.value.workflows.filterNot { it.workflow.workflowId == workflow.workflowId } + state,
        )
    }
    override suspend fun preflightWorkflow(workflowId: String) {
        if (fail) error("fixture")
        workflowPreflights += workflowId
    }
    override suspend fun deleteWorkflow(workflowId: String) {
        if (fail) error("fixture")
        snapshots.value = snapshots.value.copy(
            workflows = snapshots.value.workflows.filterNot { it.workflow.workflowId == workflowId },
        )
    }
}
