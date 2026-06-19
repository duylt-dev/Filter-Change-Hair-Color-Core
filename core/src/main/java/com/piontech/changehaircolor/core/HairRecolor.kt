package com.piontech.changehaircolor.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader

/**
 * Hair recolour math (ported from the original app's BitmapUtil, extended with
 * gradient ombré and shine).
 *
 * The hair [mask] is white-on-transparent (alpha = hair coverage). We produce a
 * "coloured hair layer" (transparent background) that callers blit over the photo:
 *  1. Base colour — [HairColorStyle.Solid] via a SRC_IN colour filter, or
 *     [HairColorStyle.Gradient] via a vertical [LinearGradient] clipped to the mask.
 *  2. Shine — overlay the ORIGINAL hair's grayscale luminance (OVERLAY blend) at
 *     strength `shine`, so the flat colour regains the real highlights/shadows and
 *     looks glossy & dimensional. shine 0 = flat, 255 = full natural luminance.
 *
 * Opacity (`intensity`) is applied by the caller at draw time, not baked in here.
 */
object HairRecolor {

    /** Sensible default shine (medium, natural look). */
    const val DEFAULT_SHINE = 140

    /** Draws the coloured hair layer into [out] (sized to the mask), clearing it first. */
    fun colorizeInto(
        out: Bitmap,
        mask: Bitmap,
        style: HairColorStyle,
        shine: Int,
        original: Bitmap?,
        gradTop: Float,
        gradBottom: Float,
    ) {
        val w = out.width
        val h = out.height
        val c = Canvas(out)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val maskSrc = Rect(0, 0, mask.width, mask.height)
        val dst = Rect(0, 0, w, h)

        when (style) {
            is HairColorStyle.Solid -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    isFilterBitmap = true
                    colorFilter = PorterDuffColorFilter(opaque(style.color), PorterDuff.Mode.SRC_IN)
                }
                c.drawBitmap(mask, maskSrc, dst, p)
            }
            is HairColorStyle.Gradient -> {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, gradTop, 0f, gradBottom,
                        opaque(style.topColor), opaque(style.bottomColor),
                        Shader.TileMode.CLAMP,
                    )
                }
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
                val clip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    isFilterBitmap = true
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                c.drawBitmap(mask, maskSrc, dst, clip)
            }
        }

        if (shine > 0 && original != null) {
            val shineLayer = grayscaleMaskedLayer(original, mask, w, h)
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                alpha = shine.coerceIn(0, 255)
            }
            c.drawBitmap(shineLayer, 0f, 0f, sp)
            shineLayer.recycle()
        }
    }

    /** Allocates a coloured hair layer (transparent background, alpha = hair coverage). */
    fun colorize(
        mask: Bitmap,
        style: HairColorStyle,
        shine: Int = 0,
        original: Bitmap? = null,
        gradTop: Float = 0f,
        gradBottom: Float = -1f,
    ): Bitmap {
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val bottom = if (gradBottom < 0f) mask.height.toFloat() else gradBottom
        colorizeInto(out, mask, style, shine, original, gradTop, bottom)
        return out
    }

    /** [original] photo with its hair recoloured (full composite). */
    fun compose(
        original: Bitmap,
        mask: Bitmap,
        style: HairColorStyle,
        intensity: Int,
        shine: Int,
        gradTop: Float = 0f,
        gradBottom: Float = -1f,
    ): Bitmap {
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
        val colored = colorize(mask, style, shine, original, gradTop, gradBottom)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            alpha = intensity.coerceIn(0, 255)
        }
        canvas.drawBitmap(colored, Rect(0, 0, colored.width, colored.height), Rect(0, 0, out.width, out.height), paint)
        colored.recycle()
        return out
    }

    /** Grayscale of [original], scaled to (w,h) and clipped to the hair [mask]. */
    private fun grayscaleMaskedLayer(original: Bitmap, mask: Bitmap, w: Int, h: Int): Bitmap {
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(layer)
        val gray = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        c.drawBitmap(original, Rect(0, 0, original.width, original.height), Rect(0, 0, w, h), gray)
        val clip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        c.drawBitmap(mask, Rect(0, 0, mask.width, mask.height), Rect(0, 0, w, h), clip)
        return layer
    }

    /** Vertical extent (topY, bottomY) of non-transparent mask pixels, or null if empty. */
    fun verticalMaskBounds(mask: Bitmap): Pair<Float, Float>? {
        val w = mask.width
        val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        var top = -1
        var bottom = -1
        val threshold = 16
        for (y in 0 until h) {
            val row = y * w
            var any = false
            var x = 0
            while (x < w) {
                if ((px[row + x] ushr 24) > threshold) { any = true; break }
                x++
            }
            if (any) {
                if (top < 0) top = y
                bottom = y
            }
        }
        return if (top < 0) null else Pair(top.toFloat(), (bottom + 1).toFloat())
    }

    /** Forces a colour opaque (alpha 0xFF). */
    fun opaque(color: Int): Int = color or (0xFF shl 24)

    fun toRgbString(color: Int): String =
        "${Color.red(color)},${Color.green(color)},${Color.blue(color)}"
}
