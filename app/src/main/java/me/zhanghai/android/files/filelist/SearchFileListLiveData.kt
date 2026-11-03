/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.search
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class SearchFileListLiveData(
    private val path: Path,
    private val query: String,
    private val filter: FileFilterSpec = FileFilterSpec()
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null

    init {
        loadValue()
    }

    fun loadValue() {
        future?.cancel(true)
        value = Loading(emptyList())
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val fileList = mutableListOf<FileItem>()
            var lastPostMillis = 0L
            try {
                // Regex / size filters are applied here, so the provider just lists everything.
                path.search(if (filter.isPlain) query else "", INTERVAL_MILLIS) { paths: List<Path> ->
                    for (path in paths) {
                        if (!filter.isPlain) {
                            val fileName = path.fileName?.toString() ?: continue
                            if (!filter.matchesName(fileName)) {
                                continue
                            }
                        }
                        val fileItem = try {
                            path.loadFileItem()
                        } catch (e: IOException) {
                            e.printStackTrace()
                            // TODO: Support file without information.
                            continue
                        }
                        if (!filter.matchesType(fileItem.attributes.isDirectory, fileItem.mimeType)) {
                            continue
                        }
                        if (!filter.matchesSize(
                                fileItem.attributes.isDirectory, fileItem.attributes.size()
                            )
                        ) {
                            continue
                        }
                        fileList.add(fileItem)
                    }
                    // Copying the whole list on every batch gets slow for big trees, so partial
                    // results are published at most every POST_INTERVAL_MILLIS. Success is always sent.
                    val now = System.currentTimeMillis()
                    if (now - lastPostMillis >= POST_INTERVAL_MILLIS) {
                        lastPostMillis = now
                        postValue(Loading(fileList.toList()))
                    }
                }
                postValue(Success(fileList))
            } catch (e: Exception) {
                // TODO: Retrieval of previous value is racy.
                postValue(Failure(valueCompat.value, e))
            }
        }
    }

    override fun close() {
        future?.cancel(true)
    }

    companion object {
        private const val INTERVAL_MILLIS = 200L
        private const val POST_INTERVAL_MILLIS = 250L
    }
}
