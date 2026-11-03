package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import com.promenar.infinity.data.local.inference.SlotState
import com.promenar.infinity.data.remote.protocol.ProtocolType

class SecureSecretUiContractTest {
    private fun source(relative: String): String = String(
        Files.readAllBytes(Path.of("app/src/main/java/com/promenar/infinity/$relative")),
        Charsets.UTF_8,
    )

    @Test
    fun `provider presets advertise only implemented protocols`() {
        assertThat(PROVIDER_PRESETS.map { it.protocolType })
            .doesNotContain(ProtocolType.Cohere_Chat)
        assertThat(PROVIDER_PRESETS.map { it.protocolType })
            .doesNotContain(ProtocolType.Yi_ZeroOne)
        assertThat(PROVIDER_PRESETS.map { it.protocolType })
            .containsAtLeast(
                ProtocolType.OpenAI_ChatCompletions,
                ProtocolType.DeepSeek,
                ProtocolType.Anthropic_Messages,
                ProtocolType.Google_VertexAI,
                ProtocolType.Mistral_Chat,
            )
    }

    @Test
    fun `provider form delegates cloud connection checks to the unique no generation probe`() {
        val form = source("ui/settings/ProviderFormScreen.kt")
        val route = form.substringBefore("fun ProviderFormContent(")

        assertThat(route).contains("ProviderConnectionProbe.processScoped")
        assertThat(route).doesNotContain("remember { ProviderConnectionProbe() }")
        assertThat(route).doesNotContain("ProtocolFactory.create(")
        assertThat(route).doesNotContain("listModels().isNotEmpty()")
        assertThat(route).doesNotContain("Reply OK.")
        assertThat(ProviderConnectionTestState.entries)
            .contains(ProviderConnectionTestState.Unavailable)
    }

    @Test
    fun `application routes Vertex credential parsing through the shared parser`() {
        val application = source("InfinityApplication.kt")
        val router = source("data/remote/ProviderRequestRouter.kt")

        assertThat(application).contains("VertexCredentialParser")
        assertThat(router).contains("VertexCredentialParser")
        assertThat(application).doesNotContain("extractVertexProjectId")
        assertThat(router).doesNotContain("parseVertexProjectId")
    }

    @Test
    fun `provider form uses typed credential mutation and never loads full config into compose state`() {
        val form = source("ui/settings/ProviderFormScreen.kt")
        val nav = source("navigation/NavGraph.kt")

        assertThat(form).contains("CredentialUpdate.Preserve")
        assertThat(form).contains("CredentialUpdate.Replace")
        assertThat(form).contains("CredentialUpdate.Clear")
        assertThat(form).contains("SecretField(")
        assertThat(form).doesNotContain("apiKey = config.apiKey")
        assertThat(form).contains("getProviderSummary(providerId)")
        val save = source("navigation/ProviderConfigurationSave.kt")
        assertThat(nav).doesNotContain("apiKey.toCredentialUpdate()")
        assertThat(nav).contains("saveProviderConfiguration(")
        assertThat(save).doesNotContain("apiKey.toCredentialUpdate()")
        assertThat(save).contains("withContext(Dispatchers.IO)")
        assertThat(form).contains("credentialKindMismatch")
        assertThat(form).contains("isSaving")
        assertThat(form).contains("catch (_: Exception)")
        assertThat(source("ui/settings/SettingsViewModel.kt")).contains("suspend fun getProviderSummary")
    }

    @Test
    fun `provider form exposes a pure Material 3 content seam without glass or fixed IME spacers`() {
        val form = source("ui/settings/ProviderFormScreen.kt")

        assertThat(form).contains("data class ProviderFormUiState")
        assertThat(form).contains("data class ProviderFormActions")
        assertThat(form).contains("fun ProviderFormContent(")
        assertThat(form).contains("InfinitySettingsPageLayout(")
        assertThat(form).contains("contentPadding = contentPadding")
        assertThat(form).contains("LazyColumn(")
        // 预设与协议都走统一底部面板；输入为无框字段行（InfinityFieldRow 内部仍是 Material 3 文本框）。
        assertThat(form).contains("InfinityBottomSheet(")
        assertThat(form).contains("InfinityFieldRow(")
        assertThat(form).contains("FilledTonalButton(")
        assertThat(form).contains("Button(")
        assertThat(form).contains("liveRegion = LiveRegionMode.Assertive")
        assertThat(form).doesNotContain("InfinityGlassCard")
        assertThat(form).doesNotContain("GlassInputField")
        assertThat(form).doesNotContain("GlassBorder")
        assertThat(form).doesNotContain("PresetItem")
        assertThat(form).doesNotContain("import com.promenar.infinity.ui.common.ProtocolSelector")
        assertThat(form).doesNotContain("BasicTextField")
        assertThat(form).doesNotContain("Spacer(modifier = Modifier.height(200.dp))")
        assertThat(form).doesNotContain("navigationBarsPadding()")
        assertThat(form).contains("imePadding()")
    }

