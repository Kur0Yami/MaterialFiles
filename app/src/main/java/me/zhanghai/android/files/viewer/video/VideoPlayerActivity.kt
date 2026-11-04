/*
 * Built-in video player (fork patch), Media3 / ExoPlayer.
 * The UI is a View-based replica of the Next Player (anilbeesetti/nextplayer) player screen:
 * layout, dimensions, colors, icons and gesture behavior follow its Compose implementation.
 */

package me.zhanghai.android.files.viewer.video

import android.animation.ValueAnimator
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java8.nio.file.Files
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.util.extraPathList
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Next Player dark color scheme.
private const val PRIMARY = 0xFF80D5D2.toInt()
private const val PRIMARY_CONTAINER = 0xFF00504E.toInt()
private const val SURFACE = 0xFF0E1514.toInt()
private const val ON_SURFACE = 0xFFDDE4E3.toInt()
private const val SURFACE_VARIANT = 0xFF3F4948.toInt()
private const val ON_SURFACE_VARIANT = 0xFFBEC9C7.toInt()
private const val SECONDARY_CONTAINER = 0xFF324B4A.toInt()
private const val ON_SECONDARY_CONTAINER = 0xFFCCE8E6.toInt()
private const val BLACK_30 = 0x4D000000
private const val BLACK_50 = 0x80000000.toInt()
private const val BOOST_COLOR = 0xFFFC6E6E.toInt()

private enum class ContentScale(val label: String, val icon: Int) {
    BEST_FIT("Best fit", R.drawable.np_ic_fit_screen),
    STRETCH("Stretch", R.drawable.np_ic_aspect_ratio),
    CROP("Crop", R.drawable.np_ic_crop_landscape),
    HUNDRED_PERCENT("100%", R.drawable.np_ic_width_wide)
}

private enum class OverlayKind { AUDIO, SUBTITLE, SPEED, SCALE, PLAYLIST }

class VideoPlayerActivity : AppActivity() {
    private lateinit var paths: List<Path>
    private var startIndex = 0
    private var player: ExoPlayer? = null

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var gestureLayer: View
    private lateinit var scrim: View
    private lateinit var controlsRoot: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var middleRow: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var timeText: TextView
    private lateinit var speedText: TextView
    private lateinit var seekBar: PlayerSeekBar
    private lateinit var playPauseIcon: ImageView
    private lateinit var scaleIcon: ImageView
    private lateinit var loopIcon: ImageView
    private lateinit var shuffleIcon: ImageView
    private lateinit var infoText: TextView
    private lateinit var bufferingView: ProgressBar
    private lateinit var lockedLayer: FrameLayout
    private lateinit var doubleTapView: DoubleTapView
    private lateinit var longPressPill: TextView
    private lateinit var volumeIndicator: VerticalProgressView
    private lateinit var brightnessIndicator: VerticalProgressView
    private lateinit var indicatorLayer: FrameLayout
    private lateinit var overlayRoot: FrameLayout
    private lateinit var sheet: LinearLayout
    private lateinit var sheetTitle: TextView
    private lateinit var sheetContent: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    // Insets (system bars + cutout) and system gesture insets.
    private var insetL = 0
    private var insetT = 0
    private var insetR = 0
    private var insetB = 0
    private var gestL = 0
    private var gestT = 0
    private var gestR = 0
    private var gestB = 0

    // Controls state.
    private var controlsVisible = true
    private var controlsLocked = false
    private var isInPip = false
    private var overlayKind: OverlayKind? = null
    private var orientationChosenByUser = false
    private var autoOrientationDone = false

    // Seek state (shared by drag gesture and seekbar).
    private var isSeeking = false
    private var seekStartPosition = 0L
    private var seekAmount: Long? = null
    private var seekStartX = 0f

    // Double tap / long press.
    private var doubleTapSeekMs = 0L
    private var longPressActive = false
    private var speedBeforeLongPress = 1f

    // Zoom.
    private var contentScale = ContentScale.BEST_FIT
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var isZooming = false
    private var showScaleIndicator = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    // Touch.
    private var downX = 0f
    private var downY = 0f
    private var startAllowed = true
    private var dragMode = DRAG_NONE
    private var startingY = 0f
    private var startVolumePercent = 0
    private var startBrightnessPercent = 0
    private var activeVertical = 0 // 0 none, 1 volume, 2 brightness
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleDetector: ScaleGestureDetector

