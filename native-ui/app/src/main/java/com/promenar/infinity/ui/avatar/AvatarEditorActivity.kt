package com.promenar.infinity.ui.avatar

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.util.LocaleHelper

/**
 * 应用内头像编辑器：圆形取景、实时预览、缩放旋转镜像，输出 512×512 JPEG 到调用方给定的位置。
 * 启动与结果合同见 [AvatarEditorContract]。
 */
class AvatarEditorActivity : ComponentActivity() {
    private val viewModel: AvatarEditorViewModel by viewModels {
        viewModelFactory { initializer { AvatarEditorViewModel(application, createSavedStateHandle()) } }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase, LocaleHelper.getSavedLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val source = intent?.data
        val destination = intent?.let {
            IntentCompat.getParcelableExtra(it, AvatarEditorContract.EXTRA_DESTINATION, Uri::class.java)
        }
        if (source == null || destination == null) {
            setResult(AvatarEditorContract.RESULT_FAILED, destination?.let { AvatarEditorContract.resultIntent(it) })
            finish()
            return
        }
        // 返回手势与关闭按钮都按取消处理，结果里带回输出位置供调用方清理。
        setResult(Activity.RESULT_CANCELED, AvatarEditorContract.resultIntent(destination))
        viewModel.start(source)
        val themeStore = (application as InfinityApplication).themePreferenceStore
        setContent {
            val preferences by themeStore.state.collectAsStateWithLifecycle()
            InfinityTheme(preferences = preferences) {
                AvatarEditorRoute(
                    viewModel = viewModel,
                    destination = destination,
                    onFinish = { resultCode ->
                        setResult(resultCode, AvatarEditorContract.resultIntent(destination))
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun AvatarEditorRoute(
    viewModel: AvatarEditorViewModel,
    destination: Uri,
    onFinish: (Int) -> Unit,
) {
    val currentOnFinish by rememberUpdatedState(onFinish)
    val snackbarHostState = remember { SnackbarHostState() }
    val replaceFailedMessage = stringResource(R.string.avatar_editor_replace_failed)
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.replace(uri)
    }

    LaunchedEffect(viewModel.outcome) {
        when (viewModel.outcome) {
            AvatarEditorOutcome.SAVED -> currentOnFinish(Activity.RESULT_OK)
            AvatarEditorOutcome.FAILED -> currentOnFinish(AvatarEditorContract.RESULT_FAILED)
            null -> Unit
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.replaceFailed.collect { snackbarHostState.showSnackbar(replaceFailedMessage) }
    }

    val source = viewModel.image
    val image = remember(source) {
        source?.let { AvatarEditorImage(it.preview.asImageBitmap(), it.geometry) }
    }
    AvatarEditorContent(
        image = image,
        state = viewModel.cropState,
        saving = viewModel.saving,
        snackbarHostState = snackbarHostState,
        actions = remember(viewModel, destination) {
            AvatarEditorActions(
                onClose = { currentOnFinish(Activity.RESULT_CANCELED) },
                onDone = { viewModel.save(destination) },
                onChange = viewModel::update,
                onReplace = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        },
    )
}
