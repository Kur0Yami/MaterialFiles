package me.zhanghai.android.files.filelist

import android.content.Context
import java8.nio.file.Path
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.startActivitySafe

object ImageToolActions {
    fun editNinePatch(context: Context, path: Path) {
        val intent = NinePatchEditorActivity::class.createIntent()
        intent.extraPath = path
        context.startActivitySafe(intent)
    }

    fun setWallpaper(context: Context, path: Path) {
        val intent = WallpaperCropActivity::class.createIntent()
        intent.extraPath = path
        context.startActivitySafe(intent)
    }
}
