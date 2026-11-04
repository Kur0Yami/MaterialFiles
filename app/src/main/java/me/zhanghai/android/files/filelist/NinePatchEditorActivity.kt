package me.zhanghai.android.files.filelist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.NinePatchDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java8.nio.file.Path
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Editor for source 9-patch PNGs (1px border: top/left = stretchable area, bottom/right = padding).
 * A plain PNG gets a border added and is saved as "<name>.9.png".
 */
class NinePatchEditorActivity : AppActivity() {
    private lateinit var sourcePath: Path
    private var isNinePatchSource = false
    private lateinit var editor: NinePatchEditorView
    private lateinit var titleText: TextView
    private lateinit var drawButton: Button
    private lateinit var eraseButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.extraPath
        if (path == null) {
            finish()
            return
        }
        sourcePath = path
        isNinePatchSource = path.fileName.toString().endsWith(".9.png", ignoreCase = true)
        buildUi()
        titleText.text = path.fileName.toString()
        thread {
            val bitmap = try {
                path.newInputStream().use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bitmap == null) {
                    showToast("Cannot open image")
                    finish()
                } else {
                    editor.setImage(bitmap, isNinePatchSource)
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(8), dp(4))
        }
        titleText = TextView(this).apply {
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { save() }
        })
        root.addView(bar)
        root.addView(TextView(this).apply {
            text = "Top/left = stretch area, bottom/right = padding. Drag on the edge to mark; two fingers to pan/zoom."
            textSize = 12f
            alpha = 0.7f
            setPadding(dp(16), 0, dp(16), dp(4))
        })
        editor = NinePatchEditorView(this)
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        fun tool(label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            tools.addView(
                this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        drawButton = tool("Draw") { setErase(false) }
        eraseButton = tool("Erase") { setErase(true) }
        tool("Preview") { showPreview() }
        tool("Clear") { editor.clearMarks() }
        setErase(false)
        root.addView(tools)
        setContentView(root)
    }

    private fun setErase(erase: Boolean) {
        editor.erase = erase
        drawButton.alpha = if (erase) 0.5f else 1f
        eraseButton.alpha = if (erase) 1f else 0.5f
    }

    private fun save() {
        val result = editor.buildSourceBitmap() ?: return
        val target = if (isNinePatchSource) sourcePath else uniqueTarget()
        thread {
            val message = try {
                target.newOutputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
                runOnUiThread {
                    sourcePath = target
                    isNinePatchSource = true
                    titleText.text = target.fileName.toString()
                }
                "Saved " + target.fileName
            } catch (e: Exception) {
                "Failed to save: " + e.message
            }
            showToast(message)
        }
    }

    private fun uniqueTarget(): Path {
        val name = sourcePath.fileName.toString()
        val base = name.substringBeforeLast('.', name)
        var candidate = sourcePath.resolveSibling("$base.9.png")
        var index = 1
        while (candidate.exists()) {
            candidate = sourcePath.resolveSibling("$base ($index).9.png")
            index++
        }
        return candidate
    }

    private fun showPreview() {
        val drawable = editor.buildPreviewDrawable(resources)
        if (drawable == null) {
            showToast("Preview unavailable (too many stretch segments)")
            return
        }
        val content = editor.contentSize()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val stage = FrameLayout(this)
        val target = View(this).apply { background = drawable }
        stage.addView(target, FrameLayout.LayoutParams(content.first, content.second, Gravity.CENTER))
        container.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        fun resize(widthProgress: Int?, heightProgress: Int?) {
            val lp = target.layoutParams
            if (widthProgress != null) lp.width = max(1, (content.first * (0.5f + 3.5f * widthProgress / 100f)).toInt())
            if (heightProgress != null) lp.height = max(1, (content.second * (0.5f + 3.5f * heightProgress / 100f)).toInt())
            target.layoutParams = lp
        }
        fun slider(label: String, onChange: (Int) -> Unit) {
            container.addView(TextView(this).apply { text = label; textSize = 12f })
            container.addView(SeekBar(this).apply {
                max = 100
                progress = 14
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = onChange(p)
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            })
        }
        slider("Width") { resize(it, null) }
        slider("Height") { resize(null, it) }
        AlertDialog.Builder(this)
            .setTitle("Preview")
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

private class NinePatchEditorView(context: Context) : View(context) {
    var erase = false
    private var content: Bitmap? = null
    private var top = BooleanArray(0)
    private var left = BooleanArray(0)
    private var bottom = BooleanArray(0)
    private var right = BooleanArray(0)

    private val density = resources.displayMetrics.density
    private var scale = 1f
    private var minScale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitted = false

    private val bitmapPaint = Paint().apply { isFilterBitmap = false }
    private val fill = Paint()
    private val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val destRect = RectF()

    private var lastX = 0f
    private var lastY = 0f
    private var panning = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val scaleDetector = android.view.ScaleGestureDetector(
        context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                val newScale = (scale * detector.scaleFactor).coerceIn(minScale, 80f)
                val factor = newScale / scale
                tx = detector.focusX - (detector.focusX - tx) * factor
                ty = detector.focusY - (detector.focusY - ty) * factor
                scale = newScale
                invalidate()
                return true
            }
        }
    )

    fun setImage(bitmap: Bitmap, isNinePatch: Boolean) {
        val inner: Bitmap
        if (isNinePatch && bitmap.width > 2 && bitmap.height > 2) {
            val w = bitmap.width - 2
            val h = bitmap.height - 2
            inner = Bitmap.createBitmap(bitmap, 1, 1, w, h)
            top = BooleanArray(w) { Color.alpha(bitmap.getPixel(it + 1, 0)) > 127 }
            bottom = BooleanArray(w) { Color.alpha(bitmap.getPixel(it + 1, h + 1)) > 127 }
            left = BooleanArray(h) { Color.alpha(bitmap.getPixel(0, it + 1)) > 127 }
            right = BooleanArray(h) { Color.alpha(bitmap.getPixel(w + 1, it + 1)) > 127 }
        } else {
            inner = bitmap
            top = BooleanArray(bitmap.width)
            bottom = BooleanArray(bitmap.width)
            left = BooleanArray(bitmap.height)
            right = BooleanArray(bitmap.height)
        }
        inner.density = Bitmap.DENSITY_NONE
        content = inner
        fitted = false
        fit()
        invalidate()
    }

    fun contentSize(): Pair<Int, Int> = Pair(content?.width ?: 1, content?.height ?: 1)

    fun clearMarks() {
        top.fill(false)
        bottom.fill(false)
        left.fill(false)
        right.fill(false)
        invalidate()
    }

    private fun fit() {
        val bitmap = content ?: return
        if (width == 0 || height == 0) {
            return
        }
        val margin = 28 * density
        val s = min(
            (width - 2 * margin) / (bitmap.width + 2),
            (height - 2 * margin) / (bitmap.height + 2)
        )
        scale = max(1f, s)
        minScale = max(1f, min(scale, 1f))
        tx = (width - (bitmap.width + 2) * scale) / 2f
        ty = (height - (bitmap.height + 2) * scale) / 2f
        fitted = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted) {
            fit()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = content ?: return
        val w = bitmap.width
        val h = bitmap.height
        val l = tx + scale
        val t = ty + scale
        val r = tx + (w + 1) * scale
        val b = ty + (h + 1) * scale

        // Border background.
        fill.color = 0x33808080
        canvas.drawRect(tx, ty, tx + (w + 2) * scale, ty + (h + 2) * scale, fill)

        // Checkerboard behind the content.
        canvas.save()
        canvas.clipRect(l, t, r, b)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(l, t, r, b, fill)
        fill.color = 0xFFDDDDDD.toInt()
        val square = 8 * density
        val startX = max(l, 0f)
        val startY = max(t, 0f)
        var yy = startY
        var row = ((startY - t) / square).toInt()
        while (yy < min(b, height.toFloat())) {
            var xx = startX
            var col = ((startX - l) / square).toInt()
            while (xx < min(r, width.toFloat())) {
                if ((row + col) % 2 == 0) {
                    canvas.drawRect(
                        l + col * square, t + row * square,
                        l + (col + 1) * square, t + (row + 1) * square, fill
                    )
                }
                xx += square
                col++
            }
            yy += square
            row++
        }
        canvas.restore()

        destRect.set(l, t, r, b)
        canvas.drawBitmap(bitmap, null, destRect, bitmapPaint)

        // Stretch (red) and padding (blue) guides over the content.
        for (i in 0 until w) {
            if (top[i]) {
                fill.color = 0x33FF0000
                canvas.drawRect(l + i * scale, t, l + (i + 1) * scale, b, fill)
            }
            if (bottom[i]) {
                fill.color = 0x330000FF
                canvas.drawRect(l + i * scale, t, l + (i + 1) * scale, b, fill)
            }
        }
        for (j in 0 until h) {
            if (left[j]) {
                fill.color = 0x33FF0000
                canvas.drawRect(l, t + j * scale, r, t + (j + 1) * scale, fill)
            }
            if (right[j]) {
                fill.color = 0x330000FF
                canvas.drawRect(l, t + j * scale, r, t + (j + 1) * scale, fill)
            }
        }

        // Marks on the border.
        fill.color = Color.BLACK
        for (i in 0 until w) {
            if (top[i]) canvas.drawRect(l + i * scale, ty, l + (i + 1) * scale, t, fill)
            if (bottom[i]) canvas.drawRect(l + i * scale, b, l + (i + 1) * scale, b + scale, fill)
        }
        for (j in 0 until h) {
            if (left[j]) canvas.drawRect(tx, t + j * scale, l, t + (j + 1) * scale, fill)
            if (right[j]) canvas.drawRect(r, t + j * scale, r + scale, t + (j + 1) * scale, fill)
        }

        // Outline and cell lines on the border strips.
        line.color = 0x88888888.toInt()
        canvas.drawRect(l, t, r, b, line)
        if (scale >= 8f * density) {
            line.color = 0x44888888
            for (i in 0..w) {
                val x = l + i * scale
                canvas.drawLine(x, ty, x, t, line)
                canvas.drawLine(x, b, x, b + scale, line)
            }
            for (j in 0..h) {
                val y = t + j * scale
                canvas.drawLine(tx, y, l, y, line)
                canvas.drawLine(r, y, r + scale, y, line)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (content == null) {
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            panning = false
        }
        if (event.pointerCount > 1) {
            parent?.requestDisallowInterceptTouchEvent(true)
            scaleDetector.onTouchEvent(event)
            var fx = 0f
            var fy = 0f
            for (i in 0 until event.pointerCount) {
                fx += event.getX(i)
                fy += event.getY(i)
            }
            fx /= event.pointerCount
            fy /= event.pointerCount
            if (panning) {
                tx += fx - lastFocusX
                ty += fy - lastFocusY
                invalidate()
            }
            lastFocusX = fx
            lastFocusY = fy
            panning = true
            return true
        }
        if (panning) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                panning = false
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                markAt(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                val steps = min(400, max(1, ceil(hypot(dx, dy) / max(1f, scale / 2f)).toInt()))
                for (s in 1..steps) {
                    markAt(lastX + dx * s / steps, lastY + dy * s / steps)
                }
                lastX = event.x
                lastY = event.y
            }
        }
        return true
    }

    // Border strips accept touches slightly outside the image, so thin borders are easy to hit.
    private fun markAt(x: Float, y: Float) {
        val bitmap = content ?: return
        val w = bitmap.width
        val h = bitmap.height
        val slop = 28 * density
        val l = tx + scale
        val t = ty + scale
        val r = tx + (w + 1) * scale
        val b = ty + (h + 1) * scale
        val value = !erase
        var changed = false
        if (x >= l && x < r) {
            val i = ((x - l) / scale).toInt().coerceIn(0, w - 1)
            if (y >= ty - slop && y < t) {
                changed = top[i] != value
                top[i] = value
            } else if (y >= b && y < b + scale + slop) {
                changed = bottom[i] != value
                bottom[i] = value
            }
        } else if (y >= t && y < b) {
            val j = ((y - t) / scale).toInt().coerceIn(0, h - 1)
            if (x >= tx - slop && x < l) {
                changed = left[j] != value
                left[j] = value
            } else if (x >= r && x < r + scale + slop) {
                changed = right[j] != value
                right[j] = value
            }
        }
        if (changed) {
            invalidate()
        }
    }

    fun buildSourceBitmap(): Bitmap? {
        val bitmap = content ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val out = Bitmap.createBitmap(w + 2, h + 2, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, 1f, 1f, null)
        for (i in 0 until w) {
            if (top[i]) out.setPixel(i + 1, 0, Color.BLACK)
            if (bottom[i]) out.setPixel(i + 1, h + 1, Color.BLACK)
        }
        for (j in 0 until h) {
            if (left[j]) out.setPixel(0, j + 1, Color.BLACK)
            if (right[j]) out.setPixel(w + 1, j + 1, Color.BLACK)
        }
        return out
    }

    private fun runs(marks: BooleanArray): List<Int> {
        val result = ArrayList<Int>()
        var i = 0
        while (i < marks.size) {
            if (marks[i]) {
                val start = i
                while (i < marks.size && marks[i]) i++
                result.add(start)
                result.add(i)
            } else {
                i++
            }
        }
        return result
    }

    fun buildPreviewDrawable(resources: android.content.res.Resources): NinePatchDrawable? {
        val bitmap = content ?: return null
        val xDivs = runs(top)
        val yDivs = runs(left)
        val numColors = (xDivs.size + 1) * (yDivs.size + 1)
        if (xDivs.size > 127 || yDivs.size > 127 || numColors > 255) {
            return null
        }
        val w = bitmap.width
        val h = bitmap.height
        val padLeft = bottom.indexOfFirst { it }.let { if (it < 0) 0 else it }
        val padRight = bottom.indexOfLast { it }.let { if (it < 0) 0 else w - 1 - it }
        val padTop = right.indexOfFirst { it }.let { if (it < 0) 0 else it }
        val padBottom = right.indexOfLast { it }.let { if (it < 0) 0 else h - 1 - it }
        val buffer = ByteBuffer.allocate(32 + (xDivs.size + yDivs.size + numColors) * 4)
            .order(ByteOrder.nativeOrder())
        buffer.put(1.toByte())
        buffer.put(xDivs.size.toByte())
        buffer.put(yDivs.size.toByte())
        buffer.put(numColors.toByte())
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putInt(padLeft)
        buffer.putInt(padRight)
        buffer.putInt(padTop)
        buffer.putInt(padBottom)
        buffer.putInt(0)
        xDivs.forEach { buffer.putInt(it) }
        yDivs.forEach { buffer.putInt(it) }
        repeat(numColors) { buffer.putInt(1) }
        return try {
            NinePatchDrawable(
                resources, bitmap, buffer.array(), Rect(padLeft, padTop, padRight, padBottom), null
            )
        } catch (e: Exception) {
            null
        }
    }
}
