package me.zhanghai.android.files.filelist

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AlertDialog
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import kotlin.concurrent.thread

object ImageToolActions {
    fun editNinePatch(context: Context, path: Path) {
        val intent = NinePatchEditorActivity::class.createIntent()
        intent.extraPath = path
        context.startActivitySafe(intent)
    }

    fun setWallpaper(context: Context, path: Path) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            applyWallpaper(context, path, 0)
            return
        }
        val items = arrayOf("Home screen", "Lock screen", "Home and lock screen")
        AlertDialog.Builder(context)
            .setTitle("Set as wallpaper")
            .setItems(items) { _, which ->
                val flags = when (which) {
                    0 -> WallpaperManager.FLAG_SYSTEM
                    1 -> WallpaperManager.FLAG_LOCK
                    else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                }
                applyWallpaper(context, path, flags)
            }
            .show()
    }

    private fun applyWallpaper(context: Context, path: Path, flags: Int) {
        val appContext = context.applicationContext
        thread {
            val message = try {
                val manager = WallpaperManager.getInstance(appContext)
                path.newInputStream().use { stream ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        manager.setStream(stream, null, true, flags)
                    } else {
                        manager.setStream(stream)
                    }
                }
                "Wallpaper set"
            } catch (e: Exception) {
                "Failed to set wallpaper: " + e.message
            }
            appContext.showToast(message)
        }
    }
}
