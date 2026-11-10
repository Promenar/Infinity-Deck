package com.promenar.infinity.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuItem
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.model.catalog.ModelCatalogRuntime
import com.promenar.infinity.data.pet.InstalledPet
import com.promenar.infinity.data.pet.PetImportFailure
import com.promenar.infinity.data.pet.PetImportResult
import com.promenar.infinity.domain.pet.PetIssue
import com.promenar.infinity.domain.pet.PetIssueCode
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.ModelPicker
import com.promenar.infinity.ui.common.ModelSelectionUiModel
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.toModelSelectionUiModel
import com.promenar.infinity.ui.pet.petMicrophoneGranted
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val PET_ENABLED_SWITCH_TAG = "pet_enabled_switch"
internal const val PET_IMPORT_TAG = "pet_import"
private const val PERCH_CLOUD = "cloud"
private const val PERCH_BUBBLE = "bubble"

/** 模型选择器当前的用途。 */
private enum class PetModelPick { LINES, ASSOCIATION }

/** “设置 → 宠物”：总开关、宠物包管理、模型关联、台词与视频片段。宠物默认关闭。 */
@Composable
fun PetSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as InfinityApplication
    val preferences = application.petPreferenceStore
    val store = application.petPackageStore
    val scope = rememberCoroutineScope()
    val settings: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(application))

    val enabled by preferences.enabled.collectAsStateWithLifecycle()
    val pets by store.pets.collectAsStateWithLifecycle()
    val defaultPetId by preferences.defaultPetId.collectAsStateWithLifecycle()
    val modelPets by preferences.modelPets.collectAsStateWithLifecycle()
    val linesEnabled by preferences.linesEnabled.collectAsStateWithLifecycle()
    val linesModelId by preferences.linesModelId.collectAsStateWithLifecycle()
    val videoEnabled by preferences.videoEnabled.collectAsStateWithLifecycle()
    val dismissed by preferences.dismissed.collectAsStateWithLifecycle()
    val flingEnabled by preferences.flingGestureEnabled.collectAsStateWithLifecycle()
    val motionEnabled by preferences.motionSensorEnabled.collectAsStateWithLifecycle()
    val hapticsEnabled by preferences.hapticsEnabled.collectAsStateWithLifecycle()
    val perchKind by preferences.perchKind.collectAsStateWithLifecycle()
    val blowEnabled by preferences.blowEnabled.collectAsStateWithLifecycle()
    val allModels by settings.providerModels.collectAsState()
    val resolver = ModelCatalogRuntime.resolver
    val models = remember(allModels, resolver) {
        allModels.filter { it.enabled }.map { it.toModelSelectionUiModel(resolver) }
    }

    var importResult by remember { mutableStateOf<PetImportResult?>(null) }
    var deletingPetId by remember { mutableStateOf<String?>(null) }
    var exportingPetId by remember { mutableStateOf<String?>(null) }
    var modelPick by remember { mutableStateOf<PetModelPick?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importResult = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { store.import(it) }
                }.getOrNull() ?: PetImportResult.Failed(PetImportFailure.IO)
            }
        }
    }
    // 吹散云朵要用麦克风：打开开关时才申请权限，被拒绝则保持关闭。
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        preferences.setBlowEnabled(granted)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val id = exportingPetId
        exportingPetId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { store.export(id, it) }
                }
            }
        }
    }

    InfinitySettingsPageLayout(title = stringResource(R.string.pet_settings_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = InfinitySpacing.Large),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.SectionGap),
        ) {
            InfinitySettingsGroup(footer = stringResource(R.string.pet_settings_enabled_footer)) {
                SettingsToggle(
                    title = stringResource(R.string.pet_settings_enabled),
                    checked = enabled,
                    onCheckedChange = preferences::setEnabled,
                    icon = Icons.Rounded.Pets,
                    switchModifier = Modifier.testTag(PET_ENABLED_SWITCH_TAG),
                )
                if (enabled && dismissed) {
                    InfinityGroupDivider()
                    InfinitySettingsItem(
                        title = stringResource(R.string.pet_settings_summon),
                        subtitle = stringResource(R.string.pet_settings_dismissed),
                        onClick = { preferences.setDismissed(false) },
                    )
                }
            }
            PetPackagesSection(
                pets = pets,
                activePetId = pets.firstOrNull { it.id == defaultPetId }?.id ?: pets.firstOrNull()?.id,
                onSetDefault = { preferences.setDefaultPetId(it.id) },
                onExport = {
                    exportingPetId = it.id
                    exportLauncher.launch("${it.id}.idpet")
                },
                onDelete = { deletingPetId = it.id },
                onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            )
            PetAssociationsSection(
                associations = modelPets,
                pets = pets,
                models = models,
                onAdd = { modelPick = PetModelPick.ASSOCIATION },
                onSelect = { modelId, pet -> preferences.setModelPet(modelId, pet.id) },
                onRemove = { preferences.setModelPet(it, null) },
            )
            InfinitySettingsSection(
                title = stringResource(R.string.pet_settings_lines_section),
                footer = stringResource(R.string.pet_settings_lines_footer),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.pet_settings_lines_enabled),
                    checked = linesEnabled,
                    onCheckedChange = preferences::setLinesEnabled,
                )
                InfinityGroupDivider()
                InfinityValueRow(
                    title = stringResource(R.string.pet_settings_lines_model),
                    value = petModelName(models, linesModelId) ?: stringResource(R.string.pet_settings_not_set),
                    onClick = { modelPick = PetModelPick.LINES },
                    enabled = linesEnabled,
                )
            }
            InfinitySettingsSection(
                title = stringResource(R.string.pet_settings_play_section),
                footer = stringResource(R.string.pet_settings_play_footer),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.pet_settings_fling),
                    checked = flingEnabled,
                    onCheckedChange = preferences::setFlingGestureEnabled,
                )
                InfinityGroupDivider()
                SettingsToggle(
                    title = stringResource(R.string.pet_settings_motion),
                    checked = motionEnabled,
                    onCheckedChange = preferences::setMotionSensorEnabled,
                )
                InfinityGroupDivider()
                SettingsToggle(
                    title = stringResource(R.string.pet_settings_haptics),
                    checked = hapticsEnabled,
                    onCheckedChange = preferences::setHapticsEnabled,
                )
            }
            InfinitySettingsSection(
                title = stringResource(R.string.pet_settings_perch_section),
                footer = stringResource(R.string.pet_settings_perch_footer),
            ) {
                InfinitySettingsItem(
                    title = stringResource(R.string.pet_settings_perch_cloud),
                    subtitle = stringResource(R.string.pet_settings_perch_selected).takeIf { perchKind != PERCH_BUBBLE },
                    onClick = { preferences.setPerchKind(PERCH_CLOUD) },
                )
                InfinityGroupDivider()
                InfinitySettingsItem(
                    title = stringResource(R.string.pet_settings_perch_bubble),
                    subtitle = stringResource(R.string.pet_settings_perch_selected).takeIf { perchKind == PERCH_BUBBLE },
                    onClick = { preferences.setPerchKind(PERCH_BUBBLE) },
                )
                InfinityGroupDivider()
                SettingsToggle(
                    title = stringResource(R.string.pet_settings_blow),
                    // 权限不随备份迁移：恢复到新设备后没有权限时显示为关闭。
                    checked = blowEnabled && petMicrophoneGranted(context),
                    onCheckedChange = { wanted ->
                        when {
                            !wanted -> preferences.setBlowEnabled(false)
                            petMicrophoneGranted(context) -> preferences.setBlowEnabled(true)
                            else -> microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
            }
            InfinitySettingsGroup(footer = stringResource(R.string.pet_settings_video_footer)) {
                SettingsToggle(
                    title = stringResource(R.string.pet_settings_video_enabled),
                    checked = videoEnabled,
                    onCheckedChange = preferences::setVideoEnabled,
                )
            }
        }
    }

    ModelPicker(
        show = modelPick != null,
        title = stringResource(
            if (modelPick == PetModelPick.LINES) R.string.pet_settings_lines_model else R.string.pet_settings_association_add,
        ),
        currentModelId = if (modelPick == PetModelPick.LINES) linesModelId else "",
        models = models,
        onDismiss = { modelPick = null },
        onSelect = { id, _ ->
            if (modelPick == PetModelPick.LINES) {
                preferences.setLinesModelId(id)
            } else {
                // 选完模型即关联当前默认宠物；要换宠物在该行的菜单里改。
                (pets.firstOrNull { it.id == defaultPetId } ?: pets.firstOrNull())?.let { preferences.setModelPet(id, it.id) }
            }
            modelPick = null
        },
    )

    val deleting = pets.firstOrNull { it.id == deletingPetId }
    if (deleting != null) {
        InfinityConfirmDialog(
            title = stringResource(R.string.pet_settings_delete_title, deleting.manifest.name),
            message = stringResource(R.string.pet_settings_delete_message),
            confirmText = stringResource(R.string.pet_settings_delete),
            onConfirm = {
                deletingPetId = null
                scope.launch { store.delete(deleting.id) }
            },
            onCancel = { deletingPetId = null },
            isDestructive = true,
        )
    }

    val result = importResult
    if (result != null && result !is PetImportResult.Installed) {
        InfinityConfirmDialog(
            title = stringResource(R.string.pet_import_failed_title),
            message = petImportMessage(result),
            confirmText = stringResource(R.string.common_btn_confirm),
            cancelText = null,
            onConfirm = { importResult = null },
            onCancel = { importResult = null },
        )
    }
}