    @Test
    fun `provider connection and save are mutually exclusive and cancellation is never swallowed`() {
        val form = source("ui/settings/ProviderFormScreen.kt")

        assertThat(form).contains("connectionTestState != ProviderConnectionTestState.Testing")
        assertThat(form).contains("!state.isSaving")
        assertThat(form).contains("catch (cancelled: CancellationException)")
        assertThat(form).contains("throw cancelled")
        assertThat(form.substringBefore("fun ProviderFormContent(")).doesNotContain("runCatching {")
    }

    @Test
    fun `provider route owns effects while pure content owns only render state and callbacks`() {
        val form = source("ui/settings/ProviderFormScreen.kt")
        val content = form.substringAfter("fun ProviderFormContent(")

        assertThat(form.substringBefore("fun ProviderFormContent(")).contains("InfinityApplication")
        assertThat(form.substringBefore("fun ProviderFormContent(")).contains("SettingsViewModel")
        assertThat(content).doesNotContain("InfinityApplication")
        assertThat(content).doesNotContain("SettingsViewModel")
        assertThat(content).doesNotContain("LocalContext")
        assertThat(content).doesNotContain("rememberCoroutineScope")
        assertThat(content).doesNotContain("LaunchedEffect")
        assertThat(content).doesNotContain("ProtocolFactory")
    }

    @Test
    fun `提供商端点支持HTTP与HTTPS`() {
        assertThat(isHttpProviderEndpoint("https://api.example.com/v1")).isTrue()
        assertThat(isHttpProviderEndpoint("HTTP://api.example.com/v1")).isTrue()
        assertThat(isHttpProviderEndpoint("https:///missing-host")).isFalse()
        assertThat(isHttpProviderEndpoint("not-a-url")).isFalse()
    }

    @Test
    fun `credential mutation preserves stored values but keeps explicit clear unambiguous`() {
        assertThat(credentialUpdateForSecretInput("", hasStoredCredential = true))
            .isEqualTo(com.promenar.infinity.data.model.CredentialUpdate.Preserve)
        assertThat(credentialUpdateForSecretInput("", hasStoredCredential = false))
            .isEqualTo(com.promenar.infinity.data.model.CredentialUpdate.Clear)
        assertThat(credentialUpdateForSecretInput("fake-new", hasStoredCredential = true))
            .isEqualTo(com.promenar.infinity.data.model.CredentialUpdate.Replace("fake-new"))
    }

    @Test
    fun `search and skills share SecretField and persistent state exposes only secret presence`() {
        val screen = source("ui/settings/TavilySecretEditor.kt")
        val skills = source("ui/settings/SkillsScreen.kt")
        val stateFields = SearchConfigState::class.java.declaredFields.map { it.name }

        assertThat(stateFields).doesNotContain("tavilyApiKey")
        assertThat(stateFields).contains("hasTavilyApiKey")
        assertThat(screen).contains("SecretField(")
        assertThat(skills).contains("TavilySecretEditor(searchViewModel, searchState)")
        assertThat(skills).doesNotContain("Paste API Key here")
    }

    @Test
    fun `secret reveal is transient clearable and never saveable`() {
        val field = source("ui/common/SecretField.kt")

        assertThat(field).contains("CharArray?")
        assertThat(field).contains("DisposableEffect")
        assertThat(field).contains("fill('\\u0000')")
        assertThat(field).doesNotContain("rememberSaveable(")
        assertThat(field).contains("Modifier.size(48.dp)")
        assertThat(field).contains("revealGeneration")
        assertThat(field).contains("revealJob?.cancel()")
        assertThat(field).contains("DisposableEffect(hasStoredSecret, lifecycleOwner)")
        assertThat(field).doesNotContain("remember(hasStoredSecret)")
        assertThat(field).doesNotContain("everFocused")
        assertThat(field).contains("focusGeneration")
        assertThat(field).contains("OutlinedTextField(")
        assertThat(field).contains("SECRET_REVEAL_TIMEOUT_MILLIS")
        assertThat(field).contains("revealTimeoutJob")
        assertThat(field).contains("delay(revealTimeoutMillis)")
        assertThat(field).contains("Lifecycle.Event.ON_STOP")
        // 任何密码变体都会被输入法与 ColorOS 强制切到禁用剪贴板的安全键盘，密钥无法粘贴。
        assertThat(field).contains("keyboardOptions = SecretKeyboardOptions")
        assertThat(field).doesNotContain("InputType.TYPE_TEXT_VARIATION_")
        assertThat(field).contains("IME_FLAG_NO_PERSONALIZED_LEARNING")
        assertThat(field).contains("autoCorrectEnabled = false")
        assertThat(field).contains("readOnly = hasStoredSecret && value.isEmpty()")
        assertThat(field).doesNotContain(".focusable()")
        assertThat(field).doesNotContain("BasicTextField")
        assertThat(field).doesNotContain("GlassBorder")
        assertThat(field).doesNotContain("SolidColor")
    }

