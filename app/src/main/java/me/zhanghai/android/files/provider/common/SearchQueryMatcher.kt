/*
 * Filter/search support (fork patch).
 * A query is split on whitespace; a name matches when it contains EVERY word, ignoring case.
 */

package me.zhanghai.android.files.provider.common

private val WHITESPACE = Regex("\\s+")

fun splitSearchQuery(query: String): List<String> =
    query.trim().split(WHITESPACE).filter { it.isNotEmpty() }

fun matchesSearchWords(name: String, words: List<String>): Boolean =
    words.all { name.contains(it, ignoreCase = true) }
