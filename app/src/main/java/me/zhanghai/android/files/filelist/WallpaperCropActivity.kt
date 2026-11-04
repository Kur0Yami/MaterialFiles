package me.zhanghai.android.files.filelist

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java8.nio.file.Path
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pan/zoom the image under a screen-shaped frame, then set the framed area as wallpaper. */
class WallpaperCropActivity : AppActivity() {
    private lateinit var sourcePath: Path
    private lateinit var cropView: WallpaperCropView
    private val buttons = ArrayList<Button>()
    private var originalWidth = 0
    private var originalHeight = 0
    private var previewWidth = 1
    private var previewHeight = 1

    private val screenWidth: Int
        get() = min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    private val screenHeight: Int
        get() = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.extraPath
        if (path == null) {
            finish()
            return
        }
        sourcePath = path
        buildUi()
        thread {
            val preview = try {
                loadPreview()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (preview == null) {
                    showToast("Cannot open image")
                    finish()
                } else {
                    previewWidth = preview.width
                    previewHeight = preview.height
                    cropView.setImage(preview)
                    buttons.forEach { it.isEnabled = true }
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun loadPreview(): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        sourcePath.newInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        originalWidth = bounds.outWidth
        originalHeight = bounds.outHeight
        if (originalWidth <= 0 || originalHeight <= 0) {
            return null
        }
        var sample = 1
        while (max(originalWidth, originalHeight) / (sample * 2) >= 2048) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return sourcePath.newInputStream().use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "Drag to move, pinch to zoom. The frame is your screen."
            setTextColor(Color.WHITE)
            alpha = 0.8f
            textSize = 13f
            setPadding(dp(16), dp(8), dp(16), dp(4))
        })
        cropView = WallpaperCropView(
            this, screenWidth.toFloat() / screenHeight
        )
        root.addView(cropView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        fun button(label: String, onClick: () -> Unit) {
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                isEnabled = false
                setOnClickListener { onClick() }
            }
            buttons.add(button)
            bar.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            button("Home") { applyWallpaper(WallpaperManager.FLAG_SYSTEM) }
            button("Lock") { applyWallpaper(WallpaperManager.FLAG_LOCK) }
            button("Both") { applyWallpaper(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
        } else {
            button("Set wallpaper") { applyWallpaper(0) }
        }
        button("Reset") { cropView.resetTransform() }
        buttons.last().isEnabled = false
        root.addView(bar)
        setContentView(root)
    }

    private fun applyWallpaper(flags: Int) {
        val crop = cropView.cropRect() ?: return
        val ratioX = originalWidth.toFloat() / previewWidth
        val ratioY = originalHeight.toFloat() / previewHeight
        val region = Rect(
            (crop.left * ratioX).roundToInt().coerceIn(0, originalWidth - 1),
            (crop.top * ratioY).roundToInt().coerceIn(0, originalHeight - 1),
            (crop.right * ratioX).roundToInt().coerceIn(1, originalWidth),
            (crop.bottom * ratioY).roundToInt().coerceIn(1, originalHeight)
        )
        buttons.forEach { it.isEnabled = false }
        val appContext = applicationContext
        thread {
            var success = false
            val message = try {
                val bitmap = decodeRegion(region)
                val manager = WallpaperManager.getInstance(appContext)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    manager.setBitmap(bitmap, null, true, flags)
                } else {
                    manager.setBitmap(bitmap)
                }
                success = true
                "Wallpaper set"
            } catch (e: Exception) {
                "Failed to set wallpaper: " + e.message
            } catch (e: OutOfMemoryError) {
                "Failed to set wallpaper: out of memory"
            }
            appContext.showToast(message)
            runOnUiThread {
                if (success) {
                    finish()
                } else {
                    buttons.forEach { it.isEnabled = true }
                }
            }
        }
    }

    private fun decodeRegion(region: Rect): Bitmap {
        val outWidth = min(region.width(), screenWidth)
        val outHeight = max(1, (outWidth.toFloat() * screenHeight / screenWidth).roundToInt())
        var sample = 1
        while (region.width() / (sample * 2) >= outWidth) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val cropped: Bitmap = try {
            sourcePath.newInputStream().use { stream ->
                @Suppress("DEPRECATION")
                val decoder = BitmapRegionDecoder.newInstance(stream, false)
                try {
                    decoder.decodeRegion(region, options)
                } finally {
                    decoder.recycle()
                }
            } ?: throw IllegalStateException("decode failed")
        } catch (e: Exception) {
            // Fallback for formats the region decoder does not support.
            val full = sourcePath.newInputStream().use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("Cannot decode image")
            Bitmap.createBitmap(full, region.left, region.top, region.width(), region.height())
        }
        return if (cropped.width == outWidth && cropped.height == outHeight) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, outWidth, outHeight, true)
        }
    }
}