@Composable
private fun PetPackagesSection(
    pets: List<InstalledPet>,
    activePetId: String?,
    onSetDefault: (InstalledPet) -> Unit,
    onExport: (InstalledPet) -> Unit,
    onDelete: (InstalledPet) -> Unit,
    onImport: () -> Unit,
) {
    InfinitySettingsSection(
        title = stringResource(R.string.pet_settings_packages_section),
        footer = stringResource(R.string.pet_settings_packages_footer),
    ) {
        pets.forEach { pet ->
            var menu by remember(pet.id) { mutableStateOf(false) }
            InfinitySettingsItem(
                title = pet.manifest.name,
                subtitleSegments = listOfNotNull(
                    stringResource(R.string.pet_settings_default).takeIf { pet.id == activePetId },
                    pet.manifest.version,
                    pet.manifest.author.takeIf { it.isNotBlank() },
                    pet.manifest.license.name,
                ),
                onClick = { menu = true },
                trailingContent = {
                    // 操作菜单贴着这一行弹出：设为默认、导出、删除。
                    Box {
                        Icon(
                            Icons.Rounded.MoreHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        InfinityDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            InfinityMenuItem(
                                text = { Text(stringResource(R.string.pet_settings_set_default)) },
                                selected = pet.id == activePetId,
                                onClick = {
                                    menu = false
                                    onSetDefault(pet)
                                },
                            )
                            InfinityMenuItem(
                                text = { Text(stringResource(R.string.pet_settings_export)) },
                                enabled = pet.manifest.license.redistribute,
                                onClick = {
                                    menu = false
                                    onExport(pet)
                                },
                            )
                            InfinityMenuItem(
                                text = { Text(stringResource(R.string.pet_settings_delete)) },
                                destructive = true,
                                onClick = {
                                    menu = false
                                    onDelete(pet)
                                },
                            )
                        }
                    }
                },
            )
            InfinityGroupDivider()
        }
        InfinitySettingsItem(
            icon = Icons.Rounded.Add,
            title = stringResource(R.string.pet_settings_import),
            onClick = onImport,
            modifier = Modifier.testTag(PET_IMPORT_TAG),
        )
    }
}

