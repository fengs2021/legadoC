package io.legado.app.ui.book.read.config

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.sound.SoundPackManager
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.surface.SurfaceStyles
import io.legado.app.utils.SurfaceBackdrop
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「下载音效和音乐」：整包下载音效库与配乐库到本地。
 *
 * 与 Z 阅读一致：素材先落地，朗读时只查本地文件；下载可中断重来，
 * 已存在的文件自动跳过，所以重复点击等价于续传。
 */
object SoundPackDialog {

    fun show(activity: FragmentActivity) {
        val packs = SoundPackManager.packs()
        if (packs.isEmpty()) return
        val rows = mutableListOf<Row>()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 8.dpToPx())
            SurfaceBackdrop.installStatic(this, SurfaceStyles.dialog(activity))
        }
        val downloading = AtomicBoolean(false)
        // 状态统计要遍历上万个文件，必须放后台：先渲染可点的行，再回填进度
        packs.forEach { pack ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 56.dpToPx()
            }
            val checkBox = CheckBox(activity).apply {
                isChecked = true
            }
            row.addView(checkBox)
            val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(activity).apply {
                text = "${pack.name}（${pack.total}）"
                textSize = 15f
                includeFontPadding = false
                setTextColor(activity.primaryTextColor)
            }
            val status = TextView(activity).apply {
                textSize = 12f
                includeFontPadding = false
                setTextColor(activity.secondaryTextColor)
                text = activity.getString(R.string.aloud_pack_state_loading)
            }
            val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                layoutParams = LinearLayout.LayoutParams(
                    140.dpToPx(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            texts.addView(title)
            texts.addView(status)
            row.addView(
                texts,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(progress)
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            rows += Row(pack.id, pack.name, checkBox, status, progress)
        }
        activity.lifecycleScope.launch(Dispatchers.IO) {
            rows.forEach { row ->
                val state = runCatching { SoundPackManager.state(row.packId) }.getOrNull()
                withContext(Dispatchers.Main) {
                    row.checkBox.isChecked = state?.finished != true
                    row.status.text = renderState(activity, state?.downloaded ?: 0, state?.total ?: 0)
                    row.progress.progress = state?.progressPercent ?: 0
                }
            }
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.aloud_pack_download_title)
            .setView(container)
            .setPositiveButton(R.string.aloud_pack_download_action, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selected = rows.filter { it.checkBox.isChecked && !it.finished() }
            if (selected.isEmpty()) {
                activity.toastOnUi(R.string.aloud_pack_none_selected)
                return@setOnClickListener
            }
            if (!downloading.compareAndSet(false, true)) return@setOnClickListener
            activity.lifecycleScope.launch {
                selected.forEach { row ->
                    row.checkBox.isEnabled = false
                    SoundPackManager.download(row.packId) { done, total ->
                        activity.runOnUiThread {
                            row.status.text = activity.getString(
                                R.string.aloud_pack_download_running,
                                row.packName,
                                done,
                                total
                            )
                            row.progress.progress =
                                if (total <= 0) 0 else (done * 100 / total)
                        }
                    }
                    row.checkBox.isEnabled = true
                    val state = SoundPackManager.state(row.packId)
                    row.status.text = renderState(activity, state?.downloaded ?: 0, state?.total ?: 0)
                    row.progress.progress = state?.progressPercent ?: 0
                }
                downloading.set(false)
                activity.toastOnUi(
                    activity.getString(
                        R.string.aloud_pack_download_finished,
                        selected.joinToString("、") { it.packName }
                    )
                )
            }
        }
    }

    private fun renderState(context: Context, downloaded: Int, total: Int): String = when {
        downloaded <= 0 -> context.getString(R.string.aloud_pack_state_waiting)
        downloaded >= total -> context.getString(R.string.aloud_pack_state_done, total)
        else -> context.getString(R.string.aloud_pack_state_partial, downloaded, total)
    }

    private class Row(
        val packId: String,
        val packName: String,
        val checkBox: CheckBox,
        val status: TextView,
        val progress: ProgressBar
    ) {
        fun finished(): Boolean {
            val state = SoundPackManager.state(packId) ?: return false
            return state.finished
        }
    }
}
