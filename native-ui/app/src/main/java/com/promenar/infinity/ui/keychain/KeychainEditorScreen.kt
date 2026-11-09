package com.promenar.infinity.ui.keychain

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.domain.keychain.KeychainItem
import com.promenar.infinity.ui.common.InfinityFieldRow
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySnackbar
import com.promenar.infinity.ui.common.InfinitySnackbarData
import com.promenar.infinity.ui.common.SecretKeyboardOptions
import com.promenar.infinity.ui.common.SecretTextInput
import com.promenar.infinity.ui.common.excludeFromAutofill
import com.promenar.infinity.ui.common.SnackbarType
import kotlinx.coroutines.launch

/** 编辑页表单；值只在页面内存在，保存、离开或切到后台时清空。 */
internal data class KeychainEditorForm(
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val value: String = "",
    val note: String = "",
) {
    companion object {
        fun from(item: KeychainItem?) = KeychainEditorForm(
            name = item?.name.orEmpty(),
            url = item?.url.orEmpty(),
            username = item?.username.orEmpty(),
            note = item?.note.orEmpty(),
        )
    }
}

internal data class KeychainEditorState(
    val editing: Boolean,
    val form: KeychainEditorForm,
    val valueVisible: Boolean = false,
    val urlError: Boolean = false,
    val saving: Boolean = false,
) {
    /** 新建必须有名称与值；编辑时值留空表示保留原值。 */
    val canSave: Boolean get() = !saving && form.name.isNotBlank() && (editing || form.value.isNotEmpty())
}

internal data class KeychainEditorActions(
    val onBack: () -> Unit = {},
    val onFormChange: (KeychainEditorForm) -> Unit = {},
    val onToggleValueVisible: () -> Unit = {},
    val onGenerate: () -> Unit = {},
    val onSave: () -> Unit = {},
)

