/*
 * Run-script support (fork patch).
 * Starts `sh <script> [-d]` through the system `su` binary (Magisk, KernelSU, etc.), so a script
 * runs as root. Uses a plain Process instead of libsu's Shell.job, because a job only returns
 * output when the script finishes, while the output dialog needs to stream it.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.provider.root.LibSuFileServiceLauncher
import java.util.UUID

/** A running root script: the `su` client plus the pid file of the script's process group. */
class RootScriptRun(val process: Process, val pidFile: String)

object RootScriptRunner {
    fun isAvailable(): Boolean = LibSuFileServiceLauncher.isSuAvailable()

    fun start(scriptPath: String, dryRun: Boolean, workingDirectory: String?): RootScriptRun {
        val pidFile = "/data/local/tmp/materialfiles-script-" + UUID.randomUUID() + ".pid"
        val script = buildString {
            if (workingDirectory != null) {
                append("cd ").append(shellQuote(workingDirectory)).append(" && ")
            }
            append("exec sh ").append(shellQuote(scriptPath))
            if (dryRun) append(" -d")
            append(" 2>&1")
        }
        // setsid starts the script in a new session and process group whose id equals the
        // shell's pid. Stop can then signal the whole group, not just the su client.
        val inner = "echo \$\$ > ${shellQuote(pidFile)}; $script"
        // Falls back to a plain shell if this device has no setsid; stop then signals the pid only.
        val launch = "if command -v setsid >/dev/null 2>&1; then exec setsid sh -c ${shellQuote(inner)}; " +
            "else exec sh -c ${shellQuote(inner)}; fi"
        val process = ProcessBuilder("su", "-c", launch)
            .redirectErrorStream(true)
            .start()
        return RootScriptRun(process, pidFile)
    }

    /** Terminates the script's whole process group: SIGTERM, then SIGKILL after a short grace. */
    fun killGroup(pidFile: String) {
        val pf = shellQuote(pidFile)
        // "-- -PID" signals the group; "PID" alone is the fallback when there is no group.
        val cmd = "p=\$(cat $pf 2>/dev/null); " +
            "if [ -n \"\$p\" ]; then " +
            "kill -TERM -- -\$p 2>/dev/null || kill -TERM \$p 2>/dev/null; sleep 1; " +
            "kill -KILL -- -\$p 2>/dev/null || kill -KILL \$p 2>/dev/null; fi; rm -f $pf"
        try {
            ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start().waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