private class WallpaperCropView(context: Context, private val aspect: Float) : View(context) {
    private var bitmap: Bitmap? = null
    private val density = resources.displayMetrics.density
    private val frame = RectF()
    private val matrix = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xAA000000.toInt() }
    private val framePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private var scale = 1f
    private var minScale = 1f
    private var tx = 0f
    private var ty = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val bmp = bitmap ?: return false
                val newScale = (scale * detector.scaleFactor).coerceIn(minScale, minScale * 8f)
                val factor = newScale / scale
                tx = detector.focusX - (detector.focusX - tx) * factor
                ty = detector.focusY - (detector.focusY - ty) * factor
                scale = newScale
                clamp(bmp)
                invalidate()
                return true
            }
        }
    )

    fun setImage(bmp: Bitmap) {
        bitmap = bmp
        resetTransform()
    }

    fun resetTransform() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) {
            return
        }
        scale = max(frame.width() / bmp.width, frame.height() / bmp.height)
        minScale = scale
        tx = frame.centerX() - bmp.width * scale / 2f
        ty = frame.centerY() - bmp.height * scale / 2f
        clamp(bmp)
        invalidate()
    }

    // Crop area in the coordinates of the preview bitmap.
    fun cropRect(): RectF? {
        val bmp = bitmap ?: return null
        return RectF(
            ((frame.left - tx) / scale).coerceIn(0f, bmp.width.toFloat()),
            ((frame.top - ty) / scale).coerceIn(0f, bmp.height.toFloat()),
            ((frame.right - tx) / scale).coerceIn(0f, bmp.width.toFloat()),
            ((frame.bottom - ty) / scale).coerceIn(0f, bmp.height.toFloat())
        )
    }

    private fun clamp(bmp: Bitmap) {
        tx = tx.coerceIn(frame.right - bmp.width * scale, frame.left)
        ty = ty.coerceIn(frame.bottom - bmp.height * scale, frame.top)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val margin = 16 * density
        val availW = w - 2 * margin
        val availH = h - 2 * margin
        val frameW = min(availW, availH * aspect)
        val frameH = frameW / aspect
        frame.set(
            (w - frameW) / 2f, (h - frameH) / 2f, (w + frameW) / 2f, (h + frameH) / 2f
        )
        resetTransform()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        matrix.setScale(scale, scale)
        matrix.postTranslate(tx, ty)
        canvas.drawBitmap(bmp, matrix, bitmapPaint)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, frame.top, dimPaint)
        canvas.drawRect(0f, frame.bottom, w, h, dimPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
        canvas.drawRect(frame.right, frame.top, w, frame.bottom, dimPaint)
        canvas.drawRect(frame, framePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        val skip = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) {
                continue
            }
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        if (count == 0) {
            return true
        }
        val fx = sumX / count
        val fy = sumY / count
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastFocusX = fx
                lastFocusY = fy
            }
            MotionEvent.ACTION_MOVE -> {
                tx += fx - lastFocusX
                ty += fy - lastFocusY
                lastFocusX = fx
                lastFocusY = fy
                clamp(bmp)
                invalidate()
            }
        }
        return true
    }
}