@Composable
fun KeychainEditorScreen(ref: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val viewModel: KeychainViewModel = viewModel(factory = KeychainViewModel.factory(application))
    val items by viewModel.items.collectAsState()
    val editing = ref?.let { r -> items?.firstOrNull { it.ref == r } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // 名称、网址、用户名与备注随配置变化保留；值不进 saveable 状态。
    var name by rememberSaveable { mutableStateOf<String?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var valueVisible by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (name == null && (ref == null || editing != null)) {
            val form = KeychainEditorForm.from(editing)
            name = form.name; url = form.url; username = form.username; note = form.note
        }
    }
    DisposableEffect(Unit) { onDispose { value = "" } }
    LifecycleResumeEffect(Unit) { onPauseOrDispose { valueVisible = false } }
    val invalidUrl = stringResource(R.string.keychain_invalid_url)
    val saveFailed = stringResource(R.string.keychain_save_failed)
    if (ref != null && editing == null) {
        InfinitySettingsPageLayout(title = stringResource(R.string.keychain_edit_title), onBack = onBack) {}
        return
    }
    Box(Modifier.fillMaxSize()) {
        KeychainEditorContent(
            state = KeychainEditorState(
                editing = editing != null,
                form = KeychainEditorForm(name.orEmpty(), url, username, value, note),
                valueVisible = valueVisible,
                urlError = urlError,
                saving = saving,
            ),
            actions = KeychainEditorActions(
                onBack = { value = ""; onBack() },
                onFormChange = { form ->
                    if (form.url != url) urlError = false
                    name = form.name; url = form.url; username = form.username; value = form.value; note = form.note
                },
                onToggleValueVisible = { valueVisible = !valueVisible },
                onGenerate = {
                    val generated = generateKeychainPassword()
                    value = String(generated)
                    generated.fill('\u0000')
                    valueVisible = true
                },
                onSave = {
                    val secret = value.takeIf { it.isNotEmpty() }?.toCharArray()
                    saving = true
                    scope.launch {
                        val result = viewModel.save(editing, name.orEmpty(), url, username, note, secret)
                        saving = false
                        when (result) {
                            is KeychainSaveResult.Saved -> { value = ""; onSaved(result.ref) }
                            is KeychainSaveResult.Failed -> when (result.error) {
                                KeychainSaveError.INVALID_URL -> { urlError = true; snackbar.showSnackbar(invalidUrl) }
                                KeychainSaveError.FAILED -> snackbar.showSnackbar(saveFailed)
                            }
                        }
                    }
                },
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { data ->
            InfinitySnackbar(InfinitySnackbarData(data.visuals.message, SnackbarType.ERROR))
        }
    }
}

/** 自定义与网站登录条目的新建与编辑：填了网址即为网站登录。 */
@Composable
internal fun KeychainEditorContent(state: KeychainEditorState, actions: KeychainEditorActions, modifier: Modifier = Modifier) {
    val form = state.form
    InfinitySettingsPageLayout(
        title = stringResource(if (state.editing) R.string.keychain_edit_title else R.string.keychain_new_entry),
        onBack = actions.onBack,
        modifier = modifier,
        actions = {
            TextButton(onClick = actions.onSave, enabled = state.canSave, modifier = Modifier.testTag("keychain_editor_save")) {
                Text(stringResource(R.string.keychain_save), maxLines = 1)
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().testTag("keychain_editor"), contentPadding = padding) {
            item(key = "fields") {
                InfinitySettingsGroup {
                    InfinityFieldRow(
                        label = stringResource(R.string.keychain_field_name),
                        value = form.name,
                        onValueChange = { actions.onFormChange(form.copy(name = it)) },
                        placeholder = stringResource(R.string.keychain_field_name_placeholder),
                        fieldModifier = Modifier.testTag("keychain_field_name"),
                    )
                    InfinityFieldRow(
                        label = stringResource(R.string.keychain_field_url),
                        value = form.url,
                        onValueChange = { actions.onFormChange(form.copy(url = it)) },
                        placeholder = stringResource(R.string.keychain_field_url_placeholder),
                        error = stringResource(R.string.keychain_invalid_url).takeIf { state.urlError },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        fieldModifier = Modifier.testTag("keychain_field_url"),
                    )
                    InfinityFieldRow(
                        label = stringResource(R.string.keychain_field_username),
                        value = form.username,
                        onValueChange = { actions.onFormChange(form.copy(username = it)) },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        fieldModifier = Modifier.testTag("keychain_field_username"),
                    )
                    SecretTextInput {
                        InfinityFieldRow(
                            label = stringResource(if (state.editing) R.string.keychain_field_value_keep else R.string.keychain_field_value),
                            value = form.value,
                            onValueChange = { actions.onFormChange(form.copy(value = it)) },
                            monospace = true,
                            keyboardOptions = SecretKeyboardOptions,
                            visualTransformation = if (state.valueVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            fieldModifier = Modifier.testTag("keychain_field_value").excludeFromAutofill(),
                            trailing = {
                                Row {
                                    IconButton(onClick = actions.onToggleValueVisible) {
                                        Icon(
                                            if (state.valueVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                            contentDescription = stringResource(
                                                if (state.valueVisible) R.string.keychain_hide_value else R.string.keychain_show_value,
                                            ),
                                        )
                                    }
                                    IconButton(onClick = actions.onGenerate, modifier = Modifier.testTag("keychain_generate")) {
                                        Icon(Icons.Rounded.Casino, contentDescription = stringResource(R.string.keychain_generate))
                                    }
                                }
                            },
                        )
                    }
                    InfinityFieldRow(
                        label = stringResource(R.string.keychain_field_note),
                        value = form.note,
                        onValueChange = { actions.onFormChange(form.copy(note = it)) },
                        singleLine = false,
                        fieldModifier = Modifier.testTag("keychain_field_note"),
                    )
                }
            }
        }
    }
}
