package com.pdfreader.app.data.signatures

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignatureBackgroundRemovalTest {

    @Test
    fun whiteBackgroundBecomesFullyTransparent() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.WHITE)

        val result = removeLightBackground(source)

        assertEquals(0, Color.alpha(result.getPixel(0, 0)))
    }

    @Test
    fun darkInkStaysFullyOpaque() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.BLACK)

        val result = removeLightBackground(source)

        val pixel = result.getPixel(0, 0)
        assertEquals(255, Color.alpha(pixel))
        assertEquals(0, Color.red(pixel))
    }

    @Test
    fun midGrayIsPartiallyFeathered() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.rgb(182, 182, 182))

        val result = removeLightBackground(source)

        val alpha = Color.alpha(result.getPixel(0, 0))
        assertTrue(alpha > 0)
        assertTrue(alpha < 255)
    }

    @Test
    fun alreadyTransparentPixelsAreLeftUntouched() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.argb(120, 10, 10, 10))

        val result = removeLightBackground(source)

        assertEquals(Color.argb(120, 10, 10, 10), result.getPixel(0, 0))
    }
}
