/*
 * Filter/search support (fork patch).
 * Name filter (plain words or regex) plus an optional size range, for the current folder
 * (instant) or for a recursive search ("subfolders").
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.provider.common.matchesSearchWords
import me.zhanghai.android.files.provider.common.splitSearchQuery

/** Quick type chips (fork patch). */
enum class FileTypeFilter {
    ALL, FOLDERS, IMAGES, VIDEOS, AUDIO
}

data class FileFilterSpec(
    val query: String = "",
    val regex: Boolean = false,
    val subfolders: Boolean = false,
    /** Inclusive bounds in bytes, or -1 for "no bound". */
    val minSize: Long = -1,
    val maxSize: Long = -1,
    val type: FileTypeFilter = FileTypeFilter.ALL
) {
    val hasSize: Boolean
        get() = minSize >= 0 || maxSize >= 0

    val hasType: Boolean
        get() = type != FileTypeFilter.ALL

    /** Plain words only: the provider can do the name matching by itself (fast path). */
    val isPlain: Boolean
        get() = !regex && !hasSize && !hasType

    val isActive: Boolean
        get() = query.isNotBlank() || hasSize || hasType

    private val words: List<String> by lazy { splitSearchQuery(query) }

    private val pattern: Regex? by lazy {
        try {
            Regex(query, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            null
        }
    }

    val isRegexInvalid: Boolean
        get() = regex && query.isNotEmpty() && pattern == null

    fun matchesName(name: String): Boolean = when {
        query.isBlank() -> true
        regex -> pattern?.containsMatchIn(name) ?: false
        else -> matchesSearchWords(name, words)
    }

    fun matchesType(isDirectory: Boolean, mimeType: MimeType): Boolean = when (type) {
        FileTypeFilter.ALL -> true
        FileTypeFilter.FOLDERS -> isDirectory
        FileTypeFilter.IMAGES -> !isDirectory && mimeType.type == "image"
        FileTypeFilter.VIDEOS -> !isDirectory && mimeType.type == "video"
        FileTypeFilter.AUDIO -> !isDirectory && mimeType.type == "audio"
    }

    /** Folders have no meaningful size, so any size bound excludes them. */
    fun matchesSize(isDirectory: Boolean, size: Long): Boolean {
        if (!hasSize) {
            return true
        }
        if (isDirectory) {
            return false
        }
        return (minSize < 0 || size >= minSize) && (maxSize < 0 || size <= maxSize)
    }

    companion object {
        private val UNITS = arrayOf("B", "KB", "MB", "GB")

        fun formatSize(bytes: Long): String {
            var unit = 0
            while (unit < UNITS.size - 1 && bytes >= 1L shl (10 * (unit + 1))) {
                unit++
            }
            val value = bytes.toDouble() / (1L shl (10 * unit))
            val text = if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                String.format(java.util.Locale.ROOT, "%.1f", value)
            }
            return text + UNITS[unit]
        }
    }

    fun sizeSummary(): String = when {
        minSize >= 0 && maxSize >= 0 -> formatSize(minSize) + "–" + formatSize(maxSize)
        minSize >= 0 -> "≥" + formatSize(minSize)
        maxSize >= 0 -> "≤" + formatSize(maxSize)
        else -> ""
    }
}
