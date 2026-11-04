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
import kotlin.math.floor
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
    private val modeButtons = ArrayList<Button>()
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
            text = "Pick Horizontal / Vertical / Padding, then drag on the image. Erase removes. Two fingers: pan/zoom."
            textSize = 12f
            alpha = 0.7f
            setPadding(dp(16), 0, dp(16), dp(4))
        })
        editor = NinePatchEditorView(this)
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        fun newRow(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        fun tool(row: LinearLayout, label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            row.addView(
                this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        val row1 = newRow()
        modeButtons.add(tool(row1, "Horizontal") { setMode(0) })
        modeButtons.add(tool(row1, "Vertical") { setMode(1) })
        modeButtons.add(tool(row1, "Padding") { setMode(2) })
        val row2 = newRow()
        eraseButton = tool(row2, "Erase: off") { toggleErase() }
        tool(row2, "Preview") { showPreview() }
        tool(row2, "Clear") { editor.clearMarks() }
        setMode(0)
        root.addView(row1)
        root.addView(row2)
        setContentView(root)
    }

    private fun setMode(mode: Int) {
        editor.mode = mode
        modeButtons.forEachIndexed { index, button -> button.alpha = if (index == mode) 1f else 0.5f }
    }

    private fun toggleErase() {
        editor.erase = !editor.erase
        eraseButton.text = if (editor.erase) "Erase: on" else "Erase: off"
        eraseButton.alpha = if (editor.erase) 1f else 0.7f
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
    var mode = 0 // 0 = horizontal stretch, 1 = vertical stretch, 2 = padding
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

    private var dragging = false
    private var dragX0 = 0f
    private var dragY0 = 0f
    private var dragX1 = 0f
    private var dragY1 = 0f
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

        // Stretch guides (red) and padding box (blue) over the content.
        fill.color = 0x44FF0000
        for (i in 0 until w) {
            if (top[i]) canvas.drawRect(l + i * scale, t, l + (i + 1) * scale, b, fill)
        }
        for (j in 0 until h) {
            if (left[j]) canvas.drawRect(l, t + j * scale, r, t + (j + 1) * scale, fill)
        }
        val padX0 = bottom.indexOfFirst { it }
        val padY0 = right.indexOfFirst { it }
        if (padX0 >= 0 || padY0 >= 0) {
            val px0 = if (padX0 >= 0) padX0 else 0
            val px1 = if (padX0 >= 0) bottom.indexOfLast { it } + 1 else w
            val py0 = if (padY0 >= 0) padY0 else 0
            val py1 = if (padY0 >= 0) right.indexOfLast { it } + 1 else h
            val pr = RectF(l + px0 * scale, t + py0 * scale, l + px1 * scale, t + py1 * scale)
            fill.color = 0x330000FF
            canvas.drawRect(pr, fill)
            line.color = 0xFF2255FF.toInt()
            canvas.drawRect(pr, line)
        }

        // Selection being dragged.
        if (dragging) {
            val x0 = l + min(dragX0, dragX1) * scale
            val x1 = l + max(dragX0, dragX1) * scale
            val y0 = t + min(dragY0, dragY1) * scale
            val y1 = t + max(dragY0, dragY1) * scale
            fill.color = if (erase) 0x66888888 else if (mode == 2) 0x660000FF else 0x66FF0000
            when (mode) {
                0 -> canvas.drawRect(x0, t, max(x1, x0 + 1f), b, fill)
                1 -> canvas.drawRect(l, y0, r, max(y1, y0 + 1f), fill)
                else -> canvas.drawRect(x0, y0, max(x1, x0 + 1f), max(y1, y0 + 1f), fill)
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

    private fun contentX(x: Float): Float =
        ((x - (tx + scale)) / scale).coerceIn(0f, (content?.width ?: 0).toFloat())

    private fun contentY(y: Float): Float =
        ((y - (ty + scale)) / scale).coerceIn(0f, (content?.height ?: 0).toFloat())

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (content == null) {
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            panning = false
        }
        if (event.pointerCount > 1) {
            dragging = false
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
            }
            lastFocusX = fx
            lastFocusY = fy
            panning = true
            invalidate()
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
                dragging = true
                dragX0 = contentX(event.x)
                dragY0 = contentY(event.y)
                dragX1 = dragX0
                dragY1 = dragY0
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                dragX1 = contentX(event.x)
                dragY1 = contentY(event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragX1 = contentX(event.x)
                    dragY1 = contentY(event.y)
                    commitDrag()
                }
                dragging = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }

    // Turns the dragged span/rectangle (in image pixels) into marks.
    private fun commitDrag() {
        val bitmap = content ?: return
        val w = bitmap.width
        val h = bitmap.height
        val i0 = floor(min(dragX0, dragX1)).toInt().coerceIn(0, w - 1)
        val i1 = ceil(max(dragX0, dragX1)).toInt().coerceIn(i0 + 1, w)
        val j0 = floor(min(dragY0, dragY1)).toInt().coerceIn(0, h - 1)
        val j1 = ceil(max(dragY0, dragY1)).toInt().coerceIn(j0 + 1, h)
        when (mode) {
            0 -> for (i in i0 until i1) top[i] = !erase
            1 -> for (j in j0 until j1) left[j] = !erase
            else -> {
                bottom.fill(false)
                right.fill(false)
                if (!erase) {
                    for (i in i0 until i1) bottom[i] = true
                    for (j in j0 until j1) right[j] = true
                }
            }
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
