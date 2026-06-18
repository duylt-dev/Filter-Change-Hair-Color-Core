package com.piontech.changehaircolor.demo.recolor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect

/**
 * Hair recolour math, ported from the original decompiled app's Java logic but
 * expressed with a Canvas + colour filter so it is fast enough for realtime.
 *
 * Original pipeline (BitmapUtil in `com.myapp.haircolor`):
 *   1. `recolorHair(mask, color)` — for every hair pixel, keep the mask's alpha
 *      (soft edges) and replace only the RGB with the chosen colour.
 *   2. `makeTransparent(layer, intensity)` — apply a global alpha (0..255) to the
 *      whole coloured layer (the intensity / opacity seekbar).
 *   3. draw the coloured layer over the original photo.
 *
 * `PorterDuffColorFilter(color, SRC_IN)` applied while drawing the mask is the
 * exact equivalent of step 1: SRC_IN keeps the destination (mask) alpha and takes
 * the source (filter) RGB. The paint's global alpha is step 2.
 */
object HairRecolor {

    /**
     * Builds a paint that recolours a white hair mask to [colorRgb] with global
     * opacity [intensity] (0..255). Reuse across draws to avoid allocations.
     */
    fun buildPaint(colorRgb: Int, intensity: Int, into: Paint = Paint(Paint.ANTI_ALIAS_FLAG)): Paint {
        into.reset()
        into.isAntiAlias = true
        into.isFilterBitmap = true
        // Force opaque colour so SRC_IN keeps exactly the mask alpha.
        val opaque = colorRgb or (0xFF shl 24)
        into.colorFilter = PorterDuffColorFilter(opaque, PorterDuff.Mode.SRC_IN)
        into.alpha = intensity.coerceIn(0, 255)
        return into
    }

    /**
     * Composes the final image: [original] photo with its hair recoloured using
     * [mask] (white-on-transparent, alpha = hair coverage), [colorRgb] and
     * [intensity]. Returns a new ARGB_8888 bitmap. Used for export/save.
     */
    fun compose(original: Bitmap, mask: Bitmap, colorRgb: Int, intensity: Int): Bitmap {
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = buildPaint(colorRgb, intensity)
        val src = Rect(0, 0, mask.width, mask.height)
        val dst = Rect(0, 0, out.width, out.height)
        canvas.drawBitmap(mask, src, dst, paint)
        return out
    }

    /**
     * Recolours [mask] into a standalone layer: coloured hair on a transparent
     * background (no original photo). Used by the realtime camera overlay, which
     * draws this on top of a live [PreviewView].
     */
    fun colorizeMask(mask: Bitmap, colorRgb: Int, intensity: Int): Bitmap {
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(mask, 0f, 0f, buildPaint(colorRgb, intensity))
        return out
    }

    /** A tinted (opaque) preview swatch colour for the picker UI. */
    fun opaque(color: Int): Int = color or (0xFF shl 24)

    fun toRgbString(color: Int): String =
        "${Color.red(color)},${Color.green(color)},${Color.blue(color)}"
}
