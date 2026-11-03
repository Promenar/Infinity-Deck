package com.promenar.infinity.ui.avatar

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.R
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AvatarEditorInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 左半红、右半蓝的 900×600 原图，可选写入 EXIF 方向。 */
    private fun sourceImage(name: String, format: Bitmap.CompressFormat, exifOrientation: Int? = null): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.BLUE)
                drawRect(0f, 0f, 450f, 600f, Paint().apply { color = Color.RED })
            }
            FileOutputStream(file).use { assertTrue(bitmap.compress(format, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        if (exifOrientation != null) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    private fun destination(name: String): File =
        File(context.cacheDir, "avatar-crops/$name").apply {
            parentFile?.mkdirs()
            delete()
        }

    private fun isRed(pixel: Int) = Color.red(pixel) > 200 && Color.blue(pixel) < 60
    private fun isBlue(pixel: Int) = Color.blue(pixel) > 200 && Color.red(pixel) < 60

    @Test
    fun exportFollowsExifOrientationAndUserRotation() {
        val source = sourceImage("avatar-editor-exif.jpg", Bitmap.CompressFormat.JPEG, ExifInterface.ORIENTATION_ROTATE_90)
        val output = destination("exif-result.jpg")
        val image = AvatarImageCodec.load(context.contentResolver, Uri.fromFile(source))

        assertEquals(900, image.geometry.rawWidth)
        assertEquals(600, image.geometry.rawHeight)

        // EXIF 顺时针 90°：原图左侧红色转到上方。
        AvatarImageCodec.export(context.contentResolver, image, image.geometry.initialState(), Uri.fromFile(output))
        BitmapFactory.decodeFile(output.absolutePath).let { result ->
            assertNotNull(result)
            assertEquals(512, result.width)
            assertEquals(512, result.height)
            assertTrue(isRed(result.getPixel(256, 60)))
            assertTrue(isBlue(result.getPixel(256, 450)))
            result.recycle()
        }

        // 再顺时针转 90°，总计 180°：红色到右侧。
        val rotated = image.geometry.rotateClockwise(image.geometry.initialState())
        AvatarImageCodec.export(context.contentResolver, image, rotated, Uri.fromFile(output))
        BitmapFactory.decodeFile(output.absolutePath).let { result ->
            assertTrue(isBlue(result.getPixel(60, 256)))
            assertTrue(isRed(result.getPixel(450, 256)))
            result.recycle()
        }
    }

    @Test
    fun undecodableSourceFailsToLoad() {
        val garbage = File(context.cacheDir, "avatar-editor-garbage.jpg").apply { writeText("not an image") }

        val failure = runCatching { AvatarImageCodec.load(context.contentResolver, Uri.fromFile(garbage)) }

        assertTrue(failure.isFailure)
    }

    @Test
    fun editorWritesSquareJpegAndReturnsOutput() {
        val source = sourceImage("avatar-editor-source.png", Bitmap.CompressFormat.PNG)
        val output = destination("editor-result.jpg")
        val intent = AvatarEditorContract().createIntent(
            context,
            AvatarEditorRequest(Uri.fromFile(source), Uri.fromFile(output)),
        )

        ActivityScenario.launchActivityForResult<AvatarEditorActivity>(intent).use { scenario ->
            var done = ""
            var mirror = ""
            scenario.onActivity {
                done = it.getString(R.string.avatar_editor_done)
                mirror = it.getString(R.string.avatar_editor_mirror_description)
            }
            composeRule.waitUntil(10_000) {
                runCatching { composeRule.onNodeWithText(done).assertIsEnabled() }.isSuccess
            }
            composeRule.onNodeWithContentDescription(mirror).performClick()
            composeRule.onNodeWithText(done).performClick()

            val result = scenario.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            assertEquals(
                AvatarEditorResult.Saved(Uri.fromFile(output)),
                AvatarEditorContract().parseResult(result.resultCode, result.resultData),
            )
        }

        val result = BitmapFactory.decodeFile(output.absolutePath)
        assertNotNull(result)
        try {
            assertEquals(512, result.width)
            assertEquals(512, result.height)
            // 镜像后原图左侧红色出现在右侧。
            assertTrue(isBlue(result.getPixel(60, 256)))
            assertTrue(isRed(result.getPixel(450, 256)))
        } finally {
            result.recycle()
        }
    }
}
