/*
 * "Video" standard directory: videos from MediaStore, grouped by folder.
 * Tap a folder to list its videos; tapping a video plays that folder as a playlist.
 * Video states: NEW badge (added recently, not watched), grey title (watched),
 * blue title (last played), black title (default).
 */

package me.zhanghai.android.files.videolibrary

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.IconCompat
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

// Same SharedPreferences file the player writes positions and watched state to.
private const val PLAYER_PREFS_NAME = "video_player_positions"
private const val PREFS_NAME = "video_library"
private const val PREF_VIEW_MODE = "view_mode"
private const val PREF_SORT_BY = "sort_by"
private const val PREF_SORT_ASCENDING = "sort_ascending"
private const val PREF_INCLUDE_SUBFOLDERS = "include_subfolders"
private const val PREF_SHOW_SHORTCUT = "show_shortcut"

private const val MENU_OPTIONS = 1
private const val MENU_SHORTCUT = 2

// A video counts as NEW for this long after it was added, until it is watched.
private const val NEW_WINDOW_SECONDS = 7L * 24 * 60 * 60

private const val NEW_BADGE_COLOR = 0xFFE5484D.toInt()
private const val DURATION_BADGE_COLOR = 0x99000000.toInt()

private enum class VideoViewMode(@StringRes val titleRes: Int, val columns: Int) {
    COMPACT(R.string.video_view_compact, 1),
    GRID(R.string.video_view_grid, 2),
    GALLERY(R.string.video_view_gallery, 3)
}

private enum class SortBy(@StringRes val titleRes: Int) {
    TITLE(R.string.video_sort_title),
    LENGTH(R.string.video_sort_length)
}

@Suppress("DEPRECATION")
class VideoLibraryActivity : AppActivity() {
    private lateinit var toolbar: Toolbar
    private lateinit var folderGrid: GridView
    private lateinit var videoGrid: GridView
    private lateinit var emptyText: TextView

    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val playerPrefs: SharedPreferences by lazy {
        getSharedPreferences(PLAYER_PREFS_NAME, MODE_PRIVATE)
    }
    private var viewMode = VideoViewMode.COMPACT
    private var sortBy = SortBy.TITLE
    private var sortAscending = true
    private var includeSubfolders = false
    private var showShortcut = false
    private var optionsDialog: AlertDialog? = null
    private var actionMode: ActionMode? = null

    private var allVideos: List<VideoEntry> = emptyList()
    private var folders: List<FolderEntry> = emptyList()
    // Videos of the folder currently open; also the playlist for playback.
    private var folderVideos: List<VideoEntry> = emptyList()
    private var currentFolder: FolderEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPreferences()

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
            choiceMode = AbsListView.CHOICE_MODE_MULTIPLE_MODAL
            setMultiChoiceModeListener(SelectionCallback())
            setOnItemClickListener { _, _, position, _ -> if (actionMode == null) openPlayer(position) }
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

