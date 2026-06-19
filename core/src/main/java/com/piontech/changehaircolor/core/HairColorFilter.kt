package com.piontech.changehaircolor.core

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable

/**
 * One-stop facade for the hair color change filter.
 *
 * Wraps the native TNN segmentation engine ([NativeHairSegmenter]) and the
 * recolor math ([HairRecolor]) behind a small API so callers never juggle both:
 *
 * ```
 * val filter = HairColorFilter.forImage(context) ?: return
 * val style = HairColorStyle.Solid(Color.RED)
 * val result = filter.apply(photo, style, intensity = 255, shine = HairRecolor.DEFAULT_SHINE)
 * filter.close()
 * ```
 *
 * A [HairColorStyle] is either [HairColorStyle.Solid] (single colour) or
 * [HairColorStyle.Gradient] (vertical ombré). `shine` (0..255) blends the hair's
 * natural luminance back in for a glossy, dimensional look.
 *
 * For interactive editing, get the [segment] mask once and recolour it cheaply on
 * every change via [recolor] / [colorize] (or let [HairCanvasView] do it).
 * Implements [Closeable] so it works with Kotlin's `use { }`.
 */
class HairColorFilter private constructor(
    private val segmenter: NativeHairSegmenter,
) : Closeable {

    /** Hair mask (ARGB_8888, alpha = hair coverage), or null if no hair / engine failed. */
    fun segment(bitmap: Bitmap): Bitmap? = segmenter.segment(bitmap)

    /**
     * Recoloured hair on a transparent background — for overlaying on a live
     * preview. Pass [original] (the source frame) to enable [shine].
     */
    fun colorize(
        mask: Bitmap,
        style: HairColorStyle,
        shine: Int = 0,
        original: Bitmap? = null,
    ): Bitmap = HairRecolor.colorize(mask, style, shine, original)

    /** [original] with its hair recoloured using [mask] (full composite). */
    fun recolor(
        original: Bitmap,
        mask: Bitmap,
        style: HairColorStyle,
        intensity: Int,
        shine: Int = 0,
    ): Bitmap = HairRecolor.compose(original, mask, style, intensity, shine)

    /** Segment + recolour in a single call. Returns [bitmap] unchanged if no hair found. */
    fun apply(bitmap: Bitmap, style: HairColorStyle, intensity: Int, shine: Int = 0): Bitmap {
        val mask = segment(bitmap) ?: return bitmap
        return recolor(bitmap, mask, style, intensity, shine)
    }

    override fun close() = segmenter.close()

    companion object {
        /** Creates a filter tuned for still images (full-res photo mode), or null on failure. */
        fun forImage(context: Context): HairColorFilter? =
            NativeHairSegmenter.createForImage(context)?.let { HairColorFilter(it) }

        /** Creates a filter tuned for realtime camera frames, or null on failure. */
        fun forCamera(context: Context): HairColorFilter? =
            NativeHairSegmenter.createForCamera(context)?.let { HairColorFilter(it) }
    }
}
