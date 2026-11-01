package com.promenar.infinity.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.ViewQuilt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.lab.catalog.LabCatalog
import com.promenar.infinity.ui.lab.catalog.LabNode
import com.promenar.infinity.ui.lab.catalog.LabScreenFit
import com.promenar.infinity.ui.lab.catalog.LabVersion
import com.promenar.infinity.ui.lab.catalog.LabVersionKind
import com.promenar.infinity.ui.theme.InfinitySpacing

/**
 * 视觉实验室的一层目录：子目录在“页面”分组，叶子版本按工作流与状态分组。
 * [path] 为空时是根目录，根目录顶部说明所有版本只用脚本数据。
 */
@Composable
fun VisualLabScreen(
    path: String,
    onNavigateBack: () -> Unit,
    onOpenNode: (String) -> Unit,
    onOpenVersion: (LabVersion) -> Unit,
) {
    val node = LabCatalog.node(path) ?: LabCatalog.root
    InfinitySettingsPageLayout(
        title = stringResource(node.title),
        onBack = onNavigateBack,
        modifier = Modifier.testTag("visual_lab_root"),
    ) { contentPadding ->
        VisualLabContent(
            node = node,
            path = path,
            onOpenNode = onOpenNode,
            onOpenVersion = onOpenVersion,
            modifier = Modifier.padding(contentPadding),
        )
    }
}

@Composable
internal fun VisualLabContent(
    node: LabNode,
    path: String,
    onOpenNode: (String) -> Unit,
    onOpenVersion: (LabVersion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nodes = node.children.filterIsInstance<LabNode>()
    val versions = node.children.filterIsInstance<LabVersion>()
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
    ) {
        if (path.isEmpty()) {
            Text(
                text = stringResource(R.string.lab_sandbox_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal),
            )
        }
        if (nodes.isNotEmpty()) {
            InfinitySettingsSection(title = stringResource(R.string.lab_section_pages)) {
                nodes.forEach { child ->
                    val count = LabCatalog.versionCount(child)
                    InfinitySettingsItem(
                        icon = if (path.isEmpty()) Icons.Rounded.FolderOpen else Icons.Rounded.ViewQuilt,
                        title = stringResource(child.title),
                        subtitle = pluralStringResource(R.plurals.lab_node_version_count, count, count),
                        onClick = { onOpenNode(if (path.isEmpty()) child.segment else "$path/${child.segment}") },
                        showChevron = true,
                        modifier = Modifier.testTag("visual_lab_node_${child.segment}"),
                    )
                }
            }
        }
        LabVersionSection(R.string.lab_section_workflows, versions.filter { it.kind == LabVersionKind.WORKFLOW }, onOpenVersion)
        LabVersionSection(R.string.lab_section_states, versions.filter { it.kind == LabVersionKind.STATE }, onOpenVersion)
    }
}

@Composable
private fun LabVersionSection(title: Int, versions: List<LabVersion>, onOpenVersion: (LabVersion) -> Unit) {
    if (versions.isEmpty()) return
    InfinitySettingsSection(title = stringResource(title)) {
        versions.forEach { version ->
            val fit = fitLabel(version.fit)
            val description = stringResource(version.description)
            InfinitySettingsItem(
                icon = if (version.kind == LabVersionKind.WORKFLOW) Icons.Rounded.PlayCircleOutline else Icons.Rounded.Science,
                title = stringResource(version.title),
                subtitle = description,
                subtitleSegments = fit?.let { listOf(it, description) },
                subtitleMaxLines = 2,
                onClick = { onOpenVersion(version) },
                showChevron = true,
                modifier = Modifier.testTag("visual_lab_version_${version.id}"),
            )
        }
    }
}

@Composable
internal fun fitLabel(fit: LabScreenFit): String? = when (fit) {
    LabScreenFit.ANY -> null
    LabScreenFit.INNER -> stringResource(R.string.lab_fit_inner)
    LabScreenFit.OUTER -> stringResource(R.string.lab_fit_outer)
}
