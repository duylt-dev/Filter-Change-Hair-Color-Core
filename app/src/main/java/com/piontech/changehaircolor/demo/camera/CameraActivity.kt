package com.piontech.changehaircolor.demo.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.piontech.changehaircolor.demo.R
import com.piontech.changehaircolor.demo.data.ColorPalette
import com.piontech.changehaircolor.demo.databinding.ActivityCameraBinding
import com.piontech.changehaircolor.demo.editor.ColorAdapter
import com.piontech.changehaircolor.demo.recolor.HairRecolor
import com.piontech.changehaircolor.demo.segmentation.NativeHairSegmenter
import com.piontech.changehaircolor.demo.util.ImmersiveActivity
import com.piontech.changehaircolor.demo.util.MediaUtils
import java.util.concurrent.Executors

/**
 * Realtime hair recolour using CameraX + MediaPipe.
 *
 * The display is decoupled from segmentation so the camera stays smooth even
 * though inference is slow on low-end CPUs:
 *  - A [Preview] use case drives a [androidx.camera.view.PreviewView] at the full
 *    camera frame rate (~30fps) — the live feed never stutters.
 *  - An [ImageAnalysis] use case runs segmentation in the background (whatever rate
 *    the device manages) and posts a recoloured hair layer (transparent elsewhere)
 *    onto an overlay ImageView. The colour therefore trails head movement slightly,
 *    but the preview itself is fluid.
 *
 * Both use cases use a 4:3 aspect ratio and fitCenter scaling so the overlay mask
 * lines up with the preview. Analysis backpressure drops stale frames.
 */
class CameraActivity : ImmersiveActivity() {

    private lateinit var binding: ActivityCameraBinding
    private val ui = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    // Separate thread for the heavy capture save (PNG + IO) so segmentation FPS isn't blocked.
    private val captureExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var segmenter: NativeHairSegmenter? = null
    @Volatile private var currentColor = ColorPalette.defaultColor
    @Volatile private var intensity = 200
    @Volatile private var isFront = true
    @Volatile private var latestColoredMask: Bitmap? = null
    @Volatile private var delegateLabel = "TNN"

    // FPS measurement (touched only on the analysis thread).
    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var lastSegMs = 0L
    private var loggedSize = false

    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        setupControls()

        // Native engine init is heavy (asset copy + model load); do it off the UI thread.
        analysisExecutor.execute {
            segmenter = NativeHairSegmenter.createForCamera(this)
        }

