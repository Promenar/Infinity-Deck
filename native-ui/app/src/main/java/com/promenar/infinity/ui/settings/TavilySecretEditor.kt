package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.common.SecretField

internal data class SearchSecretActions(
    val onSave: (CharArray) -> Unit = {},
    val onReveal: suspend () -> CharArray? = { null },
    val onClear: () -> Unit = {},
    val onRetry: (SearchSecretErrorCode, CharArray?) -> Unit = { _, secret ->
        secret?.fill('\u0000')
    },
)

@Composable
internal fun TavilySecretEditor(
    viewModel: SearchConfigViewModel,
    state: SearchConfigState,
) {
    TavilySecretEditor(
        actions = SearchSecretActions(
            onSave = { viewModel.saveTavilyApiKey(it) },
            onReveal = viewModel::revealTavilyApiKey,
            onClear = { viewModel.clearTavilyApiKey() },
            onRetry = { code, secret ->
                viewModel.retryTavilySecretFailure(
                    if (code == SearchSecretErrorCode.SAVE_FAILED) secret else null,
                )
                if (code != SearchSecretErrorCode.SAVE_FAILED) secret?.fill('\u0000')
            },
        ),
        state = state,
    )
}

@Composable
internal fun TavilySecretEditor(
    actions: SearchSecretActions,
    state: SearchConfigState,
) = SearchSecretEditor(actions, state.hasTavilyApiKey, state.secretOperation, "search_tavily")

@Composable
internal fun KeenableSecretEditor(
    viewModel: SearchConfigViewModel,
    state: SearchConfigState,
) {
    KeenableSecretEditor(
        actions = SearchSecretActions(
            onSave = { viewModel.saveKeenableApiKey(it) },
            onReveal = viewModel::revealKeenableApiKey,
            onClear = { viewModel.clearKeenableApiKey() },
            onRetry = { _, secret -> viewModel.retryKeenableSecretFailure(secret) },
        ),
        state = state,
    )
}

@Composable
internal fun KeenableSecretEditor(
    actions: SearchSecretActions,
    state: SearchConfigState,
) = SearchSecretEditor(actions, state.hasKeenableApiKey, state.keenableSecretOperation, "search_keenable")

@Composable
private fun SearchSecretEditor(
    actions: SearchSecretActions,
    hasSecret: Boolean,
    operation: SearchSecretOperation,
    tagPrefix: String,
) {
    var edit by remember(tagPrefix) { mutableStateOf("") }
    LaunchedEffect(operation) {
        if (operation is SearchSecretOperation.Saved) edit = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecretField(
            value = edit,
            onValueChange = { edit = it },
            hasStoredSecret = hasSecret,
            onRevealRequest = actions.onReveal,
            onClear = {
                edit = ""
                actions.onClear()
            },
            modifier = Modifier.testTag("${tagPrefix}_secret_field"),
        )
        Button(
            onClick = {
                actions.onSave(edit.toCharArray())
            },
            enabled = edit.isNotBlank() && operation !is SearchSecretOperation.Saving &&
                operation !is SearchSecretOperation.Initializing,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("${tagPrefix}_secret_save"),
        ) {
            Text(stringResource(R.string.shared_btn_save))
        }
        when (operation) {
            SearchSecretOperation.Initializing -> Text(
                stringResource(R.string.search_secret_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SearchSecretOperation.Saving -> Text(
                stringResource(R.string.search_secret_saving),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SearchSecretOperation.Saved -> Text(
                stringResource(R.string.search_secret_saved),
                color = MaterialTheme.colorScheme.primary,
            )
            is SearchSecretOperation.Error -> {
                Text(
                    stringResource(
                        when (operation.code) {
                            SearchSecretErrorCode.LOAD_FAILED -> R.string.search_secret_load_failed
                            SearchSecretErrorCode.SAVE_FAILED -> R.string.search_secret_save_failed
                            SearchSecretErrorCode.CLEAR_FAILED -> R.string.search_secret_clear_failed
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
                InfinityRetryButton(
                    onClick = {
                        actions.onRetry(
                            operation.code,
                            if (operation.code == SearchSecretErrorCode.SAVE_FAILED) {
                                edit.toCharArray()
                            } else {
                                null
                            },
                        )
                    },
                    modifier = Modifier.testTag("${tagPrefix}_secret_retry"),
                )
            }
            SearchSecretOperation.Idle -> Unit
        }
    }
}