        val pendingFolderPath = intent.getStringExtra(EXTRA_FOLDER)
        lifecycleScope.launch {
            allVideos = withContext(Dispatchers.IO) { queryVideos() }
            refreshFolders()
            val pending = pendingFolderPath?.let { path -> folders.find { it.path == path } }
            if (pending != null) showFolder(pending) else showFolders()
        }
    }

    override fun onResume() {
        super.onResume()
        // Watched and last-played state may have changed in the player.
        if (currentFolder != null) {
            reloadVideoGrid()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_OPTIONS, Menu.NONE, R.string.video_options_title)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_SHORTCUT, Menu.NONE, R.string.video_add_shortcut)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_SHORTCUT)?.isVisible = showShortcut
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_OPTIONS -> {
            showOptionsDialog()
            true
        }
        MENU_SHORTCUT -> {
            createShortcut()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (currentFolder != null) {
            showFolders()
            return true
        }
        return super.onSupportNavigateUp()
    }

    private fun loadPreferences() {
        viewMode = prefs.getString(PREF_VIEW_MODE, null)
            ?.let { name -> VideoViewMode.entries.find { it.name == name } }
            ?: VideoViewMode.COMPACT
        sortBy = prefs.getString(PREF_SORT_BY, null)
            ?.let { name -> SortBy.entries.find { it.name == name } }
            ?: SortBy.TITLE
        sortAscending = prefs.getBoolean(PREF_SORT_ASCENDING, true)
        includeSubfolders = prefs.getBoolean(PREF_INCLUDE_SUBFOLDERS, false)
        showShortcut = prefs.getBoolean(PREF_SHOW_SHORTCUT, false)
    }

    private fun savePreference(key: String, value: Any) {
        val editor = prefs.edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
        }
        editor.apply()
    }

    private fun showOptionsDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))

            addView(sectionTitle(R.string.video_section_view))
            for (mode in VideoViewMode.entries) {
                addView(radio(getString(mode.titleRes), viewMode == mode) {
                    setViewMode(mode)
                    optionsDialog?.dismiss()
                })
            }

            addView(sectionTitle(R.string.video_section_sort))
            for (by in SortBy.entries) {
                addView(radio(getString(by.titleRes), sortBy == by) {
                    setSortBy(by)
                    optionsDialog?.dismiss()
                })
            }
            addView(radio(getString(R.string.video_sort_asc), sortAscending) {
                setSortAscending(true)
                optionsDialog?.dismiss()
            })
            addView(radio(getString(R.string.video_sort_desc), !sortAscending) {
                setSortAscending(false)
                optionsDialog?.dismiss()
            })

            addView(sectionTitle(R.string.video_section_more))
            addView(SwitchCompat(this@VideoLibraryActivity).apply {
                text = getString(R.string.video_include_subfolders)
                isChecked = includeSubfolders
                setOnCheckedChangeListener { _, checked -> setIncludeSubfolders(checked) }
            })
            addView(SwitchCompat(this@VideoLibraryActivity).apply {
                text = getString(R.string.video_show_shortcut)
                isChecked = showShortcut
                setOnCheckedChangeListener { _, checked ->
                    showShortcut = checked
                    savePreference(PREF_SHOW_SHORTCUT, checked)
                    invalidateOptionsMenu()
                }
            })
        }
        optionsDialog = AlertDialog.Builder(this)
            .setTitle(R.string.video_options_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun sectionTitle(@StringRes res: Int): TextView = TextView(this).apply {
        setText(res)
        textSize = 13f
        alpha = 0.7f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun radio(text: String, checked: Boolean, onClick: () -> Unit): RadioButton =
        RadioButton(this).apply {
            this.text = text
            isChecked = checked
            setOnClickListener { onClick() }
        }

    private fun setViewMode(mode: VideoViewMode) {
        viewMode = mode
        savePreference(PREF_VIEW_MODE, mode.name)
        reloadVideoGrid()
        invalidateOptionsMenu()
    }

    private fun setSortBy(by: SortBy) {
        sortBy = by
        savePreference(PREF_SORT_BY, by.name)
        reloadVideoGrid()
    }

    private fun setSortAscending(ascending: Boolean) {
        sortAscending = ascending
        savePreference(PREF_SORT_ASCENDING, ascending)
        reloadVideoGrid()
    }

    private fun setIncludeSubfolders(include: Boolean) {
        includeSubfolders = include
        savePreference(PREF_INCLUDE_SUBFOLDERS, include)
        refreshFolders()
        reloadVideoGrid()
    }

    private fun refreshFolders() {
        val folderPaths = allVideos.map { it.folder }.distinct()
        folders = folderPaths
            .map { path -> FolderEntry(path, path.substringAfterLast('/'), videosFor(path).size) }
            .sortedBy { it.name.lowercase() }
        folderGrid.adapter = FolderAdapter(this, folders)
    }

    // Videos belonging to a folder, including subfolders when that option is on.
    private fun videosFor(folder: String): List<VideoEntry> {
        val matched = allVideos.filter {
            it.folder == folder || (includeSubfolders && it.folder.startsWith("$folder/"))
        }
        val sorted = when (sortBy) {
            SortBy.TITLE -> matched.sortedBy { it.name.lowercase() }
            SortBy.LENGTH -> matched.sortedBy { it.durationMs }
        }
        return if (sortAscending) sorted else sorted.reversed()
    }

    private fun showFolders() {
        actionMode?.finish()
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
        folderGrid.visibility = View.GONE
        videoGrid.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        reloadVideoGrid()
        invalidateOptionsMenu()
    }

    private fun reloadVideoGrid() {
        val folder = currentFolder ?: return
        folderVideos = videosFor(folder.path)
        videoGrid.numColumns = viewMode.columns
        videoGrid.adapter = VideoAdapter(this, folderVideos, viewMode, playerPrefs) { position ->
            videoGrid.isItemChecked(position)
        }
    }

    private fun createShortcut() {
        val folder = currentFolder
        val label = folder?.name ?: getString(R.string.navigation_standard_directory_video)
        val shortcut = ShortcutInfoCompat.Builder(this, "video_library:${folder?.path.orEmpty()}")
            .setShortLabel(label)
            .setIntent(createViewIntent(this, folder?.path))
            .setIcon(IconCompat.createWithResource(this, R.mipmap.directory_shortcut_icon))
            .build()
        ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, R.string.shortcut_created, Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryVideos(): List<VideoEntry> {
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED
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
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
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
                    cursor.getLong(durationColumn),
                    cursor.getLong(dateAddedColumn)
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

    private inner class SelectionCallback : AbsListView.MultiChoiceModeListener {
        override fun onItemCheckedStateChanged(
            mode: ActionMode,
            position: Int,
            id: Long,
            checked: Boolean
        ) {
            mode.title = "${videoGrid.checkedItemCount} dipilih"
            (videoGrid.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            menu.add(0, MENU_SEL_WATCHED, Menu.NONE, "Tandai sudah ditonton")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, MENU_SEL_UNWATCHED, Menu.NONE, "Tandai belum ditonton")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add(0, MENU_SEL_MOVE, Menu.NONE, "Pindah")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, MENU_SEL_DELETE, Menu.NONE, "Hapus")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val selected = selectedVideos()
            if (selected.isEmpty()) return false
            return when (item.itemId) {
                MENU_SEL_WATCHED -> {
                    setWatched(selected, true)
                    mode.finish()
                    true
                }
                MENU_SEL_UNWATCHED -> {
                    setWatched(selected, false)
                    mode.finish()
                    true
                }
                MENU_SEL_MOVE -> {
                    showMoveDialog(selected, mode)
                    true
                }
                MENU_SEL_DELETE -> {
                    showDeleteDialog(selected, mode)
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
        }
    }

    private fun selectedVideos(): List<VideoEntry> {
        val positions = videoGrid.checkedItemPositions
        return (0 until positions.size())
            .filter { positions.valueAt(it) }
            .map { folderVideos[positions.keyAt(it)] }
    }

    private fun setWatched(videos: List<VideoEntry>, watched: Boolean) {
        val editor = playerPrefs.edit()
        for (video in videos) {
            editor.putBoolean(VideoPlayerActivity.watchedKey(video.mediaId), watched)
            if (watched) {
                editor.remove(video.mediaId)
            }
        }
        editor.apply()
        reloadVideoGrid()
    }

    private fun showDeleteDialog(videos: List<VideoEntry>, mode: ActionMode) {
        AlertDialog.Builder(this)
            .setTitle("Hapus ${videos.size} video?")
            .setMessage("File video akan dihapus permanen dari penyimpanan.")
            .setPositiveButton("Hapus") { _, _ ->
                val deleted = videos.filter { video ->
                    runCatching { Files.delete(video.path) }.isSuccess
                }
                finishSelection(mode, deleted, removed = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMoveDialog(videos: List<VideoEntry>, mode: ActionMode) {
        // Tujuan hanya folder yang sudah ada di library.
        val targets = folders.filter { folder -> videos.none { it.folder == folder.path } }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Tidak ada folder tujuan lain", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pindah ${videos.size} video ke")
            .setItems(targets.map { it.name }.toTypedArray()) { _, which ->
                val directory = Paths.get(targets[which].path)
                val moved = videos.filter { video ->
                    runCatching {
                        Files.move(video.path, directory.resolve(video.path.fileName.toString()))
                    }.isSuccess
                }
                finishSelection(mode, moved, removed = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun finishSelection(mode: ActionMode, changed: List<VideoEntry>, removed: Boolean) {
        mode.finish()
        if (changed.isEmpty()) {
            Toast.makeText(this, "Tidak ada video yang berhasil diproses", Toast.LENGTH_SHORT).show()
            return
        }
        val removedIds = if (removed) changed.map { it.mediaId }.toSet() else emptySet()
        val paths = changed.map { it.path.toString() }.toTypedArray()
        // Sinkronkan MediaStore dulu supaya daftar video ikut berubah.
        MediaScannerConnection.scanFile(this, paths, null) { _, _ ->
            lifecycleScope.launch {
                allVideos = withContext(Dispatchers.IO) { queryVideos() }
                    .filterNot { it.mediaId in removedIds }
                refreshFolders()
                reloadVideoGrid()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_FOLDER = "me.zhanghai.android.files.videolibrary.extra.FOLDER"

        private const val MENU_SEL_WATCHED = 101
        private const val MENU_SEL_UNWATCHED = 102
        private const val MENU_SEL_MOVE = 103
        private const val MENU_SEL_DELETE = 104

        // Used by home screen shortcuts: opens the library, or one folder of it directly.
        fun createViewIntent(context: Context, folderPath: String?): Intent =
            Intent(context, VideoLibraryActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_FOLDER, folderPath)
    }
}

private class VideoEntry(
    val path: Path,
    val attributes: BasicFileAttributes?,
    val name: String,
    val folder: String,
    val durationMs: Long,
    val dateAddedSeconds: Long
) {
    // Same id the player uses for positions and watched state.
    val mediaId: String = path.toUri().toString()
}

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
    private val mode: VideoViewMode,
    private val playerPrefs: SharedPreferences,
    private val isSelected: (Int) -> Boolean = { false }
) : BaseAdapter() {
    private val lastPlayedId = playerPrefs.getString(VideoPlayerActivity.KEY_LAST_PLAYED, null)
    private val colorDefault = context.getColorByAttr(android.R.attr.textColorPrimary)
    private val colorWatched = context.getColorByAttr(android.R.attr.textColorSecondary)
    private val colorLastPlayed = context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
    private val colorSelected =
        ColorUtils.setAlphaComponent(context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary), 72)

    override fun getCount(): Int = videos.size

    override fun getItem(position: Int): Any = videos[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val video = videos[position]
        // Reuse only views built for this mode (the tag records the mode).
        val view = when (mode) {
            VideoViewMode.COMPACT -> bindCompact(convertView, video)
            VideoViewMode.GRID, VideoViewMode.GALLERY -> bindTile(convertView, video)
        }
        view.foreground = if (isSelected(position)) ColorDrawable(colorSelected) else null
        return view
    }

    private fun isWatched(video: VideoEntry): Boolean =
        playerPrefs.getBoolean(VideoPlayerActivity.watchedKey(video.mediaId), false)

    private fun isNew(video: VideoEntry): Boolean {
        val nowSeconds = System.currentTimeMillis() / 1000
        return !isWatched(video) && nowSeconds - video.dateAddedSeconds < NEW_WINDOW_SECONDS
    }

    private fun titleColor(video: VideoEntry): Int = when {
        video.mediaId == lastPlayedId -> colorLastPlayed
        isWatched(video) -> colorWatched
        else -> colorDefault
    }

    private fun bindCompact(convertView: View?, video: VideoEntry): View {
        val view = (convertView?.takeIf { it.tag == mode } as? LinearLayout)
            ?: createCompactRow(context).also { it.tag = mode }
        (view.getChildAt(0) as TextView).apply {
            text = video.name
            setTextColor(titleColor(video))
        }
        (view.getChildAt(1) as TextView).text =
            DateUtils.formatElapsedTime(video.durationMs / 1000)
        return view
    }

    private fun bindTile(convertView: View?, video: VideoEntry): View {
        val withThumbnail = mode == VideoViewMode.GALLERY
        val view = (convertView?.takeIf { it.tag == mode } as? LinearLayout)
            ?: createTile(context, withThumbnail).also { it.tag = mode }
        val frame = view.getChildAt(0) as FrameLayout
        val image = frame.getChildAt(0) as ImageView
        val newBadge = frame.getChildAt(1) as TextView
        val durationBadge = frame.getChildAt(2) as TextView

        image.dispose()
        image.setImageResource(R.drawable.file_video_icon)
        val attributes = video.attributes
        if (withThumbnail && attributes != null) {
            image.load(video.path to attributes) {
                placeholder(R.drawable.file_video_icon)
                error(R.drawable.file_video_icon)
            }
        }
        newBadge.visibility = if (isNew(video)) View.VISIBLE else View.GONE
        durationBadge.text = DateUtils.formatElapsedTime(video.durationMs / 1000)
        durationBadge.visibility = View.VISIBLE
        (view.getChildAt(1) as TextView).apply {
            text = video.name
            setTextColor(titleColor(video))
        }
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

private fun createBadge(context: Context, text: CharSequence?, color: Int, gravity: Int) =
    TextView(context).apply {
        this.text = text
        textSize = 10f
        setTextColor(Color.WHITE)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(context.dp(5), context.dp(2), context.dp(5), context.dp(2))
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.dp(4).toFloat()
        }
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, gravity).apply {
            setMargins(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
        }
    }

// The thumbnail frame holds the image plus the NEW badge (top start) and duration (bottom end).
private fun createTile(context: Context, withThumbnail: Boolean): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
        val imageHeight = if (withThumbnail) context.dp(96) else context.dp(64)
        addView(
            FrameLayout(context).apply {
                addView(
                    ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP },
                    FrameLayout.LayoutParams(MATCH, MATCH)
                )
                addView(
                    createBadge(
                        context,
                        context.getString(R.string.video_badge_new),
                        NEW_BADGE_COLOR,
                        Gravity.TOP or Gravity.START
                    )
                )
                addView(
                    createBadge(context, null, DURATION_BADGE_COLOR, Gravity.BOTTOM or Gravity.END)
                )
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
    }
