package com.promenar.infinity.ui.common

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.data.remote.protocol.ProtocolType

/** 协议在界面上的名称，随系统语言切换；存储与迁移匹配另用冻结的历史显示名。 */
fun ProtocolType.label(resources: Resources): String = resources.getString(labelRes)

@Composable
fun ProtocolType.label(): String = stringResource(labelRes)
