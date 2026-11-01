package com.promenar.infinity.ui.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Science
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 导航入口：关闭开关时写回偏好、提示并退出本页。 */
@Composable
fun DeveloperRoute(
    onNavigateBack: () -> Unit,
    onOpenVisualLab: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { DeveloperModeStore.get(context) }
    DeveloperScreen(
        onNavigateBack = onNavigateBack,
        onOpenVisualLab = onOpenVisualLab,
        onDisable = {
            store.setEnabled(false)
            android.widget.Toast.makeText(context, R.string.devmode_disabled, android.widget.Toast.LENGTH_SHORT).show()
            onNavigateBack()
        },
    )
}

/**
 * 开发者页：版本号连点解锁后出现在设置首页。第一期只有视觉实验室入口与关闭开关；
 * 关闭后由调用方收起本页，设置首页随开关隐藏入口。
 */
@Composable
fun DeveloperScreen(
    onNavigateBack: () -> Unit,
    onOpenVisualLab: () -> Unit,
    onDisable: () -> Unit,
) {
    InfinitySettingsPageLayout(
        title = stringResource(R.string.devmode_title),
        onBack = onNavigateBack,
        modifier = Modifier.testTag("developer_root"),
    ) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
        ) {
            InfinitySettingsSection(title = stringResource(R.string.devmode_section_tools)) {
                InfinitySettingsItem(
                    icon = Icons.Rounded.Science,
                    title = stringResource(R.string.lab_title),
                    subtitle = stringResource(R.string.lab_entry_desc),
                    onClick = onOpenVisualLab,
                    showChevron = true,
                    modifier = Modifier.testTag("developer_visual_lab"),
                )
            }
            InfinitySettingsSection(title = stringResource(R.string.devmode_section_mode)) {
                SettingsToggle(
                    title = stringResource(R.string.devmode_toggle_title),
                    description = stringResource(R.string.devmode_toggle_desc),
                    checked = true,
                    onCheckedChange = { if (!it) onDisable() },
                    icon = Icons.Rounded.DeveloperMode,
                    modifier = Modifier.testTag("developer_mode_toggle"),
                )
            }
        }
    }
}
