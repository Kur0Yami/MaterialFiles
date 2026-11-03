/*
 * Internal file opening (fork patch).
 * Material Files no longer declares VIEW/SEND/picker intent filters, so other apps can't see it in
 * "Open with". Its own viewers are still reached by explicit intents, which need no filter.
 */

package me.zhanghai.android.files.filelist

import android.content.ComponentName
import android.content.Intent
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.intentType
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import me.zhanghai.android.files.viewer.text.TextEditorActivity

private val ARCHIVE_MIME_TYPES = setOf(
        "application/gzip",
        "application/java-archive",
        "application/rar",
        "application/vnd.android.package-archive",
        "application/vnd.debian.binary-package",
        "application/vnd.ms-cab-compressed",
        "application/vnd.rar",
        "application/x-7z-compressed",
        "application/x-bzip2",
        "application/x-compress",
        "application/x-cpio",
        "application/x-deb",
        "application/x-debian-package",
        "application/x-gtar",
        "application/x-gtar-compressed",
        "application/x-java-archive",
        "application/x-lzma",
        "application/x-tar",
        "application/x-xz",
        "application/zip",
        "application/zstd",
)

// text/* is matched by prefix below, so it isn't listed here.
private val TEXT_MIME_TYPES = setOf(
        "application/ecmascript",
        "application/javascript",
        "application/json",
        "application/typescript",
        "application/x-sh",
        "application/x-shellscript",
        "application/xml",
        "application/yaml",
)

/**
 * Points this view intent at one of our own viewers, so it still works without intent filters.
 * Types we have no viewer for stay an implicit intent (other apps).
 */
fun Intent.routeToInternalViewer(mimeType: MimeType): Intent {
    val type = mimeType.intentType
    val target = when {
        mimeType.isImage -> ImageViewerActivity::class.java
        type.startsWith("text/") || type in TEXT_MIME_TYPES -> TextEditorActivity::class.java
        type in ARCHIVE_MIME_TYPES -> FileListActivity::class.java
        else -> return this
    }
    component = ComponentName(BuildConfig.APPLICATION_ID, target.name)
    return this
}
