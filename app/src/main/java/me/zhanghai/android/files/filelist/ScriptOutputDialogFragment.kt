/*
 * Run-script support (fork patch).
 * Runs `sh <script> [-d]` with the script's folder as working directory and streams the output,
 * as this app, as root (su), or as the Shizuku shell user.
 * The process lives in a ViewModel so rotating the screen never re-runs the script.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

class ScriptOutputDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val viewModel: ScriptViewModel by lazy {
        ViewModelProvider(this)[ScriptViewModel::class.java]
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val textView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
        }
        val scrollView = ScrollView(context).apply { addView(textView) }
        viewModel.start(args.path, args.dryRun, args.method)
        viewModel.outputLiveData.observe(this) { output ->
            textView.text = output
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        val title = File(args.path).name +
            (if (args.dryRun) " (dry run)" else "") +
            when (args.method) {
                ScriptRunMethod.APP -> ""
                ScriptRunMethod.ROOT -> " [root]"
                ScriptRunMethod.SHIZUKU -> " [Shizuku]"
            }
        return MaterialAlertDialogBuilder(context, theme)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.file_run_script_stop, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        // Stop must not dismiss the dialog, so the output up to the stop point stays readable.
        val stopButton = (dialog as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL)
        stopButton.setOnClickListener { viewModel.stop() }
        viewModel.runningLiveData.observe(this) { stopButton.isEnabled = it }
    }

    companion object {
        fun show(path: String, dryRun: Boolean, method: ScriptRunMethod, fragment: Fragment) {
            ScriptOutputDialogFragment().putArgs(Args(path, dryRun, method)).show(fragment)
        }
    }

    @Parcelize
    class Args(val path: String, val dryRun: Boolean, val method: ScriptRunMethod) : ParcelableArgs

    class ScriptViewModel : ViewModel() {
        val outputLiveData = MutableLiveData("")
        val runningLiveData = MutableLiveData(true)
        private val buffer = StringBuilder()
        private var process: Process? = null
        private var started = false
        private val mainHandler = Handler(Looper.getMainLooper())
        private val refreshPending = AtomicBoolean(false)

        @Synchronized
        fun start(path: String, dryRun: Boolean, method: ScriptRunMethod) {
            if (started) return
            started = true
            Thread {
                try {
                    val script = File(path)
                    val p = when (method) {
                        ScriptRunMethod.SHIZUKU -> {
                            check(ShizukuScriptRunner.ensurePermission()) {
                                "Shizuku permission isn't granted"
                            }
                            ShizukuScriptRunner.start(script.path, dryRun, script.parentFile?.path)
                        }
                        ScriptRunMethod.ROOT ->
                            RootScriptRunner.start(script.path, dryRun, script.parentFile?.path)
                        ScriptRunMethod.APP -> {
                            val command = mutableListOf("sh", script.path)
                            if (dryRun) command += "-d"
                            ProcessBuilder(command)
                                .directory(script.parentFile)
                                .redirectErrorStream(true)
                                .start()
                        }
                    }
                    process = p
                    p.inputStream.bufferedReader().use { reader ->
                        val chunk = CharArray(1024)
                        while (true) {
                            val n = reader.read(chunk)
                            if (n < 0) break
                            append(String(chunk, 0, n))
                        }
                    }
                    append("\n[exit ${p.waitFor()}]\n")
                } catch (t: Throwable) {
                    append("\n[error] $t\n")
                } finally {
                    runningLiveData.postValue(false)
                }
            }.start()
        }

        private fun append(text: String) {
            synchronized(buffer) {
                buffer.append(ANSI.replace(text, ""))
                if (buffer.length > MAX_CHARS) {
                    // Keep only the tail (the summary lines matter most), cut at a line start.
                    buffer.delete(0, buffer.length - MAX_CHARS)
                    val nl = buffer.indexOf("\n")
                    if (nl >= 0) buffer.delete(0, nl + 1)
                }
            }
            // Coalesce updates: at most one UI refresh per UPDATE_MS, however fast the script prints.
            if (refreshPending.compareAndSet(false, true)) {
                mainHandler.postDelayed({
                    refreshPending.set(false)
                    outputLiveData.value = synchronized(buffer) { buffer.toString() }
                }, UPDATE_MS)
            }
        }

        fun stop() {
            append("\n[stopped by user]\n")
            process?.destroy()
        }

        override fun onCleared() {
            // Closing the dialog for good must not leave a script running in the background.
            process?.destroy()
        }

        companion object {
            // Strip colour escapes (the script prints them) so the TextView stays readable.
            private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
            private const val MAX_CHARS = 100_000
            private const val UPDATE_MS = 250L
        }
    }
}
