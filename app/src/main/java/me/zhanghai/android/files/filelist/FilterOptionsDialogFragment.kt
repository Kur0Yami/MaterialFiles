/*
 * Filter options + batch rename (fork patch).
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

class FilterOptionsDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    private lateinit var subfoldersCheck: CheckBox
    private lateinit var regexCheck: CheckBox
    private lateinit var minEdit: EditText
    private lateinit var minUnit: Spinner
    private lateinit var maxEdit: EditText
    private lateinit var maxUnit: Spinner

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (20 * density).toInt()
        subfoldersCheck = CheckBox(context).apply {
            setText(R.string.file_list_filter_subfolders)
            isChecked = args.subfolders
        }
        regexCheck = CheckBox(context).apply {
            setText(R.string.file_list_filter_regex)
            isChecked = args.regex
        }
        val (minRow, minE, minU) = sizeRow(R.string.file_list_filter_size_min, args.minSize)
        val (maxRow, maxE, maxU) = sizeRow(R.string.file_list_filter_size_max, args.maxSize)
        minEdit = minE
        minUnit = minU
        maxEdit = maxE
        maxUnit = maxU
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(subfoldersCheck)
            addView(regexCheck)
            addView(minRow)
            addView(maxRow)
        }
        return MaterialAlertDialogBuilder(context, theme)
            .setTitle(R.string.file_list_filter_options)
            .setView(root)
            .setPositiveButton(R.string.file_list_filter_apply) { _, _ ->
                listener.onFilterOptionsChanged(
                    regexCheck.isChecked, subfoldersCheck.isChecked,
                    readSize(minEdit, minUnit), readSize(maxEdit, maxUnit)
                )
            }
            .setNeutralButton(R.string.file_list_filter_reset) { _, _ ->
                listener.onFilterOptionsChanged(false, false, -1, -1)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun sizeRow(hintRes: Int, bytes: Long): Triple<LinearLayout, EditText, Spinner> {
        val context = requireContext()
        val (text, unitIndex) = splitSize(bytes)
        val edit = EditText(context).apply {
            hint = getString(hintRes)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(text)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, UNITS)
            setSelection(unitIndex)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(edit)
            addView(spinner)
        }
        return Triple(row, edit, spinner)
    }

    private fun readSize(edit: EditText, unit: Spinner): Long {
        val value = edit.text.toString().trim().toDoubleOrNull() ?: return -1
        if (value < 0) {
            return -1
        }
        return (value * (1L shl (10 * unit.selectedItemPosition))).toLong()
    }

    private fun splitSize(bytes: Long): Pair<String, Int> {
        if (bytes < 0) {
            return "" to 2
        }
        var unit = 0
        while (unit < UNITS.size - 1 && bytes != 0L && bytes % (1L shl (10 * (unit + 1))) == 0L) {
            unit++
        }
        return (bytes / (1L shl (10 * unit))).toString() to unit
    }

    companion object {
        private val UNITS = arrayOf("B", "KB", "MB", "GB")

        fun show(filter: FileFilterSpec, fragment: Fragment) {
            FilterOptionsDialogFragment()
                .putArgs(Args(filter.regex, filter.subfolders, filter.minSize, filter.maxSize))
                .show(fragment)
        }
    }

    @Parcelize
    class Args(
        val regex: Boolean,
        val subfolders: Boolean,
        val minSize: Long,
        val maxSize: Long
    ) : ParcelableArgs

    interface Listener {
        fun onFilterOptionsChanged(
            regex: Boolean,
            subfolders: Boolean,
            minSize: Long,
            maxSize: Long
        )
    }
}
