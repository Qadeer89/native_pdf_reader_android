package com.pdfreader.app.data.signatures

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Backlog item 8 (PDF-007): a signature imported from a photo/scan usually comes on a white or
 * light paper background, which otherwise gets stamped onto the page as an opaque rectangle. This
 * makes that background transparent so only the dark ink strokes are stamped, without needing any
 * ML/segmentation model — just a per-pixel luminance threshold with a feathered edge band so
 * anti-aliased stroke edges don't get a hard cutout.
 *
 * Pixels brighter than [featherStartLuminance] are left untouched; pixels darker than
 * [opaqueBelowLuminance] are left fully opaque (ink); luminance in between is linearly ramped to
 * transparent. Does not touch pixels that already carry non-trivial alpha (e.g. a signature drawn
 * or typed in-app on a transparent canvas), so re-processing an already-transparent signature is a
 * no-op.
 */
fun removeLightBackground(
    source: Bitmap,
    opaqueBelowLuminance: Int = 140,
    featherStartLuminance: Int = 225,
): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    val span = (featherStartLuminance - opaqueBelowLuminance).coerceAtLeast(1)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val alpha = Color.alpha(pixel)
        if (alpha < 255) continue
        val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
        val newAlpha = when {
            luminance <= opaqueBelowLuminance -> 255
            luminance >= featherStartLuminance -> 0
            else -> (255 * (featherStartLuminance - luminance) / span).coerceIn(0, 255)
        }
        if (newAlpha != 255) {
            pixels[i] = Color.argb(newAlpha, Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        }
    }

    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    result.setPixels(pixels, 0, width, 0, 0, width, height)
    return result
}
