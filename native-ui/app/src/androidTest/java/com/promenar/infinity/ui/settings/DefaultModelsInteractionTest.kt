package com.promenar.infinity.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.MainActivity
import com.promenar.infinity.R
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.data.remote.stableModelId
import com.promenar.infinity.onboarding.OnboardingStateStore
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.settings.PROVIDER_FORM_LIST_TAG
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class DefaultModelsInteractionTest {

    private val fixtureProviderId = "task8-fixture-${UUID.randomUUID()}"

    private val fixtures = listOf(
        ModelFixture(fixtureProviderId, "summary", "task8-test-summary", "Task 8 Quick Model", "chat", listOf("chat")),
        ModelFixture(fixtureProviderId, "embedding", "task8-test-embedding", "Task 8 Embedding Model", "embedding", listOf("embedding")),
        ModelFixture(fixtureProviderId, "rerank", "task8-test-rerank", "Task 8 Rerank Model", "rerank", listOf("rerank")),
    )

    private lateinit var context: Context
    private lateinit var providerManager: ProviderManager
    private lateinit var settingsPreferences: SharedPreferences
    private lateinit var originalPresetIds: Map<String, String>

    private val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(CompletedOnboardingRule())
        .around(rule)

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        providerManager = ProviderManager.getInstance()
        settingsPreferences = context.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE)
        originalPresetIds = mapOf(
            "summary" to providerManager.summaryModelId.value,
            "image" to providerManager.imageModelId.value,
            "embedding" to providerManager.embeddingModelId.value,
            "rerank" to providerManager.rerankModelId.value,
        )

        fixtures.forEach { fixture ->
            providerManager.deleteModel(fixture.id)
            providerManager.addModel(fixture.toModelInfo())
            providerManager.setPresetModel(fixture.role, "")
        }

        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        originalPresetIds.forEach(providerManager::setPresetModel)
        fixtures.forEach { providerManager.deleteModel(it.id) }
    }

    @Test
    fun fourRolesPersistImmediatelyAndBackDoesNotWriteAgain() {
        openDefaultModels()
        rule.onNodeWithText(context.getString(R.string.settings_model_summary)).assertExists()
        rule.onNodeWithTag("default_model_quick_description").assertExists()
        rule.onNodeWithText(context.getString(R.string.settings_model_quick_description)).assertExists()

        fixtures.forEach { fixture ->
            rule.onNodeWithTag("default_model_role:${fixture.role}")
                .assertExists()
                .assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performClick()
            rule.onNodeWithTag("model_picker_sheet").assertExists()
            fixtures.filterNot { it.role == fixture.role }.forEach { incompatible ->
                rule.onNodeWithTag("model_picker_item:${incompatible.id}").assertDoesNotExist()
            }
            rule.onNodeWithTag("model_picker_item:${fixture.id}").performClick()
            rule.onNodeWithTag("model_picker_sheet").assertDoesNotExist()
            rule.onNodeWithText(fixture.name).assertExists()
            rule.waitUntil(timeoutMillis = 5_000) {
                presetValue(fixture.role) == fixture.id &&
                    settingsPreferences.getString("preset_${fixture.role}_model", "") == fixture.id
            }
        }

        val presetWritesAfterSelection = AtomicInteger(0)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in PRESET_KEYS) presetWritesAfterSelection.incrementAndGet()
        }
        settingsPreferences.registerOnSharedPreferenceChangeListener(listener)
        try {
            pressBack()
            rule.waitForIdle()
            assertEquals(0, presetWritesAfterSelection.get())
        } finally {
            settingsPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }

        openDefaultModels()
        fixtures.forEach { fixture ->
            rule.onNodeWithText(fixture.name).assertExists()
        }

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        if (!rule.onAllNodesWithTag("default_model_role:summary").fetchSemanticsNodes().any()) {
            openDefaultModels()
        }
        fixtures.forEach { fixture ->
            assertEquals(fixture.id, presetValue(fixture.role))
            assertEquals(fixture.id, settingsPreferences.getString("preset_${fixture.role}_model", ""))
            rule.onNodeWithText(fixture.name).assertExists()
        }
    }

    @Test
    fun providerDestinationAndAddFormUseStableSecondaryRoutes() {
        openSettingsHome()
        rule.onNodeWithTag(UiTags.SETTINGS_PROVIDER_ENTRY).performClick()
        rule.onNodeWithTag(UiTags.SETTINGS_PROVIDER_LIST).assertExists()
        rule.onNodeWithTag(UiTags.SETTINGS_ADD_PROVIDER).performClick()
        rule.onNodeWithTag(PROVIDER_FORM_LIST_TAG).assertExists()
        pressBack()
        rule.onNodeWithTag(UiTags.SETTINGS_PROVIDER_LIST).assertExists()
        pressBack()
        rule.onNodeWithTag(UiTags.SETTINGS_ROOT).assertExists()
    }

    private fun openDefaultModels() {
        rule.waitUntil(timeoutMillis = 20_000) {
            rule.onAllNodesWithTag("main_navigation_tab_settings").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithTag("default_model_role:summary").fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithTag("default_model_role:summary").fetchSemanticsNodes().isNotEmpty()) {
            return
        }
        openSettingsHome()
        rule.onNodeWithTag(UiTags.SETTINGS_DEFAULT_MODELS_ENTRY).performClick()
        rule.onNodeWithTag("default_model_role:summary").assertExists()
    }

    private fun openSettingsHome() {
        if (rule.onAllNodesWithTag(UiTags.SETTINGS_ROOT).fetchSemanticsNodes().isNotEmpty()) return
        rule.waitUntil(timeoutMillis = 20_000) {
            rule.onAllNodesWithTag("main_navigation_tab_settings").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("main_navigation_tab_settings").performClick()
        rule.onNodeWithTag(UiTags.SETTINGS_ROOT).assertExists()
    }

    private fun presetValue(role: String): String = when (role) {
        "summary" -> providerManager.summaryModelId.value
        "image" -> providerManager.imageModelId.value
        "embedding" -> providerManager.embeddingModelId.value
        "rerank" -> providerManager.rerankModelId.value
        else -> error("未知默认模型角色: $role")
    }

    private data class ModelFixture(
        val providerId: String,
        val role: String,
        val remoteId: String,
        val name: String,
        val type: String,
        val capabilities: List<String>,
    ) {
        val id: String
            get() = stableModelId(providerId, remoteId)

        fun toModelInfo(): ModelInfo = ModelInfo(
            name = name,
            id = id,
            description = "Task 8 device fixture",
            enabled = true,
            type = type,
            capabilities = capabilities,
            providerName = "Task 8",
            providerId = providerId,
            remoteModelId = remoteId,
            chatEndpointCompatible = if (role == "summary") {
                SupportState.SUPPORTED
            } else {
                SupportState.UNKNOWN
            },
            userEditedFields = setOf("name", "type", "capabilities"),
        )
    }

    private companion object {
        val PRESET_KEYS = setOf(
            "preset_summary_model",
            "preset_image_model",
            "preset_embedding_model",
            "preset_rerank_model",
        )
    }

    private class CompletedOnboardingRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val preferences = context.getSharedPreferences(
                    OnboardingStateStore.PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                )
                val originalValues = preferences.all.mapValues { (_, value) ->
                    if (value is Set<*>) value.filterIsInstance<String>().toSet() else value
                }
                preferences.edit().putString("step", "COMPLETED").commit()
                try {
                    base.evaluate()
                } finally {
                    val editor = preferences.edit().clear()
                    originalValues.forEach { (key, value) ->
                        when (value) {
                            is String -> editor.putString(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                    editor.commit()
                }
            }
        }
    }
}