    @Test
    fun `local effective protocol bypasses cloud HTTPS while custom cloud stays protected`() {
        assertThat(isProviderEndpointAllowed(ProtocolType.Local, "")).isTrue()
        assertThat(isProviderEndpointAllowed(ProtocolType.Generic_OpenAI_Compat, "http://plain.invalid")).isFalse()
        assertThat(isProviderEndpointAllowed(ProtocolType.Generic_OpenAI_Compat, "https://secure.invalid")).isFalse()
        assertThat(
            isProviderEndpointAllowed(
                ProtocolType.Generic_OpenAI_Compat,
                "https://secure.invalid/chat/completions",
            ),
        ).isTrue()
        assertThat(
            isProviderEndpointAllowed(
                ProtocolType.Generic_OpenAI_Compat,
                "https://secure.invalid/v2",
            ),
        ).isTrue()
        assertThat(isProviderEndpointAllowed(ProtocolType.Cohere_Chat, ProtocolType.Cohere_Chat.defaultBaseUrl))
            .isFalse()
        assertThat(isProviderEndpointAllowed(ProtocolType.Yi_ZeroOne, ProtocolType.Yi_ZeroOne.defaultBaseUrl))
            .isFalse()
    }

    @Test
    fun `historical unsupported provider stays identifiable until explicit migration`() {
        assertThat(isRetiredProviderProtocol(ProtocolType.Cohere_Chat)).isTrue()
        assertThat(isRetiredProviderProtocol(ProtocolType.Yi_ZeroOne)).isTrue()
        assertThat(isRetiredProviderProtocol(ProtocolType.Generic_OpenAI_Compat)).isFalse()

        val form = source("ui/settings/ProviderFormScreen.kt")
        val route = form.substringBefore("fun ProviderFormContent(")
        assertThat(route).contains("legacyUnsupportedProtocol")
        assertThat(route).contains("legacyMigrationBlocked")
        assertThat(route).doesNotContain("ProtocolType.Yi_ZeroOne\n                    } ?: ProtocolType.Generic_OpenAI_Compat")
    }

    @Test
    fun `local connection requires an actually loaded named main model`() {
        assertThat(localProviderModelsForConnection(SlotState())).isEmpty()
        assertThat(
            localProviderModelsForConnection(SlotState(isLoaded = true, modelName = ""))
        ).isEmpty()
        assertThat(
            localProviderModelsForConnection(SlotState(isLoaded = true, modelName = "local.gguf"))
        ).containsExactly("local.gguf")
        assertThat(source("ui/settings/LocalModelsViewModel.kt"))
            .contains("app.localInferenceEngine")
        assertThat(source("ui/settings/LocalModelsViewModel.kt"))
            .doesNotContain("LocalInferenceEngine(appContext)")
    }

    @Test
    fun `backup screen provides password gates and real remote selection flow`() {
        val screen = source("ui/settings/BackupSettingsScreen.kt")

        assertThat(screen).contains("onRefreshRemote = viewModel::listRemote")
        assertThat(screen).contains("onClick = actions.onRefreshRemote")
        assertThat(screen).contains("onSelectRemote = viewModel::selectRemote")
        assertThat(screen).contains("actions.onSelectRemote(remote)")
        assertThat(screen).contains("viewModel.restoreSelectedRemote(")
        assertThat(screen).contains("viewModel.upload(")
        assertThat(screen).contains("passwordConfirmation")
        assertThat(screen).contains("showExportPasswordDialog")
        assertThat(screen).contains("showUploadPasswordDialog")
        assertThat(screen).contains("showRestorePasswordDialog")
        assertThat(screen).contains("uiState.canExecute")
        assertThat(screen).contains("uiState.operation.isCancellable")
        assertThat(screen).contains("imePadding()")
        assertThat(screen).contains("viewModel.reportDocumentError(")
        assertThat(source("ui/settings/BackupViewModel.kt")).doesNotContain("val statusMessage: String?")
    }

    @Test
    fun `search editors persist only on explicit save`() {
        val screen = source("ui/settings/TavilySecretEditor.kt")
        val skills = source("ui/settings/SkillsScreen.kt")

        assertThat(screen).contains("saveTavilyApiKey(")
        assertThat(skills).contains("TavilySecretEditor(searchViewModel, searchState)")
        assertThat(screen).doesNotContain("if (it.isNotBlank()) viewModel.updateTavilyApiKey(it)")
        assertThat(skills).doesNotContain("if (it.isNotBlank()) searchViewModel.updateTavilyApiKey(it)")
        assertThat(source("ui/settings/SearchConfigViewModel.kt")).contains("SearchSecretOperation.Saving")
    }
}
