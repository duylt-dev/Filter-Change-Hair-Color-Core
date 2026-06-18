package com.piontech.changehaircolor.demo.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.piontech.changehaircolor.demo.recolor.HairRecolor
import kotlin.math.max
import kotlin.math.min

/**
 * Consolidates the original app's `TouchImageView` (pan/zoom) and `BrushImageView`
 * (raised brush cursor + manual mask painting), plus a live recolour preview.
 *
 * It renders: the original photo, then the hair mask recoloured to the chosen
 * colour at the chosen intensity (via [HairRecolor]). In brush mode the user can
 * paint (add hair to the mask) or erase (remove hair). Strokes are recorded so
 * undo / redo / reset rebuild the mask from the segmentation base — exactly the
 * stack-replay approach of the original `HairColorEditActivity.UpdateCanvas`.
 *
 * The mask is white-on-transparent (alpha = hair coverage). Painting sets alpha
 * to opaque white (SRC), erasing clears alpha (CLEAR).
 */
class HairCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    enum class BrushMode { NONE, PAINT, ERASE }

    private data class Stroke(val path: Path, val widthBitmapPx: Float, val mode: BrushMode)

    private var original: Bitmap? = null
    private var baseMask: Bitmap? = null
    private var workingMask: Bitmap? = null
    private var maskCanvas: Canvas? = null

    private val strokes = ArrayList<Stroke>()
    private val redoStrokes = ArrayList<Stroke>()

    private var colorRgb: Int = Color.rgb(62, 1, 15)
    private var intensity: Int = 255
    var brushMode: BrushMode = BrushMode.NONE
    var compare: Boolean = false
        set(value) { field = value; invalidate() }

    /** Brush diameter and cursor-raise offset, in view pixels. */
    var brushSizePx: Float = 60f
    var cursorOffsetPx: Float = 120f

    // --- paints ---
    private val recolorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val srcMode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    private val clearMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val strokePaintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val strokeErasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.TRANSPARENT
        xfermode = clearMode
    }
    private val cursorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val cursorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 200
    }

    // --- transform / gestures ---
    private val displayMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private var fitScale = 1f
    private val maxScaleFactor = 8f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                var factor = detector.scaleFactor
                val current = currentScale()
                factor = factor.coerceIn(fitScale / current, (fitScale * maxScaleFactor) / current)
                displayMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clampMatrix()
                invalidate()
                return true
            }
        })

    private var lastPanX = 0f
    private var lastPanY = 0f
    private var currentPath: Path? = null
    private var cursorX = -1f
    private var cursorY = -1f
    private var showCursor = false

    init {
        // Needed for the cursor shadow layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    var onMaskChanged: (() -> Unit)? = null

    fun setImage(originalBitmap: Bitmap, mask: Bitmap) {
        original = originalBitmap
        baseMask = mask
        workingMask = mask.copy(Bitmap.Config.ARGB_8888, true)
        maskCanvas = Canvas(workingMask!!)
        strokes.clear()
        redoStrokes.clear()
        resetDisplayMatrix()
        invalidate()
    }

    fun setColor(rgb: Int) { colorRgb = rgb; invalidate() }

    fun setIntensity(value: Int) { intensity = value.coerceIn(0, 255); invalidate() }

    fun hasImage(): Boolean = original != null && workingMask != null

    fun canUndo() = strokes.isNotEmpty()
    fun canRedo() = redoStrokes.isNotEmpty()

    fun undo() {
        if (strokes.isEmpty()) return
        redoStrokes.add(strokes.removeAt(strokes.size - 1))
        rebuildMask()
    }

    fun redo() {
        if (redoStrokes.isEmpty()) return
        strokes.add(redoStrokes.removeAt(redoStrokes.size - 1))
        rebuildMask()
    }

    fun reset() {
        strokes.clear()
        redoStrokes.clear()
        rebuildMask()
    }

    /** The composited final image at full working resolution. */
    fun exportBitmap(): Bitmap? {
        val src = original ?: return null
        val mask = workingMask ?: return null
        return HairRecolor.compose(src, mask, colorRgb, intensity)
    }

    // ---------------------------------------------------------------- drawing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetDisplayMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = original ?: return
        canvas.save()
        canvas.concat(displayMatrix)
        canvas.drawBitmap(src, 0f, 0f, null)
        if (!compare) {
            val mask = workingMask
            if (mask != null) {
                HairRecolor.buildPaint(colorRgb, intensity, recolorPaint)
                val sr = Rect(0, 0, mask.width, mask.height)
                val dr = Rect(0, 0, src.width, src.height)
                canvas.drawBitmap(mask, sr, dr, recolorPaint)
            }
        }
        canvas.restore()

        if (showCursor && brushMode != BrushMode.NONE && cursorX >= 0) {
            val ringY = cursorY - cursorOffsetPx
            canvas.drawCircle(cursorX, ringY, brushSizePx / 2f, cursorRingPaint)
            canvas.drawCircle(cursorX, cursorY, 6f, cursorDotPaint)
        }
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (brushMode == BrushMode.NONE) {
            handlePan(event)
        } else {
            if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                handleBrush(event)
            } else {
                // Multi-touch -> pinch zoom; abandon any in-progress stroke.
                finishStroke(record = false)
                showCursor = false
                invalidate()
            }
        }
        return true
    }

    private fun handlePan(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastPanX = event.x
                lastPanY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    displayMatrix.postTranslate(event.x - lastPanX, event.y - lastPanY)
                    clampMatrix()
                    lastPanX = event.x
                    lastPanY = event.y
                    invalidate()
                }
            }
        }
    }

    private fun handleBrush(event: MotionEvent) {
        cursorX = event.x
        cursorY = event.y
        showCursor = true
        // Paint point is raised above the finger by cursorOffsetPx (the original's trick).
        val bp = viewToBitmap(event.x, event.y - cursorOffsetPx)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path().apply { moveTo(bp[0], bp[1]) }
                drawCurrentStroke()
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.lineTo(bp[0], bp[1])
                drawCurrentStroke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishStroke(record = true)
                showCursor = false
            }
        }
        invalidate()
    }

    private fun strokeWidthBitmapPx(): Float = brushSizePx / currentScale()

    private fun drawCurrentStroke() {
        val path = currentPath ?: return
        val mc = maskCanvas ?: return
        val w = strokeWidthBitmapPx()
        if (brushMode == BrushMode.PAINT) {
            strokePaintPaint.xfermode = srcMode
            strokePaintPaint.strokeWidth = w
            mc.drawPath(path, strokePaintPaint)
        } else if (brushMode == BrushMode.ERASE) {
            strokeErasePaint.strokeWidth = w
            mc.drawPath(path, strokeErasePaint)
        }
    }

    private fun finishStroke(record: Boolean) {
        val path = currentPath ?: return
        if (record) {
            strokes.add(Stroke(Path(path), strokeWidthBitmapPx(), brushMode))
            redoStrokes.clear()
            onMaskChanged?.invoke()
        } else {
            // Stroke abandoned (multi-touch) -> rebuild to discard the partial draw.
            rebuildMask()
        }
        currentPath = null
    }

    private fun rebuildMask() {
        val base = baseMask ?: return
        val mask = workingMask ?: return
        val c = maskCanvas ?: return
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.drawBitmap(base, 0f, 0f, null)
        for (s in strokes) {
            if (s.mode == BrushMode.PAINT) {
                strokePaintPaint.xfermode = srcMode
                strokePaintPaint.strokeWidth = s.widthBitmapPx
                c.drawPath(s.path, strokePaintPaint)
            } else {
                strokeErasePaint.strokeWidth = s.widthBitmapPx
                c.drawPath(s.path, strokeErasePaint)
            }
        }
        onMaskChanged?.invoke()
        invalidate()
    }

    // ---------------------------------------------------------------- matrix

    private fun resetDisplayMatrix() {
        val b = original ?: return
        if (width == 0 || height == 0) return
        val scale = min(width / b.width.toFloat(), height / b.height.toFloat())
        val dx = (width - b.width * scale) / 2f
        val dy = (height - b.height * scale) / 2f
        displayMatrix.reset()
        displayMatrix.postScale(scale, scale)
        displayMatrix.postTranslate(dx, dy)
        fitScale = scale
    }

    private fun currentScale(): Float {
        displayMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun viewToBitmap(x: Float, y: Float): FloatArray {
        displayMatrix.invert(inverseMatrix)
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return pts
    }

    /** Keep the image from drifting entirely off-screen; centre when smaller than the view. */
    private fun clampMatrix() {
        val b = original ?: return
        displayMatrix.getValues(matrixValues)
        val scale = matrixValues[Matrix.MSCALE_X]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val contentW = b.width * scale
        val contentH = b.height * scale

        val newTransX = clampAxis(transX, contentW, width.toFloat())
        val newTransY = clampAxis(transY, contentH, height.toFloat())
        displayMatrix.postTranslate(newTransX - transX, newTransY - transY)
    }

    private fun clampAxis(trans: Float, contentSize: Float, viewSize: Float): Float {
        return if (contentSize <= viewSize) {
            (viewSize - contentSize) / 2f          // centre
        } else {
            val minTrans = viewSize - contentSize    // right/bottom edge
            val maxTrans = 0f                        // left/top edge
            max(minTrans, min(trans, maxTrans))
        }
    }
}
