package com.piontech.changehaircolor.demo.editor

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.piontech.changehaircolor.demo.R
import com.piontech.changehaircolor.core.HairRecolor

/**
 * Horizontal swatch list, equivalent to the original `ColorShadeAdapter` /
 * `CustomRecentColorAdapter`. Each item is a circular swatch; the selected one
 * gets a highlight ring.
 */
class ColorAdapter(
    private var colors: List<Int>,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<ColorAdapter.VH>() {

    var selectedColor: Int = colors.firstOrNull() ?: Color.BLACK
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submit(newColors: List<Int>) {
        colors = newColors
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val swatch: View = view.findViewById(R.id.swatch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_color, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = colors.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val color = colors[position]
        val selected = color == selectedColor
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(HairRecolor.opaque(color))
            if (selected) {
                setStroke(dp(holder.swatch, 3), Color.WHITE)
            } else {
                setStroke(dp(holder.swatch, 1), 0x33000000)
            }
        }
        holder.swatch.background = drawable
        holder.swatch.scaleX = if (selected) 1.12f else 1f
        holder.swatch.scaleY = if (selected) 1.12f else 1f
        holder.itemView.setOnClickListener { onPick(color) }
    }

    private fun dp(v: View, value: Int): Int =
        (value * v.resources.displayMetrics.density).toInt()
}
