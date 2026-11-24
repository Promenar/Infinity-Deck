package com.promenar.infinity.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.data.local.inference.StoredModel
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalModelsSettingGateTest {
    @get:Rule val rule = createComposeRule()

    @Test fun modelNameCannotLoadWhileEngineIsDisabled() {
        var loads = 0; var unloads = 0
        rule.setContent {
            InfinityTheme {
                LocalModelsScreenContent(
                    availableModels = listOf(StoredModel("guard", "guard-test.gguf", "/fixture/guard-test.gguf", 1024, "GGUF", 0)),
                    engineEnabled = false,
                    onLoadModel = { _, _ -> loads++ }, onUnloadModel = { unloads++ },
                )
            }
        }
        rule.onNodeWithText("guard-test").performScrollTo().assertHasNoClickAction().performTouchInput { click() }
        assertEquals(0, loads)
        assertEquals(0, unloads)
    }

    @Test fun emptyModelEntryCannotOpenAnotherImporterWhileBusy() {
        var imports = 0
        var importTitle = ""
        rule.setContent {
            InfinityTheme {
                importTitle = stringResource(R.string.local_models_importing)
                LocalModelsScreenContent(isImporting = true, onImport = { imports++ })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(importTitle).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        assertEquals(0, imports)
    }

    @Test fun autoLoadToggleIsDisabledWhenEngineIsOffAndTogglesWhenEngineIsOn() {
        var toggled = mutableListOf<Boolean>()
        var toggleTitle = ""
        rule.setContent {
            InfinityTheme {
                toggleTitle = stringResource(R.string.local_models_auto_load_on_startup)
                LocalModelsScreenContent(
                    engineEnabled = false,
                    autoLoadOnStartup = false,
                    onAutoLoadOnStartupChange = { toggled += it },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(toggleTitle).performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(emptyList<Boolean>(), toggled)
    }

    @Test fun autoLoadToggleInvokesCallbackWhenEngineIsOn() {
        val toggled = mutableListOf<Boolean>()
        var toggleTitle = ""
        rule.setContent {
            InfinityTheme {
                toggleTitle = stringResource(R.string.local_models_auto_load_on_startup)
                LocalModelsScreenContent(
                    engineEnabled = true,
                    autoLoadOnStartup = false,
                    onAutoLoadOnStartupChange = { toggled += it },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(toggleTitle).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(true), toggled)
    }
}
