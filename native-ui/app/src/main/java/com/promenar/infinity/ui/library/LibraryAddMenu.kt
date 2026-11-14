package com.promenar.infinity.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput

@Composable
internal fun LibraryAddMenu(onRecord: () -> Unit, onImport: () -> Unit, onNote: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        InfinityPillButton(
            text = stringResource(R.string.library_add_action),
            onClick = { expanded = true },
            icon = Icons.Rounded.Add,
            tone = InfinityPillTone.PRIMARY,
        )
        InfinityDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = 196.dp,
            alignToAnchorEnd = true,
        ) {
            InfinityMenuItem(
                text = { Text(stringResource(R.string.recording_entry)) },
                leadingIcon = { Icon(Icons.Rounded.Mic, contentDescription = null) },
                onClick = { expanded = false; onRecord() },
            )
            InfinityMenuItem(
                text = { Text(stringResource(R.string.library_add_import)) },
                leadingIcon = { Icon(Icons.Rounded.UploadFile, contentDescription = null) },
                onClick = { expanded = false; onImport() },
            )
            InfinityMenuItem(
                text = { Text(stringResource(R.string.library_note_title)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null) },
                onClick = { expanded = false; onNote() },
            )
        }
    }
}

@Composable
internal fun LibraryNoteDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val defaultName = stringResource(R.string.library_note_default_name)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.library_note_name)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.library_note_body)) },
                    minLines = 4,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), text) }, enabled = name.isNotBlank() && text.isNotBlank()) {
                Text(stringResource(R.string.library_note_save), maxLines = 1)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_btn_cancel), maxLines = 1) } },
    )
}
