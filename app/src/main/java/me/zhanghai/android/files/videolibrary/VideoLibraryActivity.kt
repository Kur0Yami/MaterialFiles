/*
 * "Video" standard directory: every video MediaStore knows about, played as one playlist.
 */

package me.zhanghai.android.files.videolibrary

import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
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
    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private var entries: List<VideoEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toolbar = Toolbar(this).apply {
            setBackgroundColor(getColorByAttr(R.attr.colorAppBarSurface))
            title = getString(R.string.navigation_standard_directory_video)
        }
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        listView = ListView(this).apply {
            setOnItemClickListener { _, _, position, _ -> openPlayer(position) }
        }
        emptyText = TextView(this).apply {
            text = getString(R.string.video_library_empty)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        val content = FrameLayout(this).apply {
            addView(listView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emptyText, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        })

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { queryVideos() }
            entries = loaded
            listView.adapter = VideoAdapter(this@VideoLibraryActivity, loaded)
            emptyText.visibility = if (loaded.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        val start = max(0, min(index - PLAYLIST_SIZE_MAX / 2, entries.size - PLAYLIST_SIZE_MAX))
        val end = min(entries.size, start + PLAYLIST_SIZE_MAX)
        val paths: List<Path> = entries.subList(start, end).map { it.path }
        val intent = VideoPlayerActivity::class.createIntent()
        VideoPlayerActivity.putExtras(intent, paths, index - start)
        startActivity(intent)
    }
}

private class VideoEntry(
    val path: Path,
    val name: String,
    val folder: String,
    val durationMs: Long
)

private class VideoAdapter(context: Context, entries: List<VideoEntry>) :
    ArrayAdapter<VideoEntry>(
        context, android.R.layout.simple_list_item_2, android.R.id.text1, entries
    ) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val entry = getItem(position)!!
        view.findViewById<TextView>(android.R.id.text1).text = entry.name
        view.findViewById<TextView>(android.R.id.text2).text =
            "${entry.folder} - ${DateUtils.formatElapsedTime(entry.durationMs / 1000)}"
        return view
    }
}
