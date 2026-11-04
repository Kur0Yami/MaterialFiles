/*
 * "Video" standard directory: videos from MediaStore, grouped by folder.
 * Tap a folder to list its videos (compact / grid / gallery); tapping a video plays that
 * folder as a playlist.
 */

package me.zhanghai.android.files.videolibrary

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import coil.dispose
import coil.load
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.viewer.video.VideoPlayerActivity
import kotlin.math.max
import kotlin.math.min

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// Same cap as the image viewer, to avoid TransactionTooLargeException.
private const val PLAYLIST_SIZE_MAX = 1000

private const val PREFS_NAME = "video_library"
private const val PREF_VIEW_MODE = "view_mode"
private const val MENU_GROUP_VIEW_MODE = 1

private enum class VideoViewMode(@StringRes val titleRes: Int, val columns: Int) {
    COMPACT(R.string.video_view_compact, 1),
    GRID(R.string.video_view_grid, 2),
    GALLERY(R.string.video_view_gallery, 3)
}

@Suppress("DEPRECATION")
class VideoLibraryActivity : AppActivity() {
    private lateinit var toolbar: Toolbar
    private lateinit var folderGrid: GridView
    private lateinit var videoGrid: GridView
    private lateinit var emptyText: TextView

    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private var viewMode = VideoViewMode.COMPACT

    private var videosByFolder: Map<String, List<VideoEntry>> = emptyMap()
    private var folders: List<FolderEntry> = emptyList()
    // Videos of the folder currently open; also the playlist for playback.
    private var folderVideos: List<VideoEntry> = emptyList()
    private var currentFolder: FolderEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewMode = prefs.getString(PREF_VIEW_MODE, null)
            ?.let { name -> VideoViewMode.entries.find { it.name == name } }
            ?: VideoViewMode.COMPACT

        toolbar = Toolbar(this).apply {
            setBackgroundColor(getColorByAttr(R.attr.colorAppBarSurface))
        }
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        folderGrid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(120)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
            setOnItemClickListener { _, _, position, _ -> showFolder(folders[position]) }
        }
        videoGrid = GridView(this).apply {
            numColumns = viewMode.columns
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
            setOnItemClickListener { _, _, position, _ -> openPlayer(position) }
        }
        emptyText = TextView(this).apply {
            text = getString(R.string.video_library_empty)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        val content = FrameLayout(this).apply {
            addView(folderGrid, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(videoGrid, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emptyText, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentFolder != null) {
                    showFolders()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) { queryVideos() }
            videosByFolder = videos.groupBy { it.folder }
            folders = buildFolders(videosByFolder)
            folderGrid.adapter = FolderAdapter(this@VideoLibraryActivity, folders)
            showFolders()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        for (mode in VideoViewMode.entries) {
            menu.add(MENU_GROUP_VIEW_MODE, mode.ordinal, Menu.NONE, mode.titleRes)
        }
        menu.setGroupCheckable(MENU_GROUP_VIEW_MODE, true, true)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // View modes only apply inside a folder.
        menu.setGroupVisible(MENU_GROUP_VIEW_MODE, currentFolder != null)
        menu.findItem(viewMode.ordinal)?.isChecked = true
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val mode = VideoViewMode.entries.getOrNull(item.itemId)
            ?: return super.onOptionsItemSelected(item)
        setViewMode(mode)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        if (currentFolder != null) {
            showFolders()
            return true
        }
        return super.onSupportNavigateUp()
    }

    private fun setViewMode(mode: VideoViewMode) {
        viewMode = mode
        prefs.edit().putString(PREF_VIEW_MODE, mode.name).apply()
        videoGrid.numColumns = mode.columns
        if (currentFolder != null) {
            videoGrid.adapter = VideoAdapter(this, folderVideos, mode)
        }
        invalidateOptionsMenu()
    }

    private fun showFolders() {
        currentFolder = null
        toolbar.title = getString(R.string.navigation_standard_directory_video)
        folderGrid.visibility = View.VISIBLE
        videoGrid.visibility = View.GONE
        emptyText.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
    }

    private fun showFolder(folder: FolderEntry) {
        currentFolder = folder
        toolbar.title = folder.name
        folderVideos = videosByFolder[folder.path].orEmpty()
        videoGrid.numColumns = viewMode.columns
        videoGrid.adapter = VideoAdapter(this, folderVideos, viewMode)
        folderGrid.visibility = View.GONE
        videoGrid.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        invalidateOptionsMenu()
    }

    private fun queryVideos(): List<VideoEntry> {
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION
        )
        val result = mutableListOf<VideoEntry>()
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATA} ASC"
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (cursor.moveToNext()) {
                val data = cursor.getString(dataColumn) ?: continue
                val path = runCatching { Paths.get(data) }.getOrNull() ?: continue
                // Needed by Coil to load the video thumbnail, like the file list does.
                val attributes = runCatching {
                    Files.readAttributes(path, BasicFileAttributes::class.java)
                }.getOrNull()
                result += VideoEntry(
                    path,
                    attributes,
                    cursor.getString(nameColumn) ?: data.substringAfterLast('/'),
                    data.substringBeforeLast('/'),
                    cursor.getLong(durationColumn)
                )
            }
        }
        return result
    }

    private fun openPlayer(index: Int) {
        // Keep a window around the tapped item so the playlist fits in one Intent.
        val start = max(0, min(index - PLAYLIST_SIZE_MAX / 2, folderVideos.size - PLAYLIST_SIZE_MAX))
        val end = min(folderVideos.size, start + PLAYLIST_SIZE_MAX)
        val paths: List<Path> = folderVideos.subList(start, end).map { it.path }
        val intent = VideoPlayerActivity::class.createIntent()
        VideoPlayerActivity.putExtras(intent, paths, index - start)
        startActivity(intent)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private fun buildFolders(byFolder: Map<String, List<VideoEntry>>): List<FolderEntry> =
    byFolder.map { (path, videos) ->
        FolderEntry(path, path.substringAfterLast('/'), videos.size)
    }.sortedBy { it.name.lowercase() }

private class VideoEntry(
    val path: Path,
    val attributes: BasicFileAttributes?,
    val name: String,
    val folder: String,
    val durationMs: Long
)

private class FolderEntry(
    val path: String,
    val name: String,
    val count: Int
)

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

private class FolderAdapter(
    private val context: Context,
    private val folders: List<FolderEntry>
) : BaseAdapter() {
    override fun getCount(): Int = folders.size

    override fun getItem(position: Int): Any = folders[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView as? LinearLayout ?: createFolderTile(context)
        val folder = folders[position]
        (view.getChildAt(1) as TextView).text = folder.name
        (view.getChildAt(2) as TextView).text =
            context.getString(R.string.video_library_count, folder.count)
        return view
    }
}

private fun createFolderTile(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(context.dp(8), context.dp(12), context.dp(8), context.dp(12))
    addView(
        ImageView(context).apply { setImageResource(R.drawable.file_directory_thumbnail) },
        LinearLayout.LayoutParams(context.dp(64), context.dp(64))
    )
    addView(
        TextView(context).apply {
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        },
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(4) }
    )
    addView(
        TextView(context).apply {
            textSize = 12f
            alpha = 0.7f
            gravity = Gravity.CENTER_HORIZONTAL
        },
        LinearLayout.LayoutParams(MATCH, WRAP)
    )
}

