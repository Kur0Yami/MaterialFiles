/*
 * Run-script support (fork patch).
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

class RunScriptDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun addRun(labelRes: Int, dryRun: Boolean, method: ScriptRunMethod) {
            labels += getString(labelRes)
            val run = { listener.runScript(args.file, args.rawPath, dryRun, method) }
            actions += if (method == ScriptRunMethod.ROOT) {
                { confirmRoot(dryRun, run) }
            } else {
                run
            }
        }
        // Normal "run as this app" only works on real Linux paths; SAF paths (microSD on old
        // Android) can only be run through Shizuku, via the translated raw path.
        if (args.isLinuxPath) {
            addRun(R.string.file_run_script_run, false, ScriptRunMethod.APP)
            addRun(R.string.file_run_script_dry, true, ScriptRunMethod.APP)
        }
        addRun(R.string.file_run_script_run_root, false, ScriptRunMethod.ROOT)
        addRun(R.string.file_run_script_dry_root, true, ScriptRunMethod.ROOT)
        addRun(R.string.file_run_script_run_shizuku, false, ScriptRunMethod.SHIZUKU)
        addRun(R.string.file_run_script_dry_shizuku, true, ScriptRunMethod.SHIZUKU)
        labels += getString(R.string.file_run_script_open_as_text)
        actions += { listener.openScriptAsText(args.file) }
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(args.file.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun confirmRoot(dryRun: Boolean, run: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.file_run_script_root_confirm_title)
            .setMessage(
                if (dryRun) R.string.file_run_script_root_confirm_message_dry
                else R.string.file_run_script_root_confirm_message
            )
            .setPositiveButton(R.string.file_run_script_root_confirm_run) { _, _ -> run() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        fun show(file: FileItem, rawPath: String, isLinuxPath: Boolean, fragment: Fragment) {
            RunScriptDialogFragment().putArgs(Args(file, rawPath, isLinuxPath)).show(fragment)
        }
    }

    @Parcelize
    class Args(val file: FileItem, val rawPath: String, val isLinuxPath: Boolean) :
        ParcelableArgs

    interface Listener {
        fun runScript(file: FileItem, rawPath: String, dryRun: Boolean, method: ScriptRunMethod)
        fun openScriptAsText(file: FileItem)
    }
}
