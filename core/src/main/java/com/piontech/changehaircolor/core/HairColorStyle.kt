package com.piontech.changehaircolor.core

/**
 * How the hair should be recoloured.
 *
 * - [Solid]: one flat colour.
 * - [Gradient]: a vertical two-colour ombré — [topColor] at the roots (top of the
 *   hair) blending down to [bottomColor] at the tips (bottom).
 */
sealed class HairColorStyle {

    data class Solid(val color: Int) : HairColorStyle()

    data class Gradient(val topColor: Int, val bottomColor: Int) : HairColorStyle()

    companion object {
        val DEFAULT: HairColorStyle = Solid(ColorPalette.defaultColor)
    }
}
