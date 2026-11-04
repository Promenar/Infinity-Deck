package com.promenar.infinity.ui.renderer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 查看器底部操作；提供时替代按地址重新下载图片的旧保存与分享。 */
data class ImageLightboxActions(
    val onSave: () -> Unit,
    val onShare: () -> Unit,
    val onOpenWith: (() -> Unit)? = null,
    /** 进画板标注这张图；为 null 时不显示。 */
    val onAnnotate: (() -> Unit)? = null,
)

/**
 * 图片放大查看。[model] 为交给 Coil 的数据（如工作区媒体模型），缺省时使用 [imageUrl]；
 * [overlay] 绘制在查看器窗口内（如提示宿主）。
 */
@Composable
fun ImageLightbox(
    imageUrl: String,
    onDismiss: () -> Unit,
    model: Any? = null,
    actions: ImageLightboxActions? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    // 组内切换到另一张图时复位缩放、平移与旋转；手势协程持有同一组状态，故复位而不重建。
    LaunchedEffect(model ?: imageUrl) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        rotation = 0f
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.95f))
                    .pointerInput(Unit) {
                        detectTapGestures { onDismiss() }
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = model ?: imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                            rotationZ = rotation
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, rot ->
                                scale = (scale * zoom).coerceIn(0.5f, 5f)
                                offsetX += pan.x
                                offsetY += pan.y
                                rotation += rot
                            }
                        }
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.infinityDomainColors.overlayContent.copy(alpha = 0.2f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.media_action_close),
                        tint = MaterialTheme.infinityDomainColors.overlayContent,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(
                        onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            rotation = 0f
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ZoomOutMap,
                            contentDescription = stringResource(R.string.media_action_reset_zoom),
                            tint = MaterialTheme.infinityDomainColors.overlayContent,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = { rotation = (rotation + 90f) % 360f }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RotateRight,
                            contentDescription = stringResource(R.string.media_action_rotate),
                            tint = MaterialTheme.infinityDomainColors.overlayContent,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (actions != null) actions.onSave() else scope.launch {
                                saveImageToGallery(context, imageUrl)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SaveAlt,
                            contentDescription = stringResource(R.string.media_action_save),
                            tint = MaterialTheme.infinityDomainColors.overlayContent,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (actions != null) actions.onShare() else scope.launch {
                                shareImage(context, imageUrl)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = stringResource(R.string.media_action_share),
                            tint = MaterialTheme.infinityDomainColors.overlayContent,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    actions?.onAnnotate?.let { annotate ->
                        IconButton(onClick = annotate, modifier = Modifier.testTag(UiTags.IMAGE_LIGHTBOX_ANNOTATE)) {
                            Icon(
                                imageVector = Icons.Rounded.Draw,
                                contentDescription = stringResource(R.string.annotation_cd_edit_image),
                                tint = MaterialTheme.infinityDomainColors.overlayContent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    actions?.onOpenWith?.let { openWith ->
                        IconButton(onClick = openWith) {
                            Icon(
                                imageVector = Icons.Rounded.OpenInNew,
                                contentDescription = stringResource(R.string.media_action_open_with),
                                tint = MaterialTheme.infinityDomainColors.overlayContent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                overlay?.invoke(this)
            }
        }
    }
}

private suspend fun loadImageBitmap(context: Context, url: String): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            result.image?.toBitmap()
        } catch (_: Exception) {
            null
        }
    }
}

private suspend fun saveImageToGallery(context: Context, imageUrl: String) {
    val bitmap = loadImageBitmap(context, imageUrl)
    if (bitmap == null) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
        return
    }

    withContext(Dispatchers.IO) {
        val filename = "infinity_${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Infinity Workbench"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Saved to Pictures/Infinity Workbench", Toast.LENGTH_SHORT).show()
            }
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save", Toast.LENGTH_SHORT).show()
            }
        }
        bitmap.recycle()
    }
}

private suspend fun shareImage(context: Context, imageUrl: String) {
    val bitmap = loadImageBitmap(context, imageUrl)
    if (bitmap == null) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
        return
    }

    withContext(Dispatchers.IO) {
        val filename = "infinity_share_${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Infinity Workbench"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent.createChooser(shareIntent, "Share image")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } else {
            bitmap.recycle()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to share", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }
        bitmap.recycle()
    }
}