    private val hideControlsRunnable = Runnable {
        if (player?.isPlaying == true) {
            setControlsVisible(false)
        }
    }
    private val resetDoubleTapRunnable = Runnable {
        doubleTapSeekMs = 0L
        updateDoubleTapIndicator()
        updateInfo()
    }
    private val hideVerticalRunnable = Runnable {
        activeVertical = 0
        updateVerticalIndicators()
    }
    private val hideScaleIndicatorRunnable = Runnable {
        showScaleIndicator = false
        updateInfo()
    }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        paths = intent.extraPathList
        if (paths.isEmpty()) {
            finish()
            return
        }
        startIndex = intent.getIntExtra(EXTRA_POSITION, 0).coerceIn(0, paths.size - 1)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        buildUi()
        setContentView(root)
        toggleSystemBars(true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (overlayKind != null) {
                    hideOverlay()
                } else {
                    finish()
                }
            }
        })

        val exoPlayer = ExoPlayer.Builder(
            this,
            DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        exoPlayer.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        exoPlayer.addListener(playerListener)
        playerView.player = exoPlayer
        player = exoPlayer

        if (Build.VERSION.SDK_INT >= 26) {
            addOnPictureInPictureModeChangedListener { info ->
                isInPip = info.isInPictureInPictureMode
                updateVisibility()
                if (!isInPip) {
                    toggleSystemBars(controlsVisible && !controlsLocked)
                }
            }
        }

        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { paths.map { buildMediaItem(it) } }
            val p = player ?: return@launch
            val saved = prefs.getLong(items[startIndex].mediaId, 0L)
            p.setMediaItems(items, startIndex, if (saved > 5_000L) saved else 0L)
            p.prepare()
            p.playWhenReady = true
            updateTitle()
            updateButtons()
        }
    }

    private fun buildMediaItem(path: Path): MediaItem {
        val name = path.fileName?.toString() ?: ""
        val builder = MediaItem.Builder()
            .setUri(path.fileProviderUri)
            .setMediaId(path.toUri().toString())
        try {
            val base = name.substringBeforeLast('.', name)
            val subtitles = mutableListOf<MediaItem.SubtitleConfiguration>()
            for ((ext, mime) in SUBTITLE_TYPES) {
                val sibling = path.resolveSibling("$base.$ext")
                if (Files.exists(sibling)) {
                    subtitles.add(
                        MediaItem.SubtitleConfiguration.Builder(sibling.fileProviderUri)
                            .setMimeType(mime)
                            .setLabel(ext.uppercase(Locale.ROOT))
                            .setSelectionFlags(
                                if (subtitles.isEmpty()) C.SELECTION_FLAG_DEFAULT else 0
                            )
                            .build()
                    )
                }
            }
            if (subtitles.isNotEmpty()) {
                builder.setSubtitleConfigurations(subtitles)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return builder.build()
    }

    // region View helpers

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun medium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun label(
        sizeSp: Float,
        color: Int = Color.WHITE,
        typeface: Typeface? = null,
        text: String = ""
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (typeface != null) {
            this.typeface = typeface
        }
        includeFontPadding = false
    }

    private fun icon(res: Int, sizeDp: Int = 24, tint: Int = Color.WHITE): ImageView =
        ImageView(this).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER)
        }

    private fun pillOutline(): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(
                0, 0, view.width, view.height, min(view.width, view.height) / 2f
            )
        }
    }

    /** Equivalent of Next Player's PlayerButton. */
    private fun playerButton(
        content: View,
        paddingH: Int = 8,
        paddingV: Int = 8,
        container: Int = Color.TRANSPARENT,
        sizeDp: Int = 0,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): FrameLayout = FrameLayout(this).apply {
        addView(
            content,
            (content.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )).apply { gravity = Gravity.CENTER }
        )
        setPadding(dp(paddingH), dp(paddingV), dp(paddingH), dp(paddingV))
        background = RippleDrawable(
            ColorStateList.valueOf(0x80FFFFFF.toInt()), ColorDrawable(container), null
        )
        outlineProvider = pillOutline()
        clipToOutline = true
        isClickable = true
        setOnClickListener { onClick() }
        if (onLongClick != null) {
            setOnLongClickListener {
                onLongClick()
                true
            }
        }
        if (sizeDp > 0) {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    }

    private fun iconButton(res: Int, onClick: () -> Unit): FrameLayout =
        playerButton(icon(res), onClick = onClick)

    // endregion

    // region UI construction

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        root.addView(playerView, matchParent())

        gestureLayer = View(this)
        root.addView(gestureLayer, matchParent())
        setUpGestures(gestureLayer)

        // Controls (scrim + top + middle + bottom).
        controlsRoot = FrameLayout(this)
        root.addView(controlsRoot, matchParent())
        scrim = View(this).apply { setBackgroundColor(BLACK_30) }
        controlsRoot.addView(scrim, matchParent())

        buildTopBar()
        buildBottomBar()
        buildMiddleRow()

        // Info text (seek amount, zoom, content scale).
        infoText = label(28f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.1f)
            visibility = View.GONE
        }
        root.addView(
            infoText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        bufferingView = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(PRIMARY)
            visibility = View.GONE
        }
        root.addView(
            bufferingView, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)
        )

        // Gesture indicators.
        doubleTapView = DoubleTapView(this).apply { visibility = View.GONE }
        root.addView(
            doubleTapView,
            FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        longPressPill = label(14f, ON_SURFACE, medium(), "2.0x speed").apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(SURFACE)
                cornerRadius = dp(100).toFloat()
            }
            visibility = View.GONE
        }
        root.addView(
            longPressPill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(24) }
        )
        indicatorLayer = FrameLayout(this)
        volumeIndicator = VerticalProgressView(this, R.drawable.np_ic_volume).apply {
            visibility = View.GONE
        }
        brightnessIndicator = VerticalProgressView(this, R.drawable.np_ic_brightness).apply {
            visibility = View.GONE
        }
        indicatorLayer.addView(
            volumeIndicator,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(250), Gravity.CENTER_VERTICAL or Gravity.START
            )
        )
        indicatorLayer.addView(
            brightnessIndicator,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(250), Gravity.CENTER_VERTICAL or Gravity.END
            )
        )
        root.addView(indicatorLayer, matchParent())

        // Locked layer.
        lockedLayer = FrameLayout(this).apply { visibility = View.GONE }
        val unlock = playerButton(
            icon(R.drawable.np_ic_lock), container = BLACK_50, onClick = { unlockControls() }
        )
        lockedLayer.addView(
            unlock, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { topMargin = dp(24) }
        )
        root.addView(lockedLayer, matchParent())

        buildOverlay()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            insetL = bars.left
            insetT = bars.top
            insetR = bars.right
            insetB = bars.bottom
            val g = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars()
            )
            val sg = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
            gestL = max(g.left, sg.left)
            gestT = max(g.top, sg.top)
            gestR = max(g.right, sg.right)
            gestB = max(g.bottom, sg.bottom)
            applyInsets()
            insets
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun buildTopBar() {
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(iconButton(R.drawable.np_ic_arrow_left) { finish() })
        titleText = label(16f, Color.WHITE, medium()).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        topBar.addView(
            titleText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            }
        )
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val buttons = listOf(
            iconButton(R.drawable.np_ic_playlist) { showOverlay(OverlayKind.PLAYLIST) },
            iconButton(R.drawable.np_ic_audio_track) { showOverlay(OverlayKind.AUDIO) },
            iconButton(R.drawable.np_ic_subtitle_track) { showOverlay(OverlayKind.SUBTITLE) }
        )
        buttons.forEachIndexed { index, button ->
            group.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) marginStart = dp(8) }
            )
        }
        topBar.addView(group)
        controlsRoot.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
    }

    private fun buildMiddleRow() {
        middleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = playerButton(
            icon(R.drawable.np_ic_skip_prev, 28), sizeDp = 48
        ) {
            player?.seekToPreviousMediaItem()
            showControls()
        }
        playPauseIcon = icon(R.drawable.np_ic_play, 48)
        val playPause = playerButton(playPauseIcon, sizeDp = 64) { togglePlay() }
        val next = playerButton(
            icon(R.drawable.np_ic_skip_next, 28), sizeDp = 48
        ) {
            player?.seekToNextMediaItem()
            showControls()
        }
        middleRow.addView(prev)
        middleRow.addView(
            playPause, (playPause.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = dp(40)
                marginEnd = dp(40)
            }
        )
        middleRow.addView(next)
        controlsRoot.addView(
            middleRow,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
    }

    private fun buildBottomBar() {
        bottomBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Row 1: time, speed, rotate.
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        timeText = label(14f, Color.WHITE, medium(), "00:00 / 00:00")
        row1.addView(
            playerButton(
                timeText, paddingH = 8, paddingV = 1, container = BLACK_30,
                onClick = { toggleRemainingTime() }
            )
        )
        row1.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        speedText = label(14f, Color.WHITE, medium(), "1.0").apply { gravity = Gravity.CENTER }
        val speedBox = FrameLayout(this).apply {
            addView(speedText, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28))
        }
        row1.addView(
            playerButton(
                speedBox, paddingH = 4, paddingV = 4, container = BLACK_30,
                onClick = { showOverlay(OverlayKind.SPEED) }
            )
        )
        row1.addView(
            playerButton(
                icon(R.drawable.np_ic_screen_rotation, 20), container = BLACK_30,
                onClick = { toggleOrientation() }
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(16) }
        )
        bottomBar.addView(row1)

        // Seekbar.
        seekBar = PlayerSeekBar(this).apply {
            onSeek = { value -> onSeekBarSeek(value) }
            onSeekEnd = { endSeek() }
        }
        bottomBar.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)
            ).apply { topMargin = dp(4) }
        )

        // Row 2: lock, scale, pip, loop, shuffle.
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scaleIcon = icon(ContentScale.BEST_FIT.icon)
        loopIcon = icon(R.drawable.np_ic_loop_off)
        shuffleIcon = icon(R.drawable.np_ic_shuffle)
        val items = mutableListOf<View>()
        items.add(iconButton(R.drawable.np_ic_lock_open) {
            showControls()
            lockControls()
        })
        items.add(
            playerButton(
                scaleIcon,
                onClick = {
                    showControls()
                    onContentScaleChanged(
                        ContentScale.values()[(contentScale.ordinal + 1) % ContentScale.values().size]
                    )
                },
                onLongClick = { showOverlay(OverlayKind.SCALE) }
            )
        )
        if (isPipSupported()) {
            items.add(iconButton(R.drawable.np_ic_pip) { enterPip() })
        }
        items.add(playerButton(loopIcon, onClick = { cycleRepeatMode() }))
        items.add(playerButton(shuffleIcon, onClick = { toggleShuffle() }))
        items.forEachIndexed { index, view ->
            row2.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) marginStart = dp(8) }
            )
        }
        scroll.addView(
            row2,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        bottomBar.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )
        controlsRoot.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
    }

    private fun buildOverlay() {
        overlayRoot = FrameLayout(this).apply {
            visibility = View.GONE
            setOnClickListener { hideOverlay() }
        }
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(SURFACE)
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
        }
        sheetTitle = label(24f, ON_SURFACE).apply {
            setPadding(dp(24), dp(24), dp(24), dp(8))
        }
        sheet.addView(sheetTitle)
        val scroll = ScrollView(this)
        sheetContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), dp(24))
        }
        scroll.addView(
            sheetContent,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sheet.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        overlayRoot.addView(
            sheet,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(overlayRoot, matchParent())
    }

    private fun applyInsets() {
        val topExtra = if (insetT == 0) dp(16) else 0
        topBar.setPadding(
            insetL + dp(16), insetT + dp(0) + topExtra, insetR + dp(16), dp(16)
        )
        val bottomExtra = if (insetB == 0) dp(16) else 0
        bottomBar.setPadding(
            insetL + dp(16), dp(16), insetR + dp(16), insetB + bottomExtra
        )
        (lockedLayer.getChildAt(0).layoutParams as FrameLayout.LayoutParams).apply {
            marginStart = insetL
            topMargin = insetT + dp(24)
        }
        lockedLayer.getChildAt(0).requestLayout()
        indicatorLayer.setPadding(insetL + dp(24), dp(24), insetR + dp(24), dp(24))
    }

    // endregion

    // region Visibility and system bars

    private fun toggleSystemBars(show: Boolean) {
        WindowInsetsControllerCompat(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (show) {
                show(WindowInsetsCompat.Type.systemBars())
            } else {
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun updateVisibility() {
        val ui = !isInPip
        val showControlsUi = ui && controlsVisible && !controlsLocked && overlayKind == null
        scrim.visibility = if (showControlsUi) View.VISIBLE else View.GONE
        topBar.visibility = if (ui && controlsVisible && overlayKind == null && !controlsLocked) {
            View.VISIBLE
        } else {
            View.GONE
        }
        bottomBar.visibility = if (showControlsUi) View.VISIBLE else View.GONE
        middleRow.visibility =
            if (showControlsUi && infoText.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        controlsRoot.visibility = if (ui) View.VISIBLE else View.GONE
        lockedLayer.visibility =
            if (ui && controlsVisible && controlsLocked) View.VISIBLE else View.GONE
        indicatorLayer.visibility = if (ui) View.VISIBLE else View.GONE
        if (isInPip) {
            infoText.visibility = View.GONE
            bufferingView.visibility = View.GONE
            overlayRoot.visibility = View.GONE
            doubleTapView.visibility = View.GONE
            longPressPill.visibility = View.GONE
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        handler.removeCallbacks(hideControlsRunnable)
        if (visible) {
            scheduleHideControls()
        }
        toggleSystemBars(visible && !controlsLocked)
        updateVisibility()
        updateInfo()
    }

    private fun showControls() = setControlsVisible(true)

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 4000)
    }

    private fun lockControls() {
        controlsLocked = true
        toggleSystemBars(false)
        updateVisibility()
    }

    private fun unlockControls() {
        controlsLocked = false
        showControls()
    }

    // endregion

    // region Info / indicators

    private fun updateInfo() {
        val amount = seekAmount
        val text: String? = when {
            amount != null -> {
                val sign = if (amount < 0) "-" else "+"
                "$sign${formatTime(abs(amount))}\n[${formatTime(seekStartPosition + amount)}]"
            }
            isZooming -> "${(zoom * 100).toInt()}%"
            showScaleIndicator -> contentScale.label
            else -> null
        }
        if (text != null) {
            infoText.text = text
            infoText.visibility = View.VISIBLE
        } else {
            infoText.visibility = View.GONE
        }
        updateVisibility()
    }

    private fun updateDoubleTapIndicator() {
        if (doubleTapSeekMs == 0L) {
            doubleTapView.visibility = View.GONE
            return
        }
        val forward = doubleTapSeekMs > 0
        doubleTapView.forward = forward
        doubleTapView.setSeconds(doubleTapSeekMs / 1000)
        doubleTapView.layoutParams = (doubleTapView.layoutParams as FrameLayout.LayoutParams).apply {
            width = (root.width * 0.35f).toInt()
            gravity = if (forward) Gravity.END else Gravity.START
        }
        doubleTapView.visibility = View.VISIBLE
    }

    private fun updateVerticalIndicators() {
        volumeIndicator.visibility = if (activeVertical == 1) View.VISIBLE else View.GONE
        // Brightness is shown on the opposite side of the finger, like Next Player.
        brightnessIndicator.visibility = if (activeVertical == 2) View.VISIBLE else View.GONE
    }

    // endregion

    // region Gestures

    private fun setUpGestures(target: View) {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (doubleTapSeekMs != 0L) {
                    return true
                }
                if (controlsVisible) {
                    setControlsVisible(false)
                } else {
                    showControls()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (controlsLocked) {
                    return false
                }
                handleDoubleTap(e.x / target.width)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (controlsLocked || isInPip) {
                    return
                }
                val p = player ?: return
                if (!p.isPlaying) {
                    return
                }
                longPressActive = true
                speedBeforeLongPress = p.playbackParameters.speed
                p.setPlaybackSpeed(2f)
                longPressPill.text = String.format(Locale.ROOT, "%.1fx speed", 2f)
                longPressPill.visibility = View.VISIBLE
            }
        })
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (controlsLocked || isInPip || longPressActive) {
                        return false
                    }
                    lastFocusX = detector.focusX
                    lastFocusY = detector.focusY
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val p = player ?: return false
                    if (p.duration == C.TIME_UNSET) {
                        return false
                    }
                    isZooming = true
                    zoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val panX = detector.focusX - lastFocusX
                    val panY = detector.focusY - lastFocusY
                    lastFocusX = detector.focusX
                    lastFocusY = detector.focusY
                    val extraWidth = max(0f, (zoom - 1) * root.width)
                    val extraHeight = max(0f, (zoom - 1) * root.height)
                    offsetX = (offsetX + zoom * panX).coerceIn(-extraWidth / 2, extraWidth / 2)
                    offsetY = (offsetY + zoom * panY).coerceIn(-extraHeight / 2, extraHeight / 2)
                    applyTransform()
                    updateInfo()
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isZooming = false
                    updateInfo()
                }
            }
        )
        target.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragMode = DRAG_NONE
                    startAllowed = event.x >= gestL && event.x < target.width - gestR &&
                        event.y >= gestT && event.y < target.height - gestB
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!controlsLocked && !isInPip && event.pointerCount == 1 &&
                        !scaleDetector.isInProgress && !isZooming && !longPressActive &&
                        startAllowed
                    ) {
                        handleDrag(event, target)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> endDrag()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDrag()
                    if (longPressActive) {
                        longPressActive = false
                        player?.setPlaybackSpeed(speedBeforeLongPress)
                        longPressPill.visibility = View.GONE
                    }
                }
            }
            true
        }
    }

    private fun handleDoubleTap(fraction: Float) {
        val p = player ?: return
        if (!p.isCurrentMediaItemSeekable) {
            return
        }
        when {
            fraction < 0.35f -> {
                p.seekTo(max(0L, p.currentPosition - SEEK_INCREMENT_MS))
                if (doubleTapSeekMs > 0L) {
                    doubleTapSeekMs = 0L
                }
                doubleTapSeekMs -= SEEK_INCREMENT_MS
            }
            fraction > 0.65f -> {
                p.seekTo(p.currentPosition + SEEK_INCREMENT_MS)
                if (doubleTapSeekMs < 0L) {
                    doubleTapSeekMs = 0L
                }
                doubleTapSeekMs += SEEK_INCREMENT_MS
            }
            else -> togglePlay()
        }
        updateDoubleTapIndicator()
        handler.removeCallbacks(resetDoubleTapRunnable)
        handler.postDelayed(resetDoubleTapRunnable, 750)
    }

    private fun handleDrag(event: MotionEvent, target: View) {
        val p = player ?: return
        if (dragMode == DRAG_NONE) {
            val dx = event.x - downX
            val dy = event.y - downY
            if (abs(dx) < touchSlop && abs(dy) < touchSlop) {
                return
            }
            if (abs(dx) > abs(dy)) {
                if (p.currentPosition < 0 || p.duration <= 0 || !p.isCurrentMediaItemSeekable) {
                    return
                }
                dragMode = DRAG_SEEK
                isSeeking = true
                seekStartX = event.x
                seekStartPosition = p.currentPosition
            } else {
                dragMode = DRAG_VERTICAL
                startingY = event.y
                handler.removeCallbacks(hideVerticalRunnable)
                syncVolume()
                startVolumePercent = currentVolumePercent()
                startBrightnessPercent = currentBrightnessPercent()
                activeVertical = if (downX < target.width / 2f) 2 else 1
            }
        }
        when (dragMode) {
            DRAG_SEEK -> {
                val duration = p.duration
                if (duration <= 0) {
                    return
                }
                val newPosition = (seekStartPosition + ((event.x - seekStartX) * 50).toLong())
                    .coerceIn(0L, duration)
                seekAmount = newPosition - seekStartPosition
                p.seekTo(newPosition)
                updateInfo()
            }
            DRAG_VERTICAL -> {
                val change = ((startingY - event.y) * (0.5f / 10)).toInt()
                if (activeVertical == 1) {
                    val newVolume = (startVolumePercent + change).coerceIn(0, 100)
                    setVolumePercent(newVolume)
                    volumeIndicator.setValue(newVolume, 100)
                } else {
                    val newBrightness = (startBrightnessPercent + change).coerceIn(0, 100)
                    setBrightnessPercent(newBrightness)
                    brightnessIndicator.setValue(newBrightness, 100)
                }
                updateVerticalIndicators()
            }
        }
    }

    private fun endDrag() {
        when (dragMode) {
            DRAG_SEEK -> endSeek()
            DRAG_VERTICAL -> {
                handler.removeCallbacks(hideVerticalRunnable)
                handler.postDelayed(hideVerticalRunnable, 1000)
            }
        }
        dragMode = DRAG_NONE
    }

    private fun onSeekBarSeek(value: Long) {
        val p = player ?: return
        val duration = p.duration
        if (duration <= 0 || p.currentPosition < 0) {
            endSeek()
            return
        }
        if (!isSeeking) {
            isSeeking = true
            seekStartPosition = p.currentPosition
        }
        val position = value.coerceIn(0L, duration)
        seekAmount = position - seekStartPosition
        p.seekTo(position)
        updateInfo()
    }

    private fun endSeek() {
        isSeeking = false
        seekAmount = null
        updateInfo()
    }

    private fun syncVolume() {}

    private fun currentVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) (current.toFloat() / max * 100).toInt() else 0
    }

    private fun setVolumePercent(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, percent * max / 100, 0)
    }

    private fun currentBrightnessPercent(): Int {
        val value = window.attributes.screenBrightness
        val level = if (value >= 0f) {
            value
        } else {
            try {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            } catch (e: Exception) {
                0.5f
            }
        }
        return (level * 100).roundToInt()
    }

    private fun setBrightnessPercent(percent: Int) {
        window.attributes = window.attributes.apply {
            screenBrightness = (percent / 100f).coerceIn(0.01f, 1f)
        }
    }

    private fun applyTransform() {
        var base = 1f
        val vs = player?.videoSize
        if (contentScale == ContentScale.HUNDRED_PERCENT && vs != null && vs.width > 0 &&
            vs.height > 0 && root.width > 0 && root.height > 0
        ) {
            val fittedWidth = min(
                root.width.toFloat(), root.height.toFloat() * vs.width / vs.height
            )
            base = vs.width / fittedWidth
        }
        playerView.scaleX = base * zoom
        playerView.scaleY = base * zoom
        playerView.translationX = offsetX
        playerView.translationY = offsetY
    }

    private fun onContentScaleChanged(scale: ContentScale) {
        contentScale = scale
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        playerView.resizeMode = when (scale) {
            ContentScale.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            ContentScale.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        applyTransform()
        scaleIcon.setImageResource(scale.icon)
        showScaleIndicator = true
        handler.removeCallbacks(hideScaleIndicatorRunnable)
        handler.postDelayed(hideScaleIndicatorRunnable, 1000)
        updateInfo()
    }

    // endregion

    // region Playback helpers

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
            p.play()
        } else if (p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
    }

    private var showRemainingTime = false

    private fun toggleRemainingTime() {
        showRemainingTime = !showRemainingTime
        updateProgress()
    }

    private fun updateProgress() {
        val p = player ?: return
        val duration = if (p.duration == C.TIME_UNSET || p.duration < 0) 0L else p.duration
        val position = max(0L, p.currentPosition)
        seekBar.setProgress(position, duration)
        val whole = position / 1000 * 1000
        val positionText = if (showRemainingTime && duration > 0) {
            "-" + formatTime(max(0L, duration - whole))
        } else {
            formatTime(whole)
        }
        timeText.text = "$positionText / ${formatTime(duration)}"
    }

    private fun updateButtons() {
        val p = player ?: return
        playPauseIcon.setImageResource(
            if (p.isPlaying) R.drawable.np_ic_pause else R.drawable.np_ic_play
        )
        loopIcon.setImageResource(
            when (p.repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.np_ic_loop_one
                Player.REPEAT_MODE_ALL -> R.drawable.np_ic_loop_all
                else -> R.drawable.np_ic_loop_off
            }
        )
        shuffleIcon.setImageResource(
            if (p.shuffleModeEnabled) R.drawable.np_ic_shuffle_on else R.drawable.np_ic_shuffle
        )
        val speed = (p.playbackParameters.speed * 100).roundToInt() / 100f
        val speedString = speed.toString()
        speedText.text = speedString
        speedText.textSize = if (speedString.length > 3) 11f else 14f
        bufferingView.visibility =
            if (p.playbackState == Player.STATE_BUFFERING && !isInPip) View.VISIBLE else View.GONE
    }

    private fun updateTitle() {
        val p = player ?: return
        val index = p.currentMediaItemIndex.coerceIn(0, paths.size - 1)
        titleText.text = paths[index].fileName?.toString() ?: ""
    }

    private fun cycleRepeatMode() {
        val p = player ?: return
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        updateButtons()
    }

    private fun toggleShuffle() {
        val p = player ?: return
        p.shuffleModeEnabled = !p.shuffleModeEnabled
        updateButtons()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    private fun toggleOrientation() {
        orientationChosenByUser = true
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
    }

    private fun isPipSupported(): Boolean =
        Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPip() {
        if (!isPipSupported()) {
            Toast.makeText(this, "Picture-in-picture is not supported", Toast.LENGTH_SHORT).show()
            return
        }
        val size = player?.videoSize
        var width = size?.width ?: 0
        var height = size?.height ?: 0
        if (width <= 0 || height <= 0) {
            width = 16
            height = 9
        }
        val ratio = width.toFloat() / height
        if (ratio > 2.39f) {
            width = 239
            height = 100
        } else if (ratio < 0.42f) {
            width = 42
            height = 100
        }
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(width, height)).build()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && !controlsLocked) {
            enterPip()
        }
    }

    // endregion

    // region Overlays

    private fun showOverlay(kind: OverlayKind) {
        setControlsVisible(false)
        overlayKind = kind
        sheetContent.removeAllViews()
        when (kind) {
            OverlayKind.AUDIO -> {
                sheetTitle.text = "Select audio track"
                populateTracks(C.TRACK_TYPE_AUDIO)
            }
            OverlayKind.SUBTITLE -> {
                sheetTitle.text = "Select subtitle track"
                populateTracks(C.TRACK_TYPE_TEXT)
            }
            OverlayKind.SPEED -> {
                sheetTitle.text = "Select playback speed"
                populateSpeed()
            }
            OverlayKind.SCALE -> {
                sheetTitle.text = "Video zoom"
                for (scale in ContentScale.values()) {
                    sheetContent.addView(
                        radioRow(scale.label, scale == contentScale) {
                            onContentScaleChanged(scale)
                            hideOverlay()
                        }
                    )
                }
            }
            OverlayKind.PLAYLIST -> {
                sheetTitle.text = "Now playing"
                populatePlaylist()
            }
        }
        val portrait =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val lp = sheet.layoutParams as FrameLayout.LayoutParams
        if (portrait) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = (root.height * 0.45f).toInt()
            lp.gravity = Gravity.BOTTOM
        } else {
            lp.width = (root.width * 0.45f).toInt()
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.END
        }
        sheet.layoutParams = lp
        sheetContent.setPadding(dp(24), 0, dp(24) + (if (portrait) 0 else insetR), dp(24))
        overlayRoot.visibility = View.VISIBLE
        sheet.post {
            if (portrait) {
                sheet.translationX = 0f
                sheet.translationY = sheet.height.toFloat()
                sheet.animate().translationY(0f).setDuration(250).start()
            } else {
                sheet.translationY = 0f
                sheet.translationX = sheet.width.toFloat()
                sheet.animate().translationX(0f).setDuration(250).start()
            }
        }
        updateVisibility()
    }

    private fun hideOverlay() {
        if (overlayKind == null) {
            return
        }
        overlayKind = null
        val portrait =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val animator = if (portrait) {
            sheet.animate().translationY(sheet.height.toFloat())
        } else {
            sheet.animate().translationX(sheet.width.toFloat())
        }
        animator.setDuration(200).withEndAction {
            if (overlayKind == null) {
                overlayRoot.visibility = View.GONE
            }
        }.start()
        updateVisibility()
    }

    private fun radioRow(text: String, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE)
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
        val radio = RadioButton(this).apply {
            isChecked = selected
            isClickable = false
            isFocusable = false
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(PRIMARY, ON_SURFACE_VARIANT)
            )
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
        }
        row.addView(radio, LinearLayout.LayoutParams(dp(48), dp(48)))
        val textView = label(14f, ON_SURFACE, text = text).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(
            textView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        )
        return row
    }

    private fun trackLabel(group: androidx.media3.common.Tracks.Group, i: Int, n: Int, audio: Boolean): String {
        val format = group.getTrackFormat(i)
        val language = format.language
            ?.takeIf { it != C.LANGUAGE_UNDETERMINED }
            ?.let { Locale(it).displayLanguage }
        var label = format.label ?: language ?: (if (audio) "Audio track #$n" else "Subtitle track #$n")
        if (audio && format.channelCount > 0) {
            label += " (${format.channelCount}ch)"
        }
        return label
    }

    private fun populateTracks(type: Int) {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == type }
        val disabled = p.trackSelectionParameters.disabledTrackTypes.contains(type)
        var counter = 1
        var anySelected = false
        for (group in groups) {
            for (i in 0 until group.length) {
                val selected = group.isTrackSelected(i) && !disabled
                if (selected) {
                    anySelected = true
                }
                val override = TrackSelectionOverride(group.mediaTrackGroup, i)
                sheetContent.addView(
                    radioRow(trackLabel(group, i, counter, type == C.TRACK_TYPE_AUDIO), selected) {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(type, false)
                            .setOverrideForType(override)
                            .build()
                        hideOverlay()
                    }
                )
                counter++
            }
        }
        sheetContent.addView(
            radioRow("Disable", !anySelected) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, true)
                    .build()
                hideOverlay()
            }
        )
    }

    private fun populatePlaylist() {
        val p = player ?: return
        for (index in 0 until p.mediaItemCount) {
            val current = index == p.currentMediaItemIndex
            val name = paths.getOrNull(index)?.fileName?.toString() ?: ""
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF),
                    if (current) GradientDrawable().apply {
                        setColor((PRIMARY_CONTAINER and 0x00FFFFFF) or 0x4D000000)
                        cornerRadius = dp(12).toFloat()
                    } else null,
                    ColorDrawable(Color.WHITE)
                )
                isClickable = true
                setOnClickListener {
                    p.seekTo(index, 0L)
                    hideOverlay()
                    showControls()
                }
            }
            row.addView(
                icon(R.drawable.np_ic_drag_handle, 24, if (current) PRIMARY else ON_SURFACE_VARIANT)
            )
            row.addView(
                label(14f, if (current) PRIMARY else ON_SURFACE, text = name).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                }
            )
            sheetContent.addView(row)
        }
    }

    private fun tonalButton(res: Int, onClick: () -> Unit): View =
        FrameLayout(this).apply {
            addView(icon(res, 24, ON_SECONDARY_CONTAINER))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(SECONDARY_CONTAINER)
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }

    private fun populateSpeed() {
        val p = player ?: return
        val minValue = 0.25f
        val maxValue = 8.0f
        val step = 0.05f
        val valueText = label(16f, ON_SURFACE, medium()).apply { gravity = Gravity.CENTER }
        lateinit var slider: SeekBar
        var updating = false
        fun setSpeed(speed: Float) {
            val s = ((speed.coerceIn(minValue, maxValue)) * 100).roundToInt() / 100f
            p.setPlaybackSpeed(s)
            valueText.text = String.format(Locale.ROOT, "%.2f", s)
            updating = true
            slider.progress = ((s - minValue) / step).roundToInt()
            updating = false
            updateButtons()
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        slider = SeekBar(this).apply {
            max = ((maxValue - minValue) / step).roundToInt()
            progressTintList = ColorStateList.valueOf(PRIMARY)
            progressBackgroundTintList = ColorStateList.valueOf(SURFACE_VARIANT)
            thumbTintList = ColorStateList.valueOf(PRIMARY)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!updating && fromUser) {
                        setSpeed(minValue + progress * step)
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        topRow.addView(tonalButton(R.drawable.np_ic_remove) {
            setSpeed(p.playbackParameters.speed - step)
        })
        topRow.addView(valueText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(tonalButton(R.drawable.np_ic_add) {
            setSpeed(p.playbackParameters.speed + step)
        })
        sheetContent.addView(topRow)

        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sliderRow.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sliderRow.addView(
            FrameLayout(this).apply {
                addView(icon(R.drawable.np_ic_reset, 24, ON_SURFACE))
                isClickable = true
                setOnClickListener { setSpeed(1f) }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            }
        )
        sheetContent.addView(
            sliderRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        val speeds = listOf(
            0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f
        )
        for (rowStart in speeds.indices step 5) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in rowStart until min(rowStart + 5, speeds.size)) {
                val speed = speeds[i]
                val chip = label(14f, PRIMARY, medium(), "${speed}x").apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = RippleDrawable(
                        ColorStateList.valueOf(0x33FFFFFF),
                        GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                            setStroke(dp(1), ON_SURFACE)
                            cornerRadius = dp(100).toFloat()
                        },
                        null
                    )
                    isClickable = true
                    setOnClickListener { setSpeed(speed) }
                }
                row.addView(
                    chip,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > rowStart) marginStart = dp(8)
                    }
                )
            }
            sheetContent.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
        }

        val silenceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        silenceRow.addView(
            label(16f, ON_SURFACE, text = "Skip silence"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val switch = SwitchCompat(this).apply {
            isChecked = p.skipSilenceEnabled
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF003735.toInt(), 0xFF899392.toInt())
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(PRIMARY, SURFACE_VARIANT)
            )
            setOnCheckedChangeListener { _, checked -> p.skipSilenceEnabled = checked }
        }
        silenceRow.addView(switch)
        sheetContent.addView(
            silenceRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        setSpeed(p.playbackParameters.speed)
    }

    // endregion

    private fun savePosition() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val duration = if (p.duration == C.TIME_UNSET || p.duration < 0) 0L else p.duration
        val position = p.currentPosition
        if (p.playbackState == Player.STATE_ENDED ||
            (duration > 0 && position > duration - 10_000L) || position < 5_000L
        ) {
            prefs.edit().remove(item.mediaId).apply()
        } else {
            prefs.edit().putLong(item.mediaId, position).apply()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateButtons()
            if (isPlaying) {
                scheduleHideControls()
            } else {
                handler.removeCallbacks(hideControlsRunnable)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateButtons()
            if (playbackState == Player.STATE_ENDED) {
                setControlsVisible(true)
                handler.removeCallbacks(hideControlsRunnable)
            }
        }

        override fun onPlaybackParametersChanged(
            playbackParameters: androidx.media3.common.PlaybackParameters
        ) {
            updateButtons()
        }

        override fun onRepeatModeChanged(repeatMode: Int) = updateButtons()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateButtons()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateTitle()
            updateProgress()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                oldPosition.mediaItem?.mediaId?.let { prefs.edit().remove(it).apply() }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            applyTransform()
            if (autoOrientationDone || orientationChosenByUser ||
                videoSize.width <= 0 || videoSize.height <= 0
            ) {
                return
            }
            autoOrientationDone = true
            if (videoSize.width > videoSize.height && !isInPip) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@VideoPlayerActivity,
                "Cannot play this video: ${error.errorCodeName}",
                Toast.LENGTH_LONG
            ).show()
            setControlsVisible(true)
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        savePosition()
        val inPip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode
        if (!inPip) {
            player?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
    }

    companion object {
        private val EXTRA_POSITION = "${VideoPlayerActivity::class.java.name}.extra.POSITION"
        private const val PREFS_NAME = "video_player_positions"

        private const val DRAG_NONE = 0
        private const val DRAG_SEEK = 1
        private const val DRAG_VERTICAL = 2

        private const val SEEK_INCREMENT_MS = 10_000L
        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 4f

        private val SUBTITLE_TYPES = listOf(
            "srt" to MimeTypes.APPLICATION_SUBRIP,
            "vtt" to MimeTypes.TEXT_VTT,
            "ass" to MimeTypes.TEXT_SSA,
            "ssa" to MimeTypes.TEXT_SSA
        )

        fun putExtras(intent: Intent, paths: List<Path>, position: Int) {
            // All extras put here must be framework classes, or we may crash the resolver.
            intent.extraPathList = paths
            intent.putExtra(EXTRA_POSITION, position)
        }
    }
}

