package com.promenar.infinity.data.production

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CharacterCompositorTest {
    private fun image(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(color); return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
    @Test fun `横拼保持原始像素顺序和透明余白且编码确定`() {
        val parts = listOf(image(2, 3, Color.RED), image(4, 1, Color.BLUE))
        val codec = AndroidHorizontalImageCodec()
        val bytes = codec.compose(parts)
        assertArrayEquals(bytes, codec.compose(parts))
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        try {
            assertEquals(6, bitmap.width); assertEquals(3, bitmap.height)
            assertEquals(Color.RED, bitmap.getPixel(0, 2)); assertEquals(Color.BLUE, bitmap.getPixel(5, 0)); assertEquals(Color.TRANSPARENT, bitmap.getPixel(5, 2))
        } finally { bitmap.recycle() }
    }
    @Test fun `相同有序素材跨实例复用且内容或顺序变化产生新文件`() = runTest {
        val files = PlanningFiles()
        files.bytes["ref/front.png"] = image(2, 2, Color.RED)
        files.bytes["ref/back.png"] = image(2, 2, Color.BLUE)
        val paths = listOf("ref/front.png", "ref/back.png")
        val first = CachedCharacterCompositor(files).materialize("s", "productions/p", paths)
        assertEquals(first, CachedCharacterCompositor(files).materialize("s", "productions/p", paths))
        assertEquals(1, files.writes)
        assertNotEquals(first, CachedCharacterCompositor(files).materialize("s", "productions/p", paths.reversed()))
        files.bytes["ref/front.png"] = image(2, 2, Color.GREEN)
        assertNotEquals(first, CachedCharacterCompositor(files).materialize("s", "productions/p", paths))
        assertEquals(3, files.writes)
    }
    @Test fun `损坏图片拒绝生成派生文件`() = runTest {
        val files = PlanningFiles()
        try { CachedCharacterCompositor(files).materialize("s", "productions/p", listOf("ref/front.png")); fail() }
        catch (_: IllegalArgumentException) { assertEquals(0, files.writes) }
    }
    @Test fun `派生缓存被覆盖时拒绝复用`() = runTest {
        val files = PlanningFiles()
        files.bytes["ref/front.png"] = image(2, 2, Color.RED)
        val compositor = CachedCharacterCompositor(files)
        val target = compositor.materialize("s", "productions/p", listOf("ref/front.png"))
        files.bytes[target] = image(2, 2, Color.BLUE)
        try { compositor.materialize("s", "productions/p", listOf("ref/front.png")); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, files.writes)
    }

}
