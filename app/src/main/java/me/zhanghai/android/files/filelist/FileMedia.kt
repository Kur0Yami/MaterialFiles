/*
 * Gallery support (fork patch).
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.intentType

/** Whether this file is a photo or video. */
fun FileItem.isMediaFile(): Boolean {
    val type = mimeType.intentType
    return type.startsWith("image/") || type.startsWith("video/")
}
