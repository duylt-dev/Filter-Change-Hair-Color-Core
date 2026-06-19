package com.piontech.changehaircolor.core

import android.graphics.Color

/**
 * The hair-color palette, ported 1:1 from the original decompiled app
 * (`com.myapp.haircolor.utils.ColorUtil.colorArray`, 47 entries "R,G,B").
 *
 * In the original, [ColorUtil.colorModels] parsed these strings into ColorShadeModel
 * objects that fed the shade RecyclerView and the editor. We keep them as packed
 * ARGB ints (fully opaque); opacity is handled separately by the intensity slider,
 * exactly like the original (`recolorHair` keeps the mask alpha, `makeTransparent`
 * applies a global alpha).
 */
object ColorPalette {

    /** The 47 base hair colors as packed ARGB ints (opaque). */
    val colors: List<Int> = listOf(
        rgb(62, 1, 15), rgb(78, 6, 1), rgb(126, 57, 47), rgb(205, 73, 35),
        rgb(245, 122, 27), rgb(125, 54, 17), rgb(154, 51, 0), rgb(185, 99, 12),
        rgb(234, 165, 62), rgb(220, 142, 41), rgb(234, 165, 62), rgb(216, 192, 120),
        rgb(138, 96, 48), rgb(150, 112, 95), rgb(226, 171, 95), rgb(220, 153, 57),
        rgb(196, 149, 34), rgb(175, 123, 31), rgb(183, 117, 151), rgb(159, 8, 5),
        rgb(125, 32, 54), rgb(208, 20, 56), rgb(162, 21, 99), rgb(91, 38, 91),
        rgb(139, 125, 204), rgb(79, 43, 152), rgb(59, 44, 202), rgb(7, 124, 134),
        rgb(3, 108, 145), rgb(70, 142, 164), rgb(31, 188, 174), rgb(70, 105, 76),
        rgb(8, 87, 72), rgb(0, 114, 68), rgb(29, 134, 40), rgb(93, 195, 80),
        rgb(65, 188, 42), rgb(9, 8, 6), rgb(77, 48, 71), rgb(89, 68, 67),
        rgb(99, 131, 148), rgb(95, 120, 124), rgb(93, 68, 44), rgb(122, 126, 126),
        rgb(105, 123, 142), rgb(140, 142, 160), rgb(155, 159, 204),
    )

    /** Default selected color (the original default `mColor = {62,1,15,100}`). */
    val defaultColor: Int = colors.first()

    private fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)
}