/** Next Player's SimpleSlider: 4dp track, white 50% inactive, primary active, 16dp white thumb. */
private class PlayerSeekBar(context: Context) : View(context) {
    var onSeek: ((Long) -> Unit)? = null
    var onSeekEnd: (() -> Unit)? = null
    private var position = 0L
    private var duration = 0L
    private var scrubbing = false
    private val density = resources.displayMetrics.density
    private val inactive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt() }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRIMARY }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(4 * density, 0f, 2 * density, 0x66000000)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setProgress(position: Long, duration: Long) {
        if (scrubbing) {
            return
        }
        this.position = position
        this.duration = duration
        invalidate()
    }

    private fun radius() = 8 * density

    override fun onDraw(canvas: Canvas) {
        val r = radius()
        val cy = height / 2f
        val half = 2 * density
        val corner = 4 * density
        val track = RectF(0f, cy - half, width.toFloat(), cy + half)
        canvas.drawRoundRect(track, corner, corner, inactive)
        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val thumbX = r + fraction * (width - 2 * r)
        val activeRect = RectF(0f, cy - half, thumbX, cy + half)
        canvas.drawRoundRect(activeRect, corner, corner, active)
        canvas.drawCircle(thumbX, cy, r, thumb)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (duration <= 0) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true
                val r = radius()
                val fraction = ((event.x - r) / (width - 2 * r)).coerceIn(0f, 1f)
                position = (fraction * duration).toLong()
                invalidate()
                onSeek?.invoke(position)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scrubbing = false
                onSeekEnd?.invoke()
            }
        }
        return true
    }
}

