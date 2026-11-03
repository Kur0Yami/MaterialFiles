/*
 * Filter options + batch rename (fork patch).
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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

class BatchRenameDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    private lateinit var patternEdit: EditText
    private lateinit var findEdit: EditText
    private lateinit var replaceEdit: EditText
    private lateinit var regexCheck: CheckBox
    private lateinit var summaryText: TextView
    private lateinit var previewText: TextView

    private var newNames: List<String> = emptyList()
    private var canRename = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (20 * density).toInt()
        patternEdit = EditText(context).apply {
            setSingleLine()
            setText("{P}{S}")
            hint = getString(R.string.batch_rename_pattern_hint)
        }
        val helpText = TextView(context).apply {
            setText(R.string.batch_rename_help)
            textSize = 12f
            alpha = 0.7f
        }
        findEdit = EditText(context).apply {
            setSingleLine()
            hint = getString(R.string.batch_rename_find_hint)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        replaceEdit = EditText(context).apply {
            setSingleLine()
            hint = getString(R.string.batch_rename_replace_hint)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val findRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(findEdit)
            addView(replaceEdit)
        }
        regexCheck = CheckBox(context).apply { setText(R.string.batch_rename_regex) }
        summaryText = TextView(context).apply {
            textSize = 13f
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        }
        previewText = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(patternEdit)
            addView(helpText)
            addView(findRow)
            addView(regexCheck)
            addView(summaryText)
            addView(previewText)
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                updatePreview()
            }
        }
        patternEdit.addTextChangedListener(watcher)
        findEdit.addTextChangedListener(watcher)
        replaceEdit.addTextChangedListener(watcher)
        regexCheck.setOnCheckedChangeListener { _, _ -> updatePreview() }
        updatePreview()
        return MaterialAlertDialogBuilder(context, theme)
            .setTitle(getString(R.string.batch_rename_title, args.files.size))
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton(R.string.batch_rename_do, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        // Keep the dialog open until the renames are valid.
        val button = (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
        button.isEnabled = canRename
        button.setOnClickListener {
            if (canRename) {
                listener.batchRename(args.files, newNames)
                dismiss()
            }
        }
    }

    private fun updatePreview() {
        val names = args.files.map { it.name }
        val result = BatchRenamer.rename(
            names, patternEdit.text.toString(), findEdit.text.toString(),
            replaceEdit.text.toString(), regexCheck.isChecked
        )
        newNames = result.names
        val conflicts = BatchRenamer.findConflicts(names, newNames, args.existingNames)
        var changed = 0
        var bad = 0
        val builder = StringBuilder()
        for (index in names.indices) {
            val conflict = conflicts[index]
            val same = newNames[index] == names[index]
            if (!same) {
                changed++
            }
            if (conflict != null) {
                bad++
            }
            if (index < MAX_PREVIEW) {
                builder.append(names[index])
                if (same) {
                    builder.append("  =\n")
                } else {
                    builder.append('\n').append(if (conflict != null) "x " else "-> ")
                        .append(newNames[index])
                    if (conflict != null) {
                        builder.append("  (").append(conflict).append(')')
                    }
                    builder.append('\n')
                }
            }
        }
        if (names.size > MAX_PREVIEW) {
            builder.append("... +").append(names.size - MAX_PREVIEW)
        }
        summaryText.text = result.error
            ?: getString(R.string.batch_rename_summary, changed, bad, names.size - changed)
        previewText.text = builder
        canRename = result.error == null && changed > 0 && bad == 0
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = canRename
    }

    companion object {
        private const val MAX_PREVIEW = 200

        fun show(files: List<FileItem>, existingNames: List<String>, fragment: Fragment) {
            BatchRenameDialogFragment()
                .putArgs(Args(files, ArrayList(existingNames)))
                .show(fragment)
        }
    }

    @Parcelize
    class Args(val files: List<FileItem>, val existingNames: ArrayList<String>) : ParcelableArgs

    interface Listener {
        fun batchRename(files: List<FileItem>, newNames: List<String>)
    }
}
