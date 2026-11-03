/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

enum class FileViewType {
    // New entries go at the end: the setting is stored as an index into this enum.
    LIST,
    GRID,
    COMPACT,
    DETAILED,
    GALLERY;

    /** Whether items use the grid item layout (thumbnail cards). */
    val isGridLayout: Boolean
        get() = this == GRID || this == GALLERY
}
