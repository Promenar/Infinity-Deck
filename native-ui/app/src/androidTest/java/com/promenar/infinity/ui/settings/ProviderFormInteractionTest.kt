package com.promenar.infinity.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.ui.theme.InfinityTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Rule
import org.junit.Test

class ProviderFormInteractionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun cloudFormRoutesEditsTestAndSaveThroughExplicitActions() {
        val changedNames = CopyOnWriteArrayList<String>()
        val changedUrls = CopyOnWriteArrayList<String>()
        val tests = AtomicInteger()
        val saves = AtomicInteger()
        rule.setContent {
            InfinityTheme {
                ProviderFormContent(
                    state = validCloudState(),
                    actions = ProviderFormActions(
                        onNameChange = changedNames::add,
                        onBaseUrlChange = changedUrls::add,
                        onTestConnection = { tests.incrementAndGet() },
                        onSave = { saves.incrementAndGet() },
                    ),
                )
            }
        }

        rule.onNodeWithText(resources.getString(R.string.provider_form_label_name))
            .performTextReplacement("Renamed")
        rule.onNodeWithText(resources.getString(R.string.provider_form_label_url))
            .performTextReplacement("https://gateway.example/v1")
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_test))
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_save))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertThat(changedNames).contains("Renamed")
        assertThat(changedUrls).contains("https://gateway.example/v1")
        assertThat(tests.get()).isEqualTo(1)
        assertThat(saves.get()).isEqualTo(1)
    }

    @Test
    fun connectionAndSaveActionsStayMutuallyExclusive() {
        val state = mutableStateOf(
            validCloudState(connectionTestState = ProviderConnectionTestState.Testing),
        )
        rule.setContent {
            InfinityTheme {
                ProviderFormContent(
                    state = state.value,
                    actions = ProviderFormActions(),
                )
            }
        }

        val testing = resources.getString(R.string.provider_form_testing)
        // “测试中”同时出现在按钮与按钮上方的状态条里，按可点击节点取按钮。
        rule.onNode(hasText(testing) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, testing))
            .assertIsNotEnabled()
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_save))
            .assertIsNotEnabled()

        rule.runOnIdle { state.value = validCloudState(isSaving = true) }
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_test))
            .assertIsNotEnabled()
    }

    @Test
    fun invalidHttpsAndCredentialMismatchExposeErrorsAndDisableBothActions() {
        rule.setContent {
            InfinityTheme {
                ProviderFormContent(
                    state = validCloudState(
                        baseUrl = "http://plain.example",
                        endpointValid = false,
                        credentialKindMismatch = true,
                    ),
                    actions = ProviderFormActions(),
                )
            }
        }

        rule.onNodeWithText(resources.getString(R.string.provider_form_https_required))
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.provider_form_credential_kind_changed))
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_test))
            .assertIsNotEnabled()
            .performClick()
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_save))
            .assertIsNotEnabled()
            .performClick()
    }

    @Test
    fun historicalUnsupportedProviderDisablesCallbacksUntilExplicitMigration() {
        val tests = AtomicInteger()
        val saves = AtomicInteger()
        rule.setContent {
            InfinityTheme {
                ProviderFormContent(
                    state = validCloudState(
                        selectedPreset = ProviderPreset(
                            name = ProtocolType.Cohere_Chat.persistedId,
                            labelRes = ProtocolType.Cohere_Chat.labelRes,
                            protocolType = ProtocolType.Cohere_Chat,
                            defaultBaseUrl = ProtocolType.Cohere_Chat.defaultBaseUrl,
                        ),
                        baseUrl = ProtocolType.Cohere_Chat.defaultBaseUrl,
                        endpointValid = false,
                        protocolUnsupported = true,
                        legacyMigrationBlocked = true,
                    ),
                    actions = ProviderFormActions(
                        onTestConnection = { tests.incrementAndGet() },
                        onSave = { saves.incrementAndGet() },
                    ),
                )
            }
        }

        rule.onNodeWithText(resources.getString(R.string.provider_form_protocol_unsupported))
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.provider_form_legacy_migration_required))
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_test))
            .assertIsNotEnabled()
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_save))
            .assertIsNotEnabled()

        assertThat(tests.get()).isEqualTo(0)
        assertThat(saves.get()).isEqualTo(0)
    }

    @Test
    fun failedConnectionStatusRemainsVisibleBesideReachableActions() {
        rule.setContent {
            InfinityTheme {
                ProviderFormContent(
                    state = validCloudState(
                        connectionTestState = ProviderConnectionTestState.Error,
                    ),
                    actions = ProviderFormActions(),
                )
            }
        }

        val failed = resources.getString(R.string.common_cd_failed)
        rule.onNodeWithTag(UiTags.PROVIDER_FORM_STATUS_STRIP)
            .assertIsDisplayed()
        rule.onNodeWithText(failed).assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_test))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, failed))
            .assertIsEnabled()
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_save))
            .assertIsEnabled()
    }

    @Test
    fun unsupportedNoCostProbeIsExplainedWithoutBlockingManualSave() {
        rule.setContent {
            InfinityTheme {
                ProviderFormContent(
                    state = validCloudState(
                        connectionTestState = ProviderConnectionTestState.Unavailable,
                    ),
                    actions = ProviderFormActions(),
                )
            }
        }

        val unavailable = resources.getString(R.string.provider_form_connection_unavailable)
        rule.onNodeWithTag(UiTags.PROVIDER_FORM_STATUS_STRIP)
            .assertIsDisplayed()
        rule.onNodeWithText(unavailable).assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_test))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, unavailable))
            .assertIsEnabled()
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_save))
            .assertIsEnabled()
    }

    @Test
    fun presetAndCustomProtocolSelectorsUseExplicitSelectionCallbacks() {
        val selectedPreset = AtomicReference<ProviderPreset?>(null)
        val selectedProtocol = AtomicReference<ProtocolType?>(null)
        val state = mutableStateOf(
            validCloudState(
                presetMenuExpanded = true,
            ),
        )
        rule.setContent {
            InfinityTheme {
                ProviderFormContent(
                    state = state.value,
                    actions = ProviderFormActions(
                        onPresetSelected = {
                            selectedPreset.set(it)
                            state.value = state.value.copy(selectedPreset = it, presetMenuExpanded = false)
                        },
                        onProtocolSelected = {
                            selectedProtocol.set(it)
                            state.value = state.value.copy(localProtocol = it, protocolMenuExpanded = false)
                        },
                        onProtocolMenuExpandedChange = {
                            state.value = state.value.copy(protocolMenuExpanded = it)
                        },
                    ),
                )
            }
        }

        rule.onNodeWithTag("provider_preset:DeepSeek").performClick()
        assertThat(selectedPreset.get()?.name).isEqualTo("DeepSeek")

        rule.runOnIdle {
            state.value = state.value.copy(
                selectedPreset = PROVIDER_PRESETS.first { it.name == "Custom" },
                protocolMenuExpanded = false,
            )
        }
        rule.onNodeWithTag("provider_protocol_picker").performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(ProtocolType.OpenAI_Responses.labelRes)).performClick()
        assertThat(selectedProtocol.get()).isEqualTo(ProtocolType.OpenAI_Responses)
    }

    @Test
    fun allCloudFieldsAndActionsRemainReachableAt360By800WithTwoXFontScale() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                InfinityTheme {
                    Box(Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                        ProviderFormContent(
                            state = validCloudState(hasStoredCredential = true),
                            actions = ProviderFormActions(),
                        )
                    }
                }
            }
        }

        listOf(
            R.string.provider_form_preset_title,
        ).forEach { id ->
            rule.onNodeWithText(resources.getString(id)).performScrollTo().assertIsDisplayed()
        }
        listOf(
            R.string.provider_form_label_name,
            R.string.provider_form_label_url,
            R.string.provider_form_label_api_key,
        ).forEach { id ->
            rule.onNodeWithText(resources.getString(id))
                .performScrollTo()
                .assertIsDisplayed()
        }
        rule.onNodeWithText(resources.getString(R.string.field_row_secret_saved)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_test))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_save))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun cloudActionsRemainReachableInLandscapeWithTwoXFontScale() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                InfinityTheme {
                    Box(Modifier.requiredSize(width = 800.dp, height = 360.dp)) {
                        ProviderFormContent(
                            state = validCloudState(hasStoredCredential = true),
                            actions = ProviderFormActions(),
                        )
                    }
                }
            }
        }

        listOf(
            R.string.provider_form_label_name,
            R.string.provider_form_label_api_key,
        ).forEach { id ->
            // 表单是懒加载列表：先在列表内滚动到目标，再断言可见。
            rule.onNodeWithTag(PROVIDER_FORM_LIST_TAG).performScrollToNode(hasText(resources.getString(id)))
            rule.onNodeWithText(resources.getString(id)).assertIsDisplayed()
        }
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_test))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText(resources.getString(R.string.provider_form_btn_save))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    private fun validCloudState(
        baseUrl: String = "https://api.example.com/v1",
        endpointValid: Boolean = true,
        credentialKindMismatch: Boolean = false,
        connectionTestState: ProviderConnectionTestState = ProviderConnectionTestState.Idle,
        isSaving: Boolean = false,
        selectedPreset: ProviderPreset = PROVIDER_PRESETS.first(),
        presetMenuExpanded: Boolean = false,
        protocolMenuExpanded: Boolean = false,
        localProtocol: ProtocolType = ProtocolType.Generic_OpenAI_Compat,
        hasStoredCredential: Boolean = false,
        protocolUnsupported: Boolean = false,
        legacyMigrationBlocked: Boolean = false,
    ) = ProviderFormUiState(
        name = "Example Provider",
        selectedPreset = selectedPreset,
        presetMenuExpanded = presetMenuExpanded,
        protocolMenuExpanded = protocolMenuExpanded,
        localProtocol = localProtocol,
        baseUrl = baseUrl,
        endpointValid = endpointValid,
        credentialKindMismatch = credentialKindMismatch,
        connectionTestState = connectionTestState,
        isSaving = isSaving,
        hasStoredCredential = hasStoredCredential,
        protocolUnsupported = protocolUnsupported,
        legacyMigrationBlocked = legacyMigrationBlocked,
    )
}