/** Next Player's DoubleTapIndicator: translucent half-oval with animated triangles. */
private class DoubleTapView(context: Context) : LinearLayout(context) {
    var forward = true
        set(value) {
            field = value
            row.rotation = if (value) 0f else 180f
            invalidate()
        }
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val path = AndroidPath()
    private val arrows = List(3) {
        ImageView(context).apply {
            setImageResource(R.drawable.np_ic_play)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            alpha = 0f
        }
    }
    private val row = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val labelView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }
    private val animator = ValueAnimator.ofFloat(0f, 6f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val t = it.animatedValue as Float
            val segment = min(5, t.toInt())
            val frac = t - segment
            for (i in 0 until 3) {
                arrows[i].alpha = when {
                    segment < 3 -> if (i < segment) 1f else if (i == segment) frac else 0f
                    else -> if (i < segment - 3) 0f else if (i == segment - 3) 1f - frac else 1f
                }
            }
        }
    }

    init {
        setWillNotDraw(false)
        orientation = VERTICAL
        gravity = Gravity.CENTER
        arrows.forEach { row.addView(it, LayoutParams((20 * density).toInt(), (20 * density).toInt())) }
        addView(row)
        addView(labelView)
    }

    fun setSeconds(seconds: Long) {
        labelView.text = "$seconds seconds"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset()
        if (forward) {
            path.moveTo(w, h)
            path.lineTo(w, 0f)
            path.lineTo(w * 0.1f, 0f)
            path.cubicTo(w * 0.1f, 0f, -w * 0.1f, h / 2, w * 0.1f, h)
        } else {
            path.moveTo(0f, 0f)
            path.lineTo(w * 0.9f, 0f)
            path.cubicTo(w * 0.9f, 0f, w * 1.1f, h / 2, w * 0.9f, h)
            path.lineTo(0f, h)
        }
        path.close()
        canvas.drawPath(path, fill)
        super.onDraw(canvas)
    }
}

