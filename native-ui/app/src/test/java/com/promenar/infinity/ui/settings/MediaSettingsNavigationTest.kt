package com.promenar.infinity.ui.settings

import com.promenar.infinity.navigation.isSettingsPaneRoute
import org.junit.Assert.*
import org.junit.Test

class MediaSettingsNavigationTest {
    @Test fun allMediaSettingsDestinationsStayInsideTheSettingsPane() {
        listOf(
            "media_models",
            "media_model/cloud%2Fa",
            "comfy_hub",
            "comfy_host/main",
            "comfy_host_form",
            "comfy_host_form?hostId=main",
            "comfy_template/ref",
            "comfy_workflow_editor?hostId=main",
            "comfy_workflow_editor?hostId=main&workflowId=wf1",
        ).forEach {
            assertTrue(it, isSettingsPaneRoute(it))
        }
    }
    @Test fun conversationHandoffLeavesTheSettingsPane() {
        assertFalse(isSettingsPaneRoute("chat_hero/session"))
    }
}
