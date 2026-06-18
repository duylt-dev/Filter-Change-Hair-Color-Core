package com.piontech.changehaircolor.demo.editor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.piontech.changehaircolor.demo.R
import com.piontech.changehaircolor.demo.data.ColorPalette
import com.piontech.changehaircolor.demo.data.RecentColorStore
import com.piontech.changehaircolor.demo.databinding.ActivityHairEditorBinding
import com.piontech.changehaircolor.demo.segmentation.NativeHairSegmenter
import com.piontech.changehaircolor.demo.util.ColorPickerDialog
import com.piontech.changehaircolor.demo.util.ImmersiveActivity
import com.piontech.changehaircolor.demo.util.MediaUtils
import com.piontech.changehaircolor.demo.view.HairCanvasView
import java.util.concurrent.Executors

/**
 * Main hair-color editor: segment hair (MediaPipe), recolor it live, refine the
 * mask with a brush, then save/share. Mirrors the original app's
 * HairColorOptionActivity (+ HairColorEditActivity brush) flow with a Material 3 UI.
 */
class HairEditorActivity : ImmersiveActivity() {

    private lateinit var binding: ActivityHairEditorBinding
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var recentStore: RecentColorStore
    private lateinit var paletteAdapter: ColorAdapter
    private lateinit var recentAdapter: ColorAdapter

    private var workingBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHairEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)

        recentStore = RecentColorStore(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupColorList()
        setupControls()
        loadAndSegment()
    }

    // ----------------------------------------------------------- color list

    private fun setupColorList() {
        paletteAdapter = ColorAdapter(ColorPalette.colors) { onColorPicked(it) }
        paletteAdapter.selectedColor = ColorPalette.defaultColor
        binding.colorRv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.colorRv.adapter = paletteAdapter

        recentAdapter = ColorAdapter(emptyList()) { onColorPicked(it) }
        binding.recentRv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recentRv.adapter = recentAdapter

        binding.canvas.setColor(ColorPalette.defaultColor)
        refreshRecent()
    }

    private fun onColorPicked(color: Int) {
        binding.canvas.setColor(color)
        paletteAdapter.selectedColor = color
        recentAdapter.selectedColor = color
        recentStore.addRecentColor(color)
        refreshRecent()
    }

    private fun refreshRecent() {
        val recents = recentStore.getRecentColors()
        recentAdapter.submit(recents)
        val visible = if (recents.isEmpty()) View.GONE else View.VISIBLE
        binding.recentLabel.visibility = visible
        binding.recentRv.visibility = visible
    }

    // ----------------------------------------------------------- controls

    private fun setupControls() {
        val density = resources.displayMetrics.density
        binding.canvas.brushSizePx = binding.sliderBrush.value * density
        binding.canvas.cursorOffsetPx = binding.sliderOffset.value * density

        binding.sliderIntensity.addOnChangeListener { _, value, _ ->
            binding.canvas.setIntensity(value.toInt())
        }
        binding.sliderBrush.addOnChangeListener { _, value, _ ->
            binding.canvas.brushSizePx = value * density
        }
        binding.sliderOffset.addOnChangeListener { _, value, _ ->
            binding.canvas.cursorOffsetPx = value * density
        }

        binding.btnCustom.setOnClickListener {
            ColorPickerDialog.show(this, paletteAdapter.selectedColor) { onColorPicked(it) }
        }

        // Hold-to-compare with the original photo.
        binding.btnCompare.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> binding.canvas.compare = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.canvas.compare = false
                    v.performClick()
                }
            }
            true
        }

        binding.btnUndo.setOnClickListener { binding.canvas.undo() }
        binding.btnRedo.setOnClickListener { binding.canvas.redo() }
        binding.btnReset.setOnClickListener { binding.canvas.reset() }
        binding.btnSave.setOnClickListener { saveResult() }

        // Color / Brush tabs.
        binding.modeToggle.check(binding.btnModeColor.id)
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == binding.btnModeBrush.id) {
                binding.panelColor.visibility = View.GONE
                binding.panelBrush.visibility = View.VISIBLE
                if (binding.brushToggle.checkedButtonId == View.NO_ID) {
                    binding.brushToggle.check(binding.btnPaint.id)
                }
                applyBrushMode()
            } else {
                binding.panelColor.visibility = View.VISIBLE
                binding.panelBrush.visibility = View.GONE
                binding.canvas.brushMode = HairCanvasView.BrushMode.NONE
            }
        }

        binding.brushToggle.check(binding.btnPaint.id)
        binding.brushToggle.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) applyBrushMode()
        }
    }

    private fun applyBrushMode() {
        binding.canvas.brushMode =
            if (binding.brushToggle.checkedButtonId == binding.btnErase.id) {
                HairCanvasView.BrushMode.ERASE
            } else {
                HairCanvasView.BrushMode.PAINT
            }
    }

    // ----------------------------------------------------------- load + segment

    private fun loadAndSegment() {
        binding.loading.visibility = View.VISIBLE
        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        val assetPath = intent.getStringExtra(EXTRA_ASSET)

        io.execute {
            val bitmap = when {
                uriString != null -> MediaUtils.loadBitmap(this, Uri.parse(uriString))
                assetPath != null -> MediaUtils.loadAsset(this, assetPath)
                else -> null
            }
            if (bitmap == null) {
                ui.post {
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@execute
            }

            var mask: Bitmap? = null
            try {
                val segmenter = NativeHairSegmenter.createForImage(this)
                if (segmenter != null) {
                    mask = segmenter.segment(bitmap)
                    segmenter.close()
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            val finalMask = mask ?: emptyMask(bitmap.width, bitmap.height)

            ui.post {
                workingBitmap = bitmap
                binding.canvas.setImage(bitmap, finalMask)
                binding.loading.visibility = View.GONE
                if (mask == null) {
                    Toast.makeText(this, R.string.editor_no_hair, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun emptyMask(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }

    // ----------------------------------------------------------- save / share

    private fun saveResult() {
        val result = binding.canvas.exportBitmap()
        if (result == null) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        io.execute {
            val name = "hair_${System.currentTimeMillis()}.png"
            val uri = MediaUtils.saveToGallery(this, result, name)
            ui.post {
                if (uri != null) {
                    Snackbar.make(binding.root, R.string.saved_to_gallery, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_share) { shareBitmap(result) }
                        .show()
                } else {
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareBitmap(bitmap: Bitmap) {
        val uri = MediaUtils.cacheForShare(this, bitmap)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_ASSET = "asset_path"

        fun intentForUri(context: Context, uri: Uri): Intent =
            Intent(context, HairEditorActivity::class.java)
                .putExtra(EXTRA_IMAGE_URI, uri.toString())

        fun intentForAsset(context: Context, assetPath: String): Intent =
            Intent(context, HairEditorActivity::class.java)
                .putExtra(EXTRA_ASSET, assetPath)
    }
}