private class VideoAdapter(
    private val context: Context,
    private val videos: List<VideoEntry>,
    private val mode: VideoViewMode
) : BaseAdapter() {
    override fun getCount(): Int = videos.size

    override fun getItem(position: Int): Any = videos[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val video = videos[position]
        // Reuse only views built for this mode (the tag records the mode).
        return when (mode) {
            VideoViewMode.COMPACT -> bindCompact(convertView, video)
            VideoViewMode.GRID, VideoViewMode.GALLERY -> bindTile(convertView, video)
        }
    }

    private fun bindCompact(convertView: View?, video: VideoEntry): View {
        val view = (convertView?.takeIf { it.tag == mode } as? LinearLayout)
            ?: createCompactRow(context).also { it.tag = mode }
        (view.getChildAt(0) as TextView).text = video.name
        (view.getChildAt(1) as TextView).text =
            DateUtils.formatElapsedTime(video.durationMs / 1000)
        return view
    }

    private fun bindTile(convertView: View?, video: VideoEntry): View {
        val withThumbnail = mode == VideoViewMode.GALLERY
        val view = (convertView?.takeIf { it.tag == mode } as? LinearLayout)
            ?: createTile(context, withThumbnail).also { it.tag = mode }
        val image = view.getChildAt(0) as ImageView
        image.dispose()
        image.setImageResource(R.drawable.file_video_icon)
        val attributes = video.attributes
        if (withThumbnail && attributes != null) {
            image.load(video.path to attributes) {
                placeholder(R.drawable.file_video_icon)
                error(R.drawable.file_video_icon)
            }
        }
        (view.getChildAt(1) as TextView).text = video.name
        (view.getChildAt(2) as TextView).text =
            DateUtils.formatElapsedTime(video.durationMs / 1000)
        return view
    }
}

private fun createCompactRow(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
    addView(
        TextView(context).apply {
            textSize = 15f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    )
    addView(
        TextView(context).apply {
            textSize = 12f
            alpha = 0.7f
        }
    )
}

private fun createTile(context: Context, withThumbnail: Boolean): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
        val imageHeight = if (withThumbnail) context.dp(96) else context.dp(48)
        addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            },
            LinearLayout.LayoutParams(MATCH, imageHeight)
        )
        addView(
            TextView(context).apply {
                textSize = 13f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(4) }
        )
        addView(
            TextView(context).apply {
                textSize = 11f
                alpha = 0.7f
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
    }
