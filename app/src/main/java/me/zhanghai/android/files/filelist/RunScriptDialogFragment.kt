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
        // Normal "run as this app" only works on real Linux paths; SAF paths (microSD on old
        // Android) can only be run through Shizuku, via the translated raw path.
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (args.isLinuxPath) {
            labels += getString(R.string.file_run_script_run)
            actions += { listener.runScript(args.file, args.rawPath, false, false) }
            labels += getString(R.string.file_run_script_dry)
            actions += { listener.runScript(args.file, args.rawPath, true, false) }
        }
        labels += getString(R.string.file_run_script_run_shizuku)
        actions += { listener.runScript(args.file, args.rawPath, false, true) }
        labels += getString(R.string.file_run_script_dry_shizuku)
        actions += { listener.runScript(args.file, args.rawPath, true, true) }
        labels += getString(R.string.file_run_script_open_as_text)
        actions += { listener.openScriptAsText(args.file) }
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(args.file.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
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
        fun runScript(file: FileItem, rawPath: String, dryRun: Boolean, viaShizuku: Boolean)
        fun openScriptAsText(file: FileItem)
    }
}
