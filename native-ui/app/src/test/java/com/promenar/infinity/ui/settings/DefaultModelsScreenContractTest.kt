package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.ui.common.ModelSelectionUiModel
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class DefaultModelsScreenContractTest {
    private val screenSource = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/infinity/ui/settings/DefaultModelsScreen.kt"),
    ).toString(Charsets.UTF_8)

    @Test
    fun `default model role has exactly three system roles`() {
        val enumBlock = screenSource.substringAfter("enum class DefaultModelRole(")
            .substringBefore("\n}")
        val roles = Regex("(?m)^\\s{4}([A-Z]+)\\(")
            .findAll(enumBlock)
            .map { it.groupValues[1] }
            .toList()

        assertThat(roles).containsExactly("SUMMARY", "EMBEDDING", "RERANK").inOrder()
    }

    @Test
    fun `selecting model immediately persists on same path and does not save on return`() {
        val selection = screenSource.substringAfter("onSelect = { id, _ ->")
            .substringBefore("\n            }")
        assertThat(selection).contains("viewModel.setPresetModel(role.type, id)")
        assertThat(selection).contains("activePickerRole = null")
        assertThat(screenSource).doesNotContain("draftModel")
        assertThat(screenSource).doesNotContain("savePreset")
        assertThat(screenSource).doesNotContain("onNavigateBack = {")
    }

    @Test
    fun `each configured default model exposes explicit persistent clear`() {
        assertThat(screenSource).contains("onClearModel = { role ->")
        assertThat(screenSource).contains("viewModel.setPresetModel(role.type, \"\")")
        assertThat(screenSource).contains("default_model_clear:")
        assertThat(screenSource).contains("R.string.common_cd_clear")
    }

    @Test
    fun `default models screen follows route content state action seam`() {
        assertThat(screenSource).contains("data class DefaultModelsScreenState(")
        assertThat(screenSource).contains("data class DefaultModelsScreenActions(")
        assertThat(screenSource).contains("fun DefaultModelsScreenContent(")
        val content = screenSource.substringAfter("fun DefaultModelsScreenContent(")
        assertThat(content).doesNotContain("viewModel(")
        assertThat(content).doesNotContain("SettingsViewModel")
    }

    @Test
    fun `default model row reuses unified resolved display name`() {
        val model = ModelSelectionUiModel(
            selectionId = "provider::official-model",
            remoteModelId = "official-model",
            displayName = "Official Friendly Name",
            providerName = "Provider",
            contextTokens = null,
            workload = ModelWorkload.GENERATIVE_TEXT,
            capabilityStates = emptyMap(),
            chatEndpointCompatible = SupportState.SUPPORTED,
        )

        assertThat(resolveDefaultModelName(listOf(model), model.selectionId))
            .isEqualTo("Official Friendly Name")
        assertThat(resolveDefaultModelName(listOf(model), "provider::unknown"))
            .isEqualTo("provider::unknown")
    }
}
