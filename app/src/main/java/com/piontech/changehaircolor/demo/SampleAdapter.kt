package com.piontech.changehaircolor.demo

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.piontech.changehaircolor.demo.util.MediaUtils
import java.util.concurrent.Executors

/** Grid of bundled sample portraits from the assets "samples" folder. */
class SampleAdapter(
    private val assetPaths: List<String>,
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<SampleAdapter.VH>() {

    private val io = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.sample_image)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_sample, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = assetPaths.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val path = assetPaths[position]
        holder.image.setImageDrawable(null)
        holder.image.tag = path
        val context = holder.image.context.applicationContext
        io.execute {
            val thumb: Bitmap? = MediaUtils.loadAsset(context, path, maxDim = 320)
            ui.post {
                if (holder.image.tag == path) holder.image.setImageBitmap(thumb)
            }
        }
        holder.itemView.setOnClickListener { onClick(path) }
    }
}
