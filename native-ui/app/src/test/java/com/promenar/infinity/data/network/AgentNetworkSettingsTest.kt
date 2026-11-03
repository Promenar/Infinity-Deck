package com.promenar.infinity.data.network

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AgentNetworkSettingsTest {
    @Test fun `启动清除局域网旧键并保留其它偏好`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("network-migration-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("script_lan_hosts", "invalid old value").putBoolean("script_approve_writes", true).commit()
        AgentNetworkSettings(prefs)
        assertThat(prefs.contains("script_lan_hosts")).isFalse()
        assertThat(prefs.getBoolean("script_approve_writes", false)).isTrue()
        AgentNetworkSettings(prefs)
        assertThat(prefs.contains("script_lan_hosts")).isFalse()
    }
}
