package com.piontech.changehaircolor.demo.util

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import com.piontech.changehaircolor.demo.R

/**
 * A small self-contained HSV colour picker (Hue / Saturation / Value sliders),
 * replacing the original app's skydoves ColorPickerView so we add no extra deps.
 */
object ColorPickerDialog {

    fun show(context: Context, initial: Int, onPicked: (Int) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)
        val preview = view.findViewById<android.view.View>(R.id.color_preview)
        val hue = view.findViewById<SeekBar>(R.id.seek_hue)
        val sat = view.findViewById<SeekBar>(R.id.seek_sat)
        val value = view.findViewById<SeekBar>(R.id.seek_val)

        val hsv = FloatArray(3)
        Color.colorToHSV(initial or (0xFF shl 24), hsv)
        hue.progress = hsv[0].toInt()
        sat.progress = (hsv[1] * 100).toInt()
        value.progress = (hsv[2] * 100).toInt()

        fun current(): Int = Color.HSVToColor(
            floatArrayOf(hue.progress.toFloat(), sat.progress / 100f, value.progress / 100f)
        )
        fun refresh() { preview.setBackgroundColor(current()) }
        refresh()

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = refresh()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        hue.setOnSeekBarChangeListener(listener)
        sat.setOnSeekBarChangeListener(listener)
        value.setOnSeekBarChangeListener(listener)

        AlertDialog.Builder(context)
            .setTitle(R.string.picker_title)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ -> onPicked(current() and 0x00FFFFFF or (0xFF shl 24)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
