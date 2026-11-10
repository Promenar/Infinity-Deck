package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.theme.InfinitySpacing

/** “设置 → 宠物”：会话宠物的总开关。宠物默认关闭。 */
@Composable
fun PetSettingsScreen(onBack: () -> Unit) {
    val store = (LocalContext.current.applicationContext as InfinityApplication).petPreferenceStore
    val enabled by store.enabled.collectAsStateWithLifecycle()
    InfinitySettingsPageLayout(title = stringResource(R.string.pet_settings_title), onBack = onBack) { padding ->
        PetSettingsContent(
            enabled = enabled,
            onEnabledChange = store::setEnabled,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun PetSettingsContent(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = InfinitySpacing.Large),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.SectionGap),
    ) {
        InfinitySettingsGroup(footer = stringResource(R.string.pet_settings_enabled_footer)) {
            SettingsToggle(
                title = stringResource(R.string.pet_settings_enabled),
                checked = enabled,
                onCheckedChange = onEnabledChange,
                icon = Icons.Rounded.Pets,
                switchModifier = Modifier.testTag(PET_ENABLED_SWITCH_TAG),
            )
        }
    }
}

internal const val PET_ENABLED_SWITCH_TAG = "pet_enabled_switch"
