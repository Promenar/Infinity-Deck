package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.promenar.infinity.ui.theme.InfinitySpacing

@Composable
fun SettingsSectionHeader(
    title: String,
    titleStyle: TextStyle? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.Large + InfinitySpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = (titleStyle ?: MaterialTheme.typography.labelMedium).copy(
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
    }
}
