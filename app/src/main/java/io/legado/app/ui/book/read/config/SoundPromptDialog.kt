package io.legado.app.ui.book.read.config

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import io.legado.app.R
import io.legado.app.help.sound.AiSoundPromptStore
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi

/**
 * AI 提示词管理：BGM 选曲、音效关键字提取、音效选择三个槽位各自可多条、单选启用。
 *
 * 内置模板不可删除（删了会让整条音效链路失去提示词），可复制一份改成自己的版本再启用。
 */
object SoundPromptDialog {

    fun show(activity: FragmentActivity) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 8.dpToPx())
        }
        val dialogHolder = arrayOfNulls<AlertDialog>(1)

        fun rebuild() {
            container.removeAllViews()
            AiSoundPromptStore.Kind.entries.forEach { kind ->
                container.addView(sectionTitle(activity, kindTitle(activity, kind)))
                val active = AiSoundPromptStore.active(kind)
                val sets = AiSoundPromptStore.sets(kind)
                sets.forEach { set ->
                    container.addView(
                        row(
                            activity = activity,
                            set = set,
                            checked = set.id == active.id,
                            onPick = {
                                AiSoundPromptStore.setActive(kind, set.id)
                                rebuild()
                            },
                            onEdit = {
                                edit(activity, set) { rebuild() }
                            },
                            onDelete = {
                                if (AiSoundPromptStore.delete(set.id)) {
                                    activity.toastOnUi(R.string.aloud_prompt_deleted)
                                    rebuild()
                                } else {
                                    activity.toastOnUi(R.string.aloud_prompt_builtin_undeletable)
                                }
                            }
                        )
                    )
                }
                val addButton = Button(activity).apply {
                    text = activity.getString(R.string.aloud_prompt_new)
                    textSize = 13f
                    setTextColor(activity.accentColor)
                    setOnClickListener {
                        edit(activity, null, kind) { rebuild() }
                    }
                }
                container.addView(addButton)
            }
        }

        rebuild()
        val scroll = ScrollView(activity).apply { addView(container) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.aloud_prompt_title)
            .setView(scroll)
            .setNegativeButton(R.string.close, null)
            .create()
        dialogHolder[0] = dialog
        dialog.show()
    }

    private fun kindTitle(context: Context, kind: AiSoundPromptStore.Kind): String =
        context.getString(
            when (kind) {
                AiSoundPromptStore.Kind.BGM -> R.string.aloud_prompt_kind_bgm
                AiSoundPromptStore.Kind.EXTRACT -> R.string.aloud_prompt_kind_extract
                AiSoundPromptStore.Kind.PICK -> R.string.aloud_prompt_kind_pick
            }
        )

    private fun sectionTitle(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            includeFontPadding = false
            setTextColor(context.accentColor)
            setPadding(0, 14.dpToPx(), 0, 4.dpToPx())
        }

    private fun row(
        activity: FragmentActivity,
        set: AiSoundPromptStore.PromptSet,
        checked: Boolean,
        onPick: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 52.dpToPx()
        }
        val radio = RadioButton(activity).apply {
            isChecked = checked
            setOnClickListener { onPick() }
        }
        row.addView(radio)
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(activity).apply {
                text = set.name
                textSize = 15f
                includeFontPadding = false
                setTextColor(activity.primaryTextColor)
            }
        )
        texts.addView(
            TextView(activity).apply {
                text = set.content.replace("\n", " ").take(40)
                textSize = 12f
                includeFontPadding = false
                setTextColor(activity.secondaryTextColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        )
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(actionButton(activity, R.string.aloud_prompt_edit, onEdit))
        row.addView(actionButton(activity, R.string.aloud_prompt_delete, onDelete))
        return row
    }

    private fun actionButton(
        activity: FragmentActivity,
        textRes: Int,
        onClick: () -> Unit
    ): Button = Button(activity).apply {
        text = activity.getString(textRes)
        textSize = 12f
        setTextColor(activity.accentColor)
        minWidth = 0
        minimumWidth = 0
        setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
        setOnClickListener { onClick() }
    }

    private fun edit(
        activity: FragmentActivity,
        source: AiSoundPromptStore.PromptSet?,
        kind: AiSoundPromptStore.Kind = source?.kindValue ?: AiSoundPromptStore.Kind.BGM,
        onSaved: () -> Unit
    ) {
        val nameInput = EditText(activity).apply {
            setText(source?.name ?: kindTitle(activity, kind))
            hint = activity.getString(R.string.aloud_prompt_name);
            setSingleLine(true)
            setTextColor(activity.primaryTextColor)
        }
        val contentInput = EditText(activity).apply {
            setText(source?.content.orEmpty())
            hint = activity.getString(R.string.aloud_prompt_content_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 10
            gravity = Gravity.TOP or Gravity.START
            setTextColor(activity.primaryTextColor)
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = 16.dpToPx()
            setPadding(pad, pad / 2, pad, 0)
            addView(nameInput)
            addView(
                contentInput,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    320.dpToPx()
                ).apply { topMargin = 8.dpToPx() }
            )
        }
        val scroll = ScrollView(activity).apply { addView(wrapper) }
        AlertDialog.Builder(activity)
            .setTitle(
                activity.getString(
                    if (source == null || source.builtin) R.string.aloud_prompt_new
                    else R.string.aloud_prompt_edit
                )
            )
            .setView(scroll)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val content = contentInput.text?.toString().orEmpty()
                if (content.isBlank()) {
                    activity.toastOnUi(R.string.aloud_prompt_content_required)
                    return@setPositiveButton
                }
                val saved = AiSoundPromptStore.save(
                    AiSoundPromptStore.PromptSet(
                        id = if (source == null || source.builtin) "" else source.id,
                        kind = kind.name,
                        name = name.ifBlank { kindTitle(activity, kind) },
                        content = content
                    )
                );
                AiSoundPromptStore.setActive(kind, saved.id)
                onSaved()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
