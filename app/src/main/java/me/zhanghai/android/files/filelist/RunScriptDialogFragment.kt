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
        val items = arrayOf(
            getString(R.string.file_run_script_run),
            getString(R.string.file_run_script_dry),
            getString(R.string.file_run_script_open_as_text)
        )
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(args.file.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> listener.runScript(args.file, false)
                    1 -> listener.runScript(args.file, true)
                    else -> listener.openScriptAsText(args.file)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        fun show(file: FileItem, fragment: Fragment) {
            RunScriptDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }

    @Parcelize
    class Args(val file: FileItem) : ParcelableArgs

    interface Listener {
        fun runScript(file: FileItem, dryRun: Boolean)
        fun openScriptAsText(file: FileItem)
    }
}