/** Next Player's VerticalProgressView used for volume and brightness. */
private class VerticalProgressView(context: Context, iconRes: Int) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val valueText = TextView(context)
    private val bar = BarView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = (8 * density).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(SURFACE)
            cornerRadius = 100 * density
        }
        val box = (32 * density).toInt()
        valueText.apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(ON_SURFACE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }
        addView(valueText, LayoutParams(box, box))
        addView(bar, LayoutParams(box, 0, 1f).apply {
            topMargin = (4 * density).toInt()
            bottomMargin = (4 * density).toInt()
        })
        val iconBox = FrameLayout(context)
        iconBox.addView(
            ImageView(context).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(ON_SURFACE)
            },
            FrameLayout.LayoutParams(
                (24 * density).toInt(), (24 * density).toInt(), Gravity.CENTER
            )
        )
        addView(iconBox, LayoutParams(box, box))
    }

    fun setValue(value: Int, maxValue: Int) {
        val normalized = value.coerceIn(0, maxValue)
        valueText.text = normalized.toString()
        bar.fraction = normalized.toFloat() / maxValue
        bar.boost = maxValue > 100 && value > 100
    }

    private class BarView(context: Context) : View(context) {
        var fraction = 0f
            set(value) {
                field = value
                invalidate()
            }
        var boost = false
        private val density = resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE_VARIANT }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = AndroidPath()

        override fun onDraw(canvas: Canvas) {
            val radius = 12 * density
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            clip.reset()
            clip.addRoundRect(rect, radius, radius, AndroidPath.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(rect, bg)
            fg.color = if (boost) BOOST_COLOR else PRIMARY
            canvas.drawRect(0f, height * (1f - fraction), width.toFloat(), height.toFloat(), fg)
            canvas.restore()
        }
    }
}
