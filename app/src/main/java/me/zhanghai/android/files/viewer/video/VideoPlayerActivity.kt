/*
 * Built-in video player (fork patch), Media3 / ExoPlayer, MX Player style gestures.
 */

package me.zhanghai.android.files.viewer.video

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.util.extraPathList
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class VideoPlayerActivity : AppActivity() {
    private lateinit var paths: List<Path>
    private var startIndex = 0

    private var player: ExoPlayer? = null
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var controls: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playButton: TextView
    private lateinit var speedButton: TextView
    private lateinit var overlayText: TextView
    private lateinit var unlockButton: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var locked = false
    private var isUserSeeking = false
    private var isInPip = false
    private var orientationChosenByUser = false
    private var autoOrientationDone = false

    private var zoom = 1f
    private var resizeIndex = 0

    // Gesture state.
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleDetector: ScaleGestureDetector
    private var downX = 0f
    private var downY = 0f
    private var mode = MODE_NONE
    private var seekStartPosition = 0L
    private var seekTarget = 0L
    private var brightness = 0.5f
    private var volumeFloat = 0f
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val hideUnlockRunnable = Runnable { unlockButton.visibility = View.GONE }
    private val hideOverlayRunnable = Runnable { overlayText.visibility = View.GONE }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
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
        hideSystemBars()

        val currentBrightness = window.attributes.screenBrightness
        brightness = if (currentBrightness >= 0f) {
            currentBrightness
        } else {
            try {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            } catch (e: Exception) {
                0.5f
            }
        }

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
                controls.visibility = if (isInPip) View.GONE else View.VISIBLE
                unlockButton.visibility = View.GONE
                if (!isInPip) {
                    hideSystemBars()
                }
            }
        }

        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { paths.map { buildMediaItem(it) } }
            val p = player ?: return@launch
            val startItem = items[startIndex]
            val saved = prefs.getLong(startItem.mediaId, 0L)
            p.setMediaItems(items, startIndex, if (saved > 5_000L) saved else 0L)
            p.prepare()
            p.playWhenReady = true
            updateTitle()
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

    // region UI

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun button(label: String, sizeSp: Float = 18f, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = sizeSp
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(44)
            setPadding(dp(6), 0, dp(6), 0)
            val value = TypedValue()
            this@VideoPlayerActivity.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, value, true
            )
            setBackgroundResource(value.resourceId)
            setOnClickListener {
                onClick()
                scheduleHideControls()
            }
        }

    private fun gradient(top: Boolean): GradientDrawable =
        GradientDrawable(
            if (top) GradientDrawable.Orientation.TOP_BOTTOM
            else GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xB0000000.toInt(), 0x00000000)
        )

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Gesture layer.
        val gestureLayer = View(this)
        root.addView(
            gestureLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setUpGestures(gestureLayer)

        controls = FrameLayout(this)
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Top bar.
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradient(true)
            setPadding(dp(4), dp(4), dp(4), dp(16))
        }
        topBar.addView(button("←", 22f) { finish() })
        titleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        topBar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(button("CC", 14f) { showTrackDialog(C.TRACK_TYPE_TEXT, "Subtitle") })
        topBar.addView(button("♪", 20f) { showTrackDialog(C.TRACK_TYPE_AUDIO, "Audio track") })
        controls.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        // Bottom bar.
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradient(false)
            setPadding(dp(8), dp(24), dp(8), dp(4))
        }
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        positionText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = formatTime(0)
        }
        durationText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = formatTime(0)
        }
        seekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = knownDuration()
                        positionText.text = formatTime(duration * progress / 1000)
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    isUserSeeking = true
                    handler.removeCallbacks(hideControlsRunnable)
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    isUserSeeking = false
                    val duration = knownDuration()
                    player?.seekTo(duration * bar.progress / 1000)
                    scheduleHideControls()
                }
            })
        }
        seekRow.addView(positionText)
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        seekRow.addView(durationText)
        bottomBar.addView(seekRow)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(button("🔒", 18f) { lockScreen() })
        speedButton = button("1x", 14f) { showSpeedDialog() }
        buttonRow.addView(speedButton)
        buttonRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        buttonRow.addView(button("⏮", 20f) { player?.seekToPreviousMediaItem() })
        playButton = button("▶", 26f) { togglePlay() }
        buttonRow.addView(playButton)
        buttonRow.addView(button("⏭", 20f) { player?.seekToNextMediaItem() })
        buttonRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        buttonRow.addView(button("⤢", 20f) { cycleResizeMode() })
        buttonRow.addView(button("⟳", 20f) { toggleOrientation() })
        buttonRow.addView(button("PiP", 12f) { enterPip() })
        bottomBar.addView(buttonRow)
        controls.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        // Center feedback text.
        overlayText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                cornerRadius = dp(16).toFloat()
            }
            visibility = View.GONE
        }
        root.addView(
            overlayText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        // Unlock button, shown only while locked.
        unlockButton = button("🔓", 22f) { unlockScreen() }.apply {
            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                shape = GradientDrawable.OVAL
            }
            visibility = View.GONE
        }
        root.addView(
            unlockButton,
            FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER_VERTICAL or Gravity.START)
                .apply { leftMargin = dp(24) }
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            controls.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isInPip) {
            hideSystemBars()
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        if (!locked) {
            controls.visibility = if (visible && !isInPip) View.VISIBLE else View.GONE
        }
        handler.removeCallbacks(hideControlsRunnable)
        if (visible) {
            scheduleHideControls()
        }
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (player?.isPlaying == true && !locked) {
            handler.postDelayed(hideControlsRunnable, 3500)
        }
    }

    private fun showOverlay(text: String) {
        overlayText.text = text
        overlayText.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, 800)
    }

    private fun lockScreen() {
        locked = true
        controls.visibility = View.GONE
        unlockButton.visibility = View.VISIBLE
        handler.removeCallbacks(hideControlsRunnable)
        handler.removeCallbacks(hideUnlockRunnable)
        handler.postDelayed(hideUnlockRunnable, 2500)
    }

    private fun unlockScreen() {
        locked = false
        unlockButton.visibility = View.GONE
        setControlsVisible(true)
    }

    // endregion

    // region Gestures

    private fun setUpGestures(target: View) {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    unlockButton.visibility = View.VISIBLE
                    handler.removeCallbacks(hideUnlockRunnable)
                    handler.postDelayed(hideUnlockRunnable, 2500)
                } else {
                    setControlsVisible(!controlsVisible)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) {
                    return false
                }
                val width = target.width.toFloat()
                when {
                    e.x < width / 3f -> {
                        seekBy(-10_000)
                        showOverlay("-10s")
                    }
                    e.x > width * 2f / 3f -> {
                        seekBy(10_000)
                        showOverlay("+10s")
                    }
                    else -> togglePlay()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (locked || scaleDetector.isInProgress) {
                    return false
                }
                val totalX = e2.x - downX
                val totalY = e2.y - downY
                if (mode == MODE_NONE) {
                    if (abs(totalX) < touchSlop && abs(totalY) < touchSlop) {
                        return false
                    }
                    if (abs(totalX) > abs(totalY)) {
                        mode = MODE_SEEK
                        seekStartPosition = player?.currentPosition ?: 0L
                    } else if (downX < target.width / 2f) {
                        mode = MODE_BRIGHTNESS
                    } else {
                        mode = MODE_VOLUME
                        volumeFloat = audioManager
                            .getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                    }
                }
                when (mode) {
                    MODE_SEEK -> {
                        val duration = knownDuration()
                        val delta = (totalX / target.width * 120_000f).toLong()
                        val upper = if (duration > 0) duration else Long.MAX_VALUE
                        seekTarget = (seekStartPosition + delta).coerceIn(0L, upper)
                        val diffSeconds = (seekTarget - seekStartPosition) / 1000
                        val sign = if (diffSeconds >= 0) "+" else "-"
                        showOverlay(
                            "$sign${formatTime(abs(diffSeconds) * 1000)}\n${formatTime(seekTarget)}"
                        )
                    }
                    MODE_BRIGHTNESS -> {
                        brightness = (brightness + distanceY / target.height * 1.3f)
                            .coerceIn(0.01f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = brightness }
                        showOverlay("☀ ${(brightness * 100).roundToInt()}%")
                    }
                    MODE_VOLUME -> {
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        volumeFloat = (volumeFloat + distanceY / target.height * max * 1.3f)
                            .coerceIn(0f, max.toFloat())
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC, volumeFloat.roundToInt(), 0
                        )
                        showOverlay("🔊 ${volumeFloat.roundToInt()}/$max")
                    }
                }
                return true
            }
        })
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (locked) {
                        return false
                    }
                    zoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
                    playerView.scaleX = zoom
                    playerView.scaleY = zoom
                    showOverlay("${(zoom * 100).roundToInt()}%")
                    return true
                }
            }
        )
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    mode = MODE_NONE
                }
            }
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == MODE_SEEK && event.actionMasked == MotionEvent.ACTION_UP) {
                        player?.seekTo(seekTarget)
                    }
                    mode = MODE_NONE
                }
            }
            true
        }
    }

    // endregion

    // region Playback helpers

    private fun knownDuration(): Long {
        val duration = player?.duration ?: C.TIME_UNSET
        return if (duration == C.TIME_UNSET || duration < 0) 0L else duration
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val duration = knownDuration()
        val upper = if (duration > 0) duration else Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, upper))
    }

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

    private fun updateProgress() {
        val p = player ?: return
        playButton.text = if (p.isPlaying) "⏸" else "▶"
        if (isUserSeeking) {
            return
        }
        val duration = knownDuration()
        val position = p.currentPosition
        positionText.text = formatTime(position)
        durationText.text = formatTime(duration)
        seekBar.progress = if (duration > 0) (position * 1000 / duration).toInt() else 0
        seekBar.secondaryProgress =
            if (duration > 0) (p.bufferedPosition * 1000 / duration).toInt() else 0
    }

    private fun updateTitle() {
        val p = player ?: return
        val index = p.currentMediaItemIndex.coerceIn(0, paths.size - 1)
        titleText.text = paths[index].fileName?.toString() ?: ""
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

    private fun cycleResizeMode() {
        resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
        playerView.resizeMode = RESIZE_MODES[resizeIndex]
        zoom = 1f
        playerView.scaleX = 1f
        playerView.scaleY = 1f
        showOverlay(RESIZE_LABELS[resizeIndex])
    }

    private fun toggleOrientation() {
        orientationChosenByUser = true
        requestedOrientation =
            if (resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
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
        if (player?.isPlaying == true && !locked) {
            enterPip()
        }
    }

    private fun showSpeedDialog() {
        val p = player ?: return
        val labels = SPEEDS.map { "${it}x" }.toTypedArray()
        val checked = SPEEDS.indexOfFirst { abs(it - p.playbackParameters.speed) < 0.01f }
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                p.setPlaybackSpeed(SPEEDS[which])
                speedButton.text = "${SPEEDS[which]}x"
                dialog.dismiss()
            }
            .show()
    }

    private fun showTrackDialog(type: Int, title: String) {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == type }
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        var checked = -1
        val isText = type == C.TRACK_TYPE_TEXT
        if (isText) {
            labels.add("Off")
            val textDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(type)
            if (textDisabled || groups.none { it.isSelected }) {
                checked = 0
            }
            actions.add {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, true)
                    .build()
            }
        }
        var counter = 1
        for (group in groups) {
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val language = format.language
                    ?.takeIf { it != C.LANGUAGE_UNDETERMINED }
                    ?.let { Locale(it).displayLanguage }
                var label = format.label ?: language ?: "Track $counter"
                if (!isText && format.channelCount > 0) {
                    label += " (${format.channelCount}ch)"
                }
                if (!group.isTrackSupported(i)) {
                    label += " (unsupported)"
                }
                counter++
                if (group.isTrackSelected(i) && checked < 0) {
                    checked = labels.size
                } else if (group.isTrackSelected(i) && isText &&
                    !p.trackSelectionParameters.disabledTrackTypes.contains(type)
                ) {
                    checked = labels.size
                }
                labels.add(label)
                val override = TrackSelectionOverride(group.mediaTrackGroup, i)
                actions.add {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(override)
                        .build()
                }
            }
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, "No $title available", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                actions[which]()
                dialog.dismiss()
            }
            .show()
    }

    private fun savePosition() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val duration = knownDuration()
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
            updateProgress()
            if (isPlaying) {
                scheduleHideControls()
            } else {
                handler.removeCallbacks(hideControlsRunnable)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateProgress()
            if (playbackState == Player.STATE_ENDED) {
                setControlsVisible(true)
            }
        }

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

    // endregion

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

        private const val MODE_NONE = 0
        private const val MODE_SEEK = 1
        private const val MODE_BRIGHTNESS = 2
        private const val MODE_VOLUME = 3

        private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

        private val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        private val RESIZE_LABELS = arrayOf("Fit", "Stretch", "Crop")

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
