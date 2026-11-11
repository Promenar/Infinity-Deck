package com.promenar.infinity.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Javascript
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import com.promenar.infinity.R

/** 内置工具在设置页的分组；枚举顺序即页面上的分组顺序。 */
internal enum class BuiltinToolCategory(@StringRes val titleRes: Int) {
    WEB(R.string.tools_category_web),
    FILES(R.string.tools_category_files),
    EXECUTION(R.string.tools_category_execution),
    MEDIA(R.string.tools_category_media),
    PLANNING(R.string.tools_category_planning),
    OTHER(R.string.tools_category_other),
}

internal fun builtinToolCategory(id: String): BuiltinToolCategory = when (id) {
    "web_search", "web_fetch", "browser_open", "browser_snapshot", "browser_click", "browser_type" -> BuiltinToolCategory.WEB
    "file_read", "file_list", "file_search", "file_diff", "file_write", "file_patch",
    "create_file", "create_directory", "move_file", "delete_file", "copy_knowledge_to_linux" -> BuiltinToolCategory.FILES
    "exec_js", "run_script", "shell_exec", "python_exec", "python_sandbox" -> BuiltinToolCategory.EXECUTION
    "image_generation", "view_media", "media_fetch" -> BuiltinToolCategory.MEDIA
    "state_define", "state_update", "state_get", "state_remove",
    "production_define", "production_attach", "production_get", "production_update", "production_remove",
    "initialize_plan", "update_plan", "get_plan", "drop_plan" -> BuiltinToolCategory.PLANNING
    else -> BuiltinToolCategory.OTHER
}

/** 按分组顺序归类；空分组不出现，组内保持原有顺序。 */
internal fun List<SkillInfo>.groupedByToolCategory(): List<Pair<BuiltinToolCategory, List<SkillInfo>>> {
    val groups = groupBy { builtinToolCategory(it.id) }
    return BuiltinToolCategory.entries.mapNotNull { category -> groups[category]?.let { category to it } }
}

internal fun builtinToolIcon(id: String): ImageVector = when (id) {
    "web_search" -> Icons.Rounded.Search
    "web_fetch" -> Icons.Rounded.Language
    "browser_open" -> Icons.Rounded.Public
    "browser_snapshot" -> Icons.Rounded.Visibility
    "browser_click" -> Icons.Rounded.TouchApp
    "browser_type" -> Icons.Rounded.Keyboard
    "calculator" -> Icons.Rounded.Calculate
    "pet" -> Icons.Rounded.Pets
    "image_generation" -> Icons.Rounded.Image
    "view_media" -> Icons.Rounded.ImageSearch
    "media_fetch" -> Icons.Rounded.Download
    "file_read" -> Icons.Rounded.Description
    "file_write" -> Icons.Rounded.EditNote
    "file_list" -> Icons.Rounded.FolderOpen
    "file_search" -> Icons.Rounded.FindInPage
    "file_diff" -> Icons.Rounded.Difference
    "file_patch" -> Icons.Rounded.AutoFixHigh
    "create_file" -> Icons.AutoMirrored.Rounded.NoteAdd
    "create_directory" -> Icons.Rounded.CreateNewFolder
    "copy_knowledge_to_linux" -> Icons.Rounded.Download
    "move_file" -> Icons.AutoMirrored.Rounded.DriveFileMove
    "delete_file" -> Icons.Rounded.DeleteOutline
    "exec_js" -> Icons.Rounded.Javascript
    "run_script" -> Icons.Rounded.PlayCircleOutline
    "shell_exec" -> Icons.Rounded.Terminal
    "python_exec" -> Icons.Rounded.Code
    "python_sandbox" -> Icons.Rounded.Science
    "agent_task" -> Icons.Rounded.AccountTree
    "request_secret", "show_secret" -> Icons.Rounded.Key
    "initialize_plan" -> Icons.AutoMirrored.Rounded.PlaylistAdd
    "update_plan" -> Icons.Rounded.Checklist
    "get_plan" -> Icons.AutoMirrored.Rounded.ListAlt
    "drop_plan" -> Icons.Rounded.PlaylistRemove
    else -> Icons.Rounded.Code
}