        startCamera()
    }

    private fun setupControls() {
        lateinit var adapter: ColorAdapter
        adapter = ColorAdapter(ColorPalette.colors) { color ->
            currentColor = color
            adapter.selectedColor = color
        }
        adapter.selectedColor = currentColor
        binding.colorRv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.colorRv.adapter = adapter

        intensity = binding.sliderIntensity.value.toInt()
        binding.sliderIntensity.addOnChangeListener { _, value, _ -> intensity = value.toInt() }
        binding.btnSwitch.setOnClickListener {
            isFront = !isFront
            bindUseCases()
        }
        binding.btnCapture.setOnClickListener { capture() }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        // Smooth live preview at the full camera rate.
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
        preview.surfaceProvider = binding.preview.surfaceProvider

        // Low-res 4:3 analysis => cheaper conversion, aligned with the 4:3 preview.
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

        val selector = if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.bindToLifecycle(this, selector, preview, analysis)
        } catch (t: Throwable) {
            t.printStackTrace()
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun analyze(proxy: ImageProxy) {
        val seg = segmenter
        if (seg == null) {
            proxy.close()
            return
        }
        try {
            val raw = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            proxy.close()

            // Rotate + mirror + downscale in a single allocation to cut GC churn.
            val scale = TARGET_MAX_DIM.toFloat() / maxOf(raw.width, raw.height)
            val m = Matrix()
            m.postScale(scale, scale)
            m.postRotate(rotation.toFloat())
            if (isFront) m.postScale(-1f, 1f) // mirror selfie
            val frame = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)

            if (!loggedSize) {
                loggedSize = true
                android.util.Log.i("HairCam", "raw=${raw.width}x${raw.height} frame=${frame.width}x${frame.height}")
            }

            val segStart = SystemClock.elapsedRealtime()
            val mask = seg.segment(frame)
            lastSegMs = SystemClock.elapsedRealtime() - segStart

            // Produce only the recoloured hair layer; the live preview shows through.
            val colored = if (mask != null) {
                HairRecolor.colorizeMask(mask, currentColor, intensity)
            } else {
                null
            }
            latestColoredMask = colored
            ui.post { binding.overlay.setImageBitmap(colored) }

            updateFps()
        } catch (t: Throwable) {
            t.printStackTrace()
            proxy.close()
        }
    }

    /** Rolling FPS over a ~0.5s window; runs on the analysis thread. */
    private fun updateFps() {
        frameCount++
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStart == 0L) {
            fpsWindowStart = now
            return
        }
        val elapsed = now - fpsWindowStart
        if (elapsed >= 500L) {
            val fps = frameCount * 1000f / elapsed
            frameCount = 0
            fpsWindowStart = now
            val text = "FPS: %.1f  seg %dms  %s".format(fps, lastSegMs, delegateLabel)
            ui.post { binding.fps.text = text }
        }
    }

    private fun capture() {
        // Snapshot the clean preview content (no overlay) on the UI thread.
        val viewBmp = binding.preview.bitmap
        if (viewBmp == null) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val seg = segmenter
        val color = currentColor
        val strength = intensity

        // Re-segment the EXACT captured pixels so the mask aligns perfectly (no stretch).
        analysisExecutor.execute {
            val content = cropPreviewContent(viewBmp)
            val result = content.copy(Bitmap.Config.ARGB_8888, true)
            try {
                val mask = seg?.segment(content)
                if (mask != null) {
                    val colored = HairRecolor.colorizeMask(mask, color, strength)
                    Canvas(result).drawBitmap(
                        colored, null, Rect(0, 0, result.width, result.height),
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            // Heavy PNG + IO on a separate thread so realtime segmentation keeps running.
            captureExecutor.execute {
                val name = "hair_cam_${System.currentTimeMillis()}.png"
                val uri = MediaUtils.saveToGallery(this, result, name)
                ui.post {
                    if (uri != null) {
                        Snackbar.make(binding.root, R.string.saved_to_gallery, Snackbar.LENGTH_LONG)
                            .setAction(R.string.action_share) { share(result) }
                            .show()
                    } else {
                        Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * The preview is fitCenter, so [PreviewView.getBitmap] returns a view-sized bitmap
     * with the 4:3 (portrait 3:4) camera content letterboxed. Crop out the black bars
     * to get the real content, which we then segment + recolour.
     */
    private fun cropPreviewContent(viewBmp: Bitmap): Bitmap {
        val vw = viewBmp.width
        val vh = viewBmp.height
        val ar = 3f / 4f // content width / height (portrait)
        val contentW: Int
        val contentH: Int
        if (vw.toFloat() / vh < ar) {
            contentW = vw
            contentH = (vw / ar).toInt()
        } else {
            contentH = vh
            contentW = (vh * ar).toInt()
        }
        val left = ((vw - contentW) / 2).coerceAtLeast(0)
        val top = ((vh - contentH) / 2).coerceAtLeast(0)
        val w = contentW.coerceAtMost(vw - left)
        val h = contentH.coerceAtMost(vh - top)
        return Bitmap.createBitmap(viewBmp, left, top, w, h)
    }

    private fun share(bitmap: Bitmap) {
        val uri = MediaUtils.cacheForShare(this, bitmap)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, getString(R.string.action_share)))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        analysisExecutor.execute { segmenter?.close() }
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    companion object {
        /** Working resolution for segmentation + preview (model input is 256). */
        private const val TARGET_MAX_DIM = 384
    }
}
