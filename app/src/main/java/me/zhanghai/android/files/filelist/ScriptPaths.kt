/*
 * Run-script support (fork patch).
 * Maps a file's Path to a raw filesystem path that `sh` can open, or null if there is none.
 */

package me.zhanghai.android.files.filelist

import android.os.Environment
import android.provider.DocumentsContract
import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import me.zhanghai.android.files.provider.document.documentTreeUri
import me.zhanghai.android.files.provider.linux.isLinuxPath

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * Linux path -> its own path. SAF document path on the external storage provider (internal
 * storage, microSD, OTG) -> `/storage/<volume>/<tree folder>/<path in tree>`. Anything else -> null.
 */
fun Path.toScriptRawPath(): String? {
    if (isLinuxPath) {
        return toFile().path
    }
    val treeUri = try {
        documentTreeUri
    } catch (e: ProviderMismatchException) {
        return null
    }
    if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) {
        return null
    }
    // Tree document ID looks like "XXXX-XXXX:" (SD root) or "primary:Download/scripts".
    val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
    val volume = treeDocumentId.substringBefore(':')
    val treeRelativePath = treeDocumentId.substringAfter(':', "").trim('/')
    val volumeRoot = if (volume == "primary") {
        Environment.getExternalStorageDirectory().path
    } else {
        "/storage/$volume"
    }
    val base = if (treeRelativePath.isEmpty()) volumeRoot else "$volumeRoot/$treeRelativePath"
    // For a DocumentPath this is the absolute path inside the tree, e.g. "/oat/sdtest.sh".
    return base + toString().trimEnd('/')
}
