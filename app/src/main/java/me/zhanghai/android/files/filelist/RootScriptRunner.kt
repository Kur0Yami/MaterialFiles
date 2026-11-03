/*
 * Run-script support (fork patch).
 * Starts `sh <script> [-d]` through the system `su` binary (Magisk, KernelSU, etc.), so a script
 * runs as root. Uses a plain Process instead of libsu's Shell.job, because a job only returns
 * output when the script finishes, while the output dialog needs to stream it.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.provider.root.LibSuFileServiceLauncher

object RootScriptRunner {
    fun isAvailable(): Boolean = LibSuFileServiceLauncher.isSuAvailable()

    fun start(scriptPath: String, dryRun: Boolean, workingDirectory: String?): Process {
        val inner = buildString {
            if (workingDirectory != null) {
                append("cd ").append(shellQuote(workingDirectory)).append(" && ")
            }
            append("exec sh ").append(shellQuote(scriptPath))
            if (dryRun) append(" -d")
            append(" 2>&1")
        }
        return ProcessBuilder("su", "-c", inner)
            .redirectErrorStream(true)
            .start()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