@Composable
private fun PetAssociationsSection(
    associations: Map<String, String>,
    pets: List<InstalledPet>,
    models: List<ModelSelectionUiModel>,
    onAdd: () -> Unit,
    onSelect: (String, InstalledPet) -> Unit,
    onRemove: (String) -> Unit,
) {
    InfinitySettingsSection(
        title = stringResource(R.string.pet_settings_association_section),
        footer = stringResource(R.string.pet_settings_association_footer),
    ) {
        associations.entries.sortedBy { it.key }.forEach { (modelId, petId) ->
            var menu by remember(modelId) { mutableStateOf(false) }
            InfinitySettingsItem(
                title = petModelName(models, modelId) ?: modelId,
                onClick = { menu = true },
                trailingContent = {
                    // 点这一行在宠物名旁弹出菜单：换一只宠物，或解除关联。
                    Box {
                        Text(
                            text = pets.firstOrNull { it.id == petId }?.manifest?.name
                                ?: stringResource(R.string.pet_settings_missing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        InfinityDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            pets.forEach { pet ->
                                InfinityMenuItem(
                                    text = { Text(pet.manifest.name) },
                                    selected = pet.id == petId,
                                    onClick = {
                                        menu = false
                                        onSelect(modelId, pet)
                                    },
                                )
                            }
                            InfinityMenuItem(
                                text = { Text(stringResource(R.string.pet_settings_association_remove_title)) },
                                destructive = true,
                                onClick = {
                                    menu = false
                                    onRemove(modelId)
                                },
                            )
                        }
                    }
                },
            )
            InfinityGroupDivider()
        }
        InfinitySettingsItem(
            icon = Icons.Rounded.Add,
            title = stringResource(R.string.pet_settings_association_add),
            onClick = onAdd,
            enabled = pets.isNotEmpty(),
        )
    }
}

private fun petModelName(models: List<ModelSelectionUiModel>, id: String): String? =
    if (id.isEmpty()) null else models.firstOrNull { it.selectionId == id }?.displayName ?: id

/** 导入失败的说明：失败原因，或校验问题的前几条。 */
@Composable
private fun petImportMessage(result: PetImportResult): String = when (result) {
    is PetImportResult.Installed -> ""
    is PetImportResult.Failed -> stringResource(
        when (result.reason) {
            PetImportFailure.NOT_A_ZIP -> R.string.pet_import_not_zip
            PetImportFailure.MANIFEST_MISSING -> R.string.pet_import_manifest_missing
            PetImportFailure.MANIFEST_UNREADABLE -> R.string.pet_import_manifest_unreadable
            PetImportFailure.PERSONA_UNREADABLE -> R.string.pet_import_persona_unreadable
            PetImportFailure.IO -> R.string.pet_import_io
        },
    )
    is PetImportResult.Invalid -> {
        val lines = result.issues.take(MAX_SHOWN_ISSUES).map { issue ->
            val subject = petIssueSubject(issue)
            val reason = stringResource(petIssueReason(issue.code))
            if (subject.isEmpty()) reason else "$subject：$reason"
        }
        val more = result.issues.size - lines.size
        val tail = if (more > 0) listOf(stringResource(R.string.pet_import_more_issues, more)) else emptyList()
        (lines + tail).joinToString("\n")
    }
}

internal fun petIssueSubject(issue: PetIssue): String {
    val subject = issue.file ?: issue.field ?: return ""
    val limit = issue.limit ?: return subject
    return "$subject（${issue.actual.orEmpty()} / $limit）"
}

internal fun petIssueReason(code: PetIssueCode): Int = when (code) {
    PetIssueCode.PACKAGE_SIZE,
    PetIssueCode.FILE_COUNT,
    PetIssueCode.IMAGE_SIZE,
    PetIssueCode.VIDEO_SIZE,
    PetIssueCode.VIDEO_DURATION,
    -> R.string.pet_issue_limit
    PetIssueCode.UNSAFE_PATH,
    PetIssueCode.DUPLICATE_PATH,
    PetIssueCode.UNSUPPORTED_EXTENSION,
    PetIssueCode.INVALID_FILE_SIZE,
    PetIssueCode.VIDEO_CODEC,
    PetIssueCode.MISSING_MEDIA_METADATA,
    -> R.string.pet_issue_file
    PetIssueCode.MISSING_FILE,
    PetIssueCode.MISSING_CLIP,
    PetIssueCode.MISSING_IDLE,
    PetIssueCode.INVALID_VIDEO_FALLBACK,
    -> R.string.pet_issue_missing
    PetIssueCode.INVALID_CONTRACT,
    PetIssueCode.UNSUPPORTED_CONTRACT,
    -> R.string.pet_issue_contract
    PetIssueCode.PERSONA_LENGTH,
    PetIssueCode.PERSONA_COUNT,
    -> R.string.pet_issue_persona
    PetIssueCode.INVALID_FIELD -> R.string.pet_issue_field
}

private const val MAX_SHOWN_ISSUES = 5
