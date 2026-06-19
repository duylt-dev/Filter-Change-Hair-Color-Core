package com.piontech.changehaircolor.demo.editor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import com.piontech.changehaircolor.core.ColorPalette
import com.piontech.changehaircolor.core.HairCanvasView
import com.piontech.changehaircolor.core.HairColorFilter
import com.piontech.changehaircolor.core.HairColorStyle
import com.piontech.changehaircolor.demo.data.RecentColorStore
import com.piontech.changehaircolor.demo.databinding.ActivityHairEditorBinding
import com.piontech.changehaircolor.demo.util.ColorPickerDialog
import com.piontech.changehaircolor.demo.util.ImmersiveActivity
import com.piontech.changehaircolor.demo.util.MediaUtils
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

    // Colour selection state.
    private var gradientMode = false
    private var singleColor = ColorPalette.defaultColor
    private var gradTopColor = ColorPalette.colors.first()
    private var gradBottomColor = ColorPalette.colors[25]
    private var activeSlot = SLOT_TOP

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
        paletteAdapter.selectedColor = singleColor
        binding.colorRv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.colorRv.adapter = paletteAdapter

        recentAdapter = ColorAdapter(emptyList()) { onColorPicked(it) }
        binding.recentRv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recentRv.adapter = recentAdapter

        binding.canvas.setStyle(HairColorStyle.Solid(singleColor))
        binding.swatchTop.setOnClickListener { setActiveSlot(SLOT_TOP) }
        binding.swatchBottom.setOnClickListener { setActiveSlot(SLOT_BOTTOM) }
        updateGradientSwatches()
        refreshRecent()
    }

    /** A swatch tap (or custom-colour pick) applies to the current target. */
    private fun onColorPicked(color: Int) {
        if (gradientMode) {
            if (activeSlot == SLOT_TOP) gradTopColor = color else gradBottomColor = color
            binding.canvas.setStyle(HairColorStyle.Gradient(gradTopColor, gradBottomColor))
            updateGradientSwatches()
        } else {
            singleColor = color
            binding.canvas.setStyle(HairColorStyle.Solid(color))
        }
        paletteAdapter.selectedColor = color
        recentAdapter.selectedColor = color
        recentStore.addRecentColor(color)
        refreshRecent()
    }

    private fun setActiveSlot(slot: Int) {
        activeSlot = slot
        paletteAdapter.selectedColor = if (slot == SLOT_TOP) gradTopColor else gradBottomColor
        updateGradientSwatches()
    }

    private fun updateGradientSwatches() {
        binding.swatchTop.background = circle(gradTopColor, gradientMode && activeSlot == SLOT_TOP)
        binding.swatchBottom.background = circle(gradBottomColor, gradientMode && activeSlot == SLOT_BOTTOM)
        binding.gradientPreview.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(opaque(gradTopColor), opaque(gradBottomColor)),
        )
    }

    private fun circle(color: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(opaque(color))
            setStroke(dp(if (selected) 3 else 1), if (selected) Color.WHITE else 0x55000000)
        }

    private fun opaque(c: Int) = c or (0xFF shl 24)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refreshRecent() {
        val recents = recentStore.getRecentColors()
        recentAdapter.submit(recents)
        // Recent row only makes sense in single-colour mode.
        val visible = if (!gradientMode && recents.isNotEmpty()) View.VISIBLE else View.GONE
        binding.recentLabel.visibility = visible
        binding.recentRv.visibility = visible
    }

    // ----------------------------------------------------------- controls

    private fun setupControls() {
        val density = resources.displayMetrics.density
        binding.canvas.brushSizePx = binding.sliderBrush.value * density
        binding.canvas.cursorOffsetPx = binding.sliderOffset.value * density
        binding.canvas.setShine(binding.sliderShine.value.toInt())

        binding.sliderIntensity.addOnChangeListener { _, value, _ ->
            binding.canvas.setIntensity(value.toInt())
        }
        binding.sliderShine.addOnChangeListener { _, value, _ ->
            binding.canvas.setShine(value.toInt())
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

        // Single / Gradient colour sub-tabs.
        binding.colorModeToggle.check(binding.btnSingle.id)
        binding.colorModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            gradientMode = checkedId == binding.btnGradient.id
            binding.gradientRow.visibility = if (gradientMode) View.VISIBLE else View.GONE
            if (gradientMode) {
                activeSlot = SLOT_TOP
                binding.canvas.setStyle(HairColorStyle.Gradient(gradTopColor, gradBottomColor))
                paletteAdapter.selectedColor = gradTopColor
            } else {
                binding.canvas.setStyle(HairColorStyle.Solid(singleColor))
                paletteAdapter.selectedColor = singleColor
            }
            updateGradientSwatches()
            refreshRecent()
        }

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
                HairColorFilter.forImage(this)?.use { filter ->
                    mask = filter.segment(bitmap)
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
        private const val SLOT_TOP = 0
        private const val SLOT_BOTTOM = 1

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
