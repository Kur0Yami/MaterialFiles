/*
 * "Video" standard directory: videos from MediaStore, grouped by folder.
 * Tap a folder to list its videos; tapping a video plays that folder as a playlist.
 */

package me.zhanghai.android.files.videolibrary

import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
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
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Path
import java8.nio.file.Paths
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

@Suppress("DEPRECATION")
class VideoLibraryActivity : AppActivity() {
    private lateinit var toolbar: Toolbar
    private lateinit var folderGrid: GridView
    private lateinit var videoList: ListView
    private lateinit var emptyText: TextView

    private var videosByFolder: Map<String, List<VideoEntry>> = emptyMap()
    private var folders: List<FolderEntry> = emptyList()
    // Videos of the folder currently open; also the playlist for playback.
    private var folderVideos: List<VideoEntry> = emptyList()
    private var currentFolder: FolderEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        videoList = ListView(this).apply {
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
            addView(videoList, FrameLayout.LayoutParams(MATCH, MATCH))
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

    override fun onSupportNavigateUp(): Boolean {
        if (currentFolder != null) {
            showFolders()
            return true
        }
        return super.onSupportNavigateUp()
    }

    private fun showFolders() {
        currentFolder = null
        toolbar.title = getString(R.string.navigation_standard_directory_video)
        folderGrid.visibility = View.VISIBLE
        videoList.visibility = View.GONE
        emptyText.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showFolder(folder: FolderEntry) {
        currentFolder = folder
        toolbar.title = folder.name
        folderVideos = videosByFolder[folder.path].orEmpty()
        videoList.adapter = VideoRowAdapter(this, folderVideos)
        folderGrid.visibility = View.GONE
        videoList.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
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
                result += VideoEntry(
                    path,
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

private class VideoRowAdapter(
    private val context: Context,
    private val videos: List<VideoEntry>
) : BaseAdapter() {
    override fun getCount(): Int = videos.size

    override fun getItem(position: Int): Any = videos[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView as? LinearLayout ?: createVideoRow(context)
        val video = videos[position]
        (view.getChildAt(0) as TextView).text = video.name
        (view.getChildAt(1) as TextView).text =
            DateUtils.formatElapsedTime(video.durationMs / 1000)
        return view
    }
}

private fun createVideoRow(context: Context): LinearLayout = LinearLayout(context).apply {
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
