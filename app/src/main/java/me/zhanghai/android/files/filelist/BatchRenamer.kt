/*
 * Batch rename support (fork patch). Pure logic, no Android dependencies.
 *
 * Pattern tokens (same as MT Manager):
 *   {P} name without extension    {S} extension with dot (".txt")
 *   {N} counter starting at N, e.g. {1}, {0}
 *   {zN} like {N} but zero-padded to the width of the last number, e.g. {z8} -> 08, 09, 10
 * Find/replace runs on the result of the pattern.
 */

package me.zhanghai.android.files.filelist

object BatchRenamer {
    class Result(val names: List<String>, val error: String?)

    private val TOKEN = Regex("\\{(P|S|z?\\d+)\\}")

    fun splitName(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    fun rename(
        names: List<String>,
        pattern: String,
        find: String,
        replace: String,
        regex: Boolean
    ): Result {
        val effectivePattern = pattern.ifEmpty { "{P}{S}" }
        val compiled = if (find.isNotEmpty() && regex) {
            try {
                Regex(find)
            } catch (e: Exception) {
                return Result(names, "Invalid regex")
            }
        } else {
            null
        }
        val result = names.mapIndexed { index, name ->
            val (prefix, suffix) = splitName(name)
            var newName = TOKEN.replace(effectivePattern) { match ->
                when (val token = match.groupValues[1]) {
                    "P" -> prefix
                    "S" -> suffix
                    else -> {
                        val padded = token.startsWith("z")
                        val start = token.removePrefix("z").toLongOrNull() ?: return@replace match.value
                        val number = start + index
                        if (padded) {
                            val width = (start + names.size - 1).toString().length
                            number.toString().padStart(width, '0')
                        } else {
                            number.toString()
                        }
                    }
                }
            }
            if (find.isNotEmpty()) {
                newName = compiled?.replace(newName, replace) ?: newName.replace(find, replace)
            }
            newName
        }
        return Result(result, null)
    }

    /** Index-aligned: null = ok (or unchanged), otherwise the reason this rename is not allowed. */
    fun findConflicts(
        oldNames: List<String>,
        newNames: List<String>,
        existingNames: Collection<String>
    ): List<String?> {
        val existing = existingNames.toHashSet()
        val counts = HashMap<String, Int>()
        for (index in newNames.indices) {
            if (newNames[index] != oldNames[index]) {
                counts[newNames[index]] = (counts[newNames[index]] ?: 0) + 1
            }
        }
        return newNames.indices.map { index ->
            val newName = newNames[index]
            when {
                newName == oldNames[index] -> null
                newName.isEmpty() || newName == "." || newName == ".." ||
                    newName.contains('/') || newName.contains('\u0000') -> "invalid name"
                (counts[newName] ?: 0) > 1 -> "duplicate"
                newName in existing -> "already exists"
                else -> null
            }
        }
    }
}
