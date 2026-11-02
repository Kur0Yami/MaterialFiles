/*
 * Run-script support (fork patch).
 * Starts `sh` through Shizuku so a script runs as the shell user (UID 2000), which may write to
 * microSD paths that a normal app cannot on Android 9 and below.
 */

package me.zhanghai.android.files.filelist

import android.content.pm.PackageManager
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku

object ShizukuScriptRunner {
    private const val REQUEST_CODE = 0x5C21
    private const val PERMISSION_TIMEOUT_SECONDS = 60L

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /** Blocks (never call on the main thread) until the user answers Shizuku's prompt. */
    fun ensurePermission(): Boolean {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        val latch = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == REQUEST_CODE) {
                    granted.set(grantResult == PackageManager.PERMISSION_GRANTED)
                    latch.countDown()
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(REQUEST_CODE)
            latch.await(PERMISSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
        return granted.get()
    }

    // Shizuku.newProcess() is private since API 13, so call it reflectively. This is the same
    // trick many Shizuku apps use; the keep rule in proguard-rules.pro protects it from R8.
    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    /**
     * Runs `sh <script> [-d]` as the Shizuku user. stderr is merged into stdout inside the shell
     * (`2>&1`), because a remote process only exposes separate streams.
     */
    fun start(scriptPath: String, dryRun: Boolean, workingDirectory: String?): Process {
        val command = mutableListOf("sh", "-c", "exec sh \"\$0\" \"\$@\" 2>&1", scriptPath)
        if (dryRun) command += "-d"
        return try {
            newProcessMethod.invoke(null, command.toTypedArray(), null, workingDirectory) as Process
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }
}
