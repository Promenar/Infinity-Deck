package com.promenar.infinity.data.mediagen.registry

import com.promenar.infinity.data.backup.BackupPreferencePolicy
import com.promenar.infinity.data.model.CloudMediaOptions
import org.junit.Assert.*
import org.junit.Test

class MediaBackupPolicyTest {
    @Test fun configurationIsAllowedAndCredentialsRemainDenied() {
        for (key in listOf("media_channel_order_image", "media_channel_order_video", "media_channel_order_audio", "model_info_p::model_media_generation", "model_info_p::token-tts_media_generation")) {
            assertTrue(key, BackupPreferencePolicy.isAllowed("infinity_settings", key))
        }
        for (key in listOf("provider_p_api_key", "model_info_p_api_key", "media_api_key", "media_authorization")) {
            assertFalse(key, BackupPreferencePolicy.isAllowed("infinity_settings", key))
        }
        assertThrows(IllegalArgumentException::class.java) { CloudMediaOptions(baseUrl = "https://user:secret@api.test") }
        assertThrows(IllegalArgumentException::class.java) { CloudMediaOptions(createPath = "/generate?api_key=value") }
    }
}
