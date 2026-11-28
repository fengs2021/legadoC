package io.legado.app.ui.book.read.config

import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAloudSoundConfigBinding
import io.legado.app.help.sound.AloudSoundImporter
import io.legado.app.help.sound.AloudSoundStore
import io.legado.app.help.sound.BgmTrack
import io.legado.app.help.sound.BookAloudSoundConfig
import io.legado.app.help.sound.SoundPackManager
import io.legado.app.help.sound.SoundEffectRule
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 本书的朗读音效与背景音乐配置页。
 *
 * 音频一律复制进应用私有目录再引用（见 [AloudSoundImporter]），所以列表里看到的
 * 都是长期稳定的绝对路径，不依赖 SAF 授权是否仍然有效。
 */
class AloudSoundConfigActivity : BaseActivity<ActivityAloudSoundConfigBinding>() {

    override val binding by viewBinding(ActivityAloudSoundConfigBinding::inflate)

    private var bookUrl: String = ""
    private var bookName: String = ""
    private var config: BookAloudSoundConfig = BookAloudSoundConfig()
    private var previewPlayer: MediaPlayer? = null

    /** 本次选择的结果落到哪个分区 */
    private var pickingForBgm = true

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val pickBgm = pickingForBgm
            lifecycleScope.launch {
                val imported = withContext(IO) {
                    uris.mapNotNull { uri ->
                        runCatching { AloudSoundImporter.import(this@AloudSoundConfigActivity, bookUrl, uri) }
                            .getOrNull()
                    }
                }
                if (imported.isEmpty()) {
                    toastOnUi(R.string.aloud_sound_import_none)
                    return@launch
                }
                if (pickBgm) {
                    config = config.copy(bgmTracks = config.bgmTracks + imported.map { it.toBgmTrack() })
                } else {
                    config = config.copy(effectRules = config.effectRules + imported.map { it.toEffectRule() })
                }
                persist()
                render()
                toastOnUi(getString(R.string.aloud_sound_imported, imported.size))
            }
        }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra("bookUrl").orEmpty()
        bookName = intent.getStringExtra("bookName").orEmpty()
        if (bookUrl.isBlank()) {
            toastOnUi(R.string.aloud_sound_import_none)
            finish()
            return
        }
        config = AloudSoundStore.getConfig(bookUrl)
        binding.tvBookName.text = bookName.ifBlank { bookUrl }
        binding.btnAddBgm.setOnClickListener {
            pickingForBgm = true
            pickAudio.launch(arrayOf("audio/*"))
        }
        binding.btnAddEffect.setOnClickListener {
            pickingForBgm = false
            pickAudio.launch(arrayOf("audio/*"))
        }
        binding.btnAddLibrary.setOnClickListener { showLibraryDialog() }
        binding.btnClear.setOnClickListener {
            alert(R.string.aloud_sound_clear, R.string.sure_del) {
                yesButton {
                    config = BookAloudSoundConfig(bookUrl = bookUrl)
                    AloudSoundStore.clearConfig(bookUrl)
                    render()
                    toastOnUi(R.string.aloud_sound_cleared)
                }
                noButton()
            }
        }
        render()
    }

    override fun onDestroy() {
        releasePreview()
        super.onDestroy()
    }

    private fun persist() {
        val normalized = config.copy(bookUrl = bookUrl)
        config = normalized
        AloudSoundStore.saveConfig(normalized)
    }

    private fun render() {
        renderBgmList()
        renderEffectList()
    }

    private fun renderBgmList() {
        val container = binding.llBgmList
        container.removeAllViews()
        val tracks = config.bgmTracks
        if (tracks.isEmpty()) {
            container.addView(hintView(getString(R.string.aloud_sound_empty_bgm)))
            return
        }
        tracks.forEachIndexed { index, track ->
            container.addView(
                buildRow(
                    title = track.name.ifBlank { File(track.filePath).name },
                    subtitle = File(track.filePath).parent.orEmpty(),
                    onPreview = { previewAudio(track.filePath) },
                    onDelete = {
                        config = config.copy(bgmTracks = config.bgmTracks.filterIndexed { i, _ -> i != index })
                        persist()
                        render()
                    }
                )
            )
        }
    }

    private fun renderEffectList() {
        val container = binding.llEffectList
        container.removeAllViews()
        val rules = config.effectRules
        if (rules.isEmpty()) {
            container.addView(hintView(getString(R.string.aloud_sound_empty_effect)))
            return
        }
        rules.forEachIndexed { index, rule ->
            container.addView(
                buildRow(
                    title = rule.name.ifBlank {
                        if (rule.isLibrary) rule.libraryName else File(rule.filePath).name
                    },
                    subtitle = getString(R.string.aloud_sound_rule_pattern) + "：" + rule.pattern,
                    onPreview = { previewRule(rule) },
                    onDelete = {
                        config = config.copy(effectRules = config.effectRules.filterIndexed { i, _ -> i != index })
                        persist()
                        render()
                    },
                    onEdit = { editPattern(index, rule) }
                )
            )
        }
    }

    private fun editPattern(index: Int, rule: SoundEffectRule) {
        val input = EditText(this).apply {
            setText(rule.pattern)
            setSingleLine(true)
            hint = getString(R.string.aloud_sound_rule_pattern_hint)
            setTextColor(primaryTextColor)
        }
        val padding = 16.dpToPx()
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.aloud_sound_rule_pattern)
            .setView(wrapper)
            .setPositiveButton(R.string.ok) { _, _ ->
                val pattern = input.text?.toString()?.trim().orEmpty()
                if (pattern.isBlank()) return@setPositiveButton
                val next = config.effectRules.toMutableList()
                next.getOrNull(index)?.let { current ->
                    next[index] = current.copy(pattern = pattern)
                    config = config.copy(effectRules = next)
                    persist()
                    render()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildRow(
        title: String,
        subtitle: String,
        onPreview: () -> Unit,
        onDelete: () -> Unit,
        onEdit: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 52.dpToPx()
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(
            TextView(this).apply {
                text = title
                textSize = 15f
                includeFontPadding = false
                setTextColor(primaryTextColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
        )
        texts.addView(
            TextView(this).apply {
                text = subtitle
                textSize = 12f
                includeFontPadding = false
                setTextColor(secondaryTextColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
        )
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            Button(this).apply {
                text = getString(R.string.aloud_sound_preview)
                textSize = 13f
                setTextColor(accentColor)
                minWidth = 0
                minimumWidth = 0
                setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
                setOnClickListener { onPreview() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        if (onEdit != null) {
            row.addView(
                Button(this).apply {
                    text = getString(R.string.aloud_sound_rule_pattern)
                    textSize = 13f
                    setTextColor(accentColor)
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
                    setOnClickListener { onEdit() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        row.addView(
            Button(this).apply {
                text = getString(R.string.aloud_sound_delete)
                textSize = 13f
                setTextColor(accentColor)
                minWidth = 0
                minimumWidth = 0
                setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
                setOnClickListener { onDelete() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return row
    }

    private fun hintView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        includeFontPadding = false
        setTextColor(secondaryTextColor)
        setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
        isVisible = true
    }

    private fun previewAudio(path: String) {
        releasePreview()
        val file = File(path)
        if (!file.isFile) {
            toastOnUi(getString(R.string.aloud_sound_file_missing, path))
            return
        }
        runCatching {
            previewPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
                setOnCompletionListener { releasePreview() }
            }
        }.onFailure {
            toastOnUi(getString(R.string.aloud_sound_file_missing, path))
        }
    }

    private fun releasePreview() {
        runCatching {
            previewPlayer?.let { player ->
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
        previewPlayer = null
    }

    private fun previewRule(rule: SoundEffectRule) {
        if (rule.isLibrary) {
            val file = SoundPackManager.findByName(rule.libraryName)
            if (file == null) {
                toastOnUi(R.string.aloud_sound_import_none)
                return
            }
            previewAudio(file.absolutePath)
            return
        }
        previewAudio(rule.filePath)
    }

    private fun previewUrl(url: String) {
        releasePreview()
        lifecycleScope.launch {
            val prepared = withContext(IO) {
                runCatching {
                    MediaPlayer().apply {
                        setDataSource(url)
                        prepare()
                    }
                }.getOrNull()
            }
            if (prepared == null) {
                toastOnUi(R.string.aloud_sound_import_none)
                return@launch
            }
            previewPlayer = prepared.apply {
                setOnCompletionListener { releasePreview() }
                start()
            }
        }
    }

    /**
     * 在线音效库：搜索内置索引，条目可直接试听（流式），点「添加」写进本书规则。
     * 规则只存库内文件名，音频在朗读挂载时按需下载缓存。
     */
    /**
     * 从已下载的音效库挑选：整包下载后在这里按名字搜索，选中即生成本书手动规则。
     *
     * AI 匹配是主路径；这里用于局部覆盖，比如某本书固定要某个脚步声。
     */
    private fun showLibraryDialog() {
        val all = downloadedLibraryEntries()
        if (all.isEmpty()) {
            toastOnUi(R.string.aloud_sound_import_none)
            return
        }
        var entries = all.take(LIBRARY_PAGE_LIMIT)
        val listAdapter = object : RecyclerView.Adapter<LibraryHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryHolder {
                val row = LinearLayout(this@AloudSoundConfigActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = 52.dpToPx()
                }
                val nameView = TextView(this@AloudSoundConfigActivity).apply {
                    textSize = 15f
                    includeFontPadding = false
                    setTextColor(primaryTextColor)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                row.addView(
                    nameView,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                val previewButton = Button(this@AloudSoundConfigActivity).apply {
                    text = getString(R.string.aloud_sound_preview)
                    textSize = 13f
                    setTextColor(accentColor)
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
                }
                row.addView(
                    previewButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                val addButton = Button(this@AloudSoundConfigActivity).apply {
                    textSize = 13f
                    setTextColor(accentColor)
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
                }
                row.addView(
                    addButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                return LibraryHolder(row, nameView, previewButton, addButton)
            }

            override fun getItemCount(): Int = entries.size

            override fun onBindViewHolder(holder: LibraryHolder, position: Int) {
                val entry = entries[position]
                holder.name.text = entry.name
                val added = config.effectRules.any { it.filePath == entry.file.absolutePath }
                holder.preview.setOnClickListener { previewAudio(entry.file.absolutePath) }
                holder.add.text = getString(
                    if (added) R.string.aloud_sound_library_added else R.string.aloud_sound_library_add
                )
                holder.add.isEnabled = !added
                holder.add.setOnClickListener {
                    if (config.effectRules.any { it.filePath == entry.file.absolutePath }) {
                        return@setOnClickListener
                    }
                    val rule = SoundEffectRule(
                        id = UUID.randomUUID().toString(),
                        name = entry.name,
                        pattern = AloudSoundImporter.defaultKeywords(entry.name),
                        isRegex = false,
                        filePath = entry.file.absolutePath,
                        volume = 1f,
                        cooldownMs = 1500L
                    )
                    config = config.copy(effectRules = config.effectRules + rule)
                    persist()
                    render()
                    notifyItemChanged(position)
                }
            }
        }
        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AloudSoundConfigActivity)
            adapter = listAdapter
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                360.dpToPx()
            )
        }
        val searchView = SearchView(this).apply {
            queryHint = getString(R.string.aloud_sound_library_search)
            setIconifiedByDefault(false)
            isIconified = false
            isSubmitButtonEnabled = false
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = true
                override fun onQueryTextChange(newText: String?): Boolean {
                    val key = newText.orEmpty().trim()
                    entries = if (key.isBlank()) {
                        all.take(LIBRARY_PAGE_LIMIT)
                    } else {
                        all.filter { it.name.contains(key, ignoreCase = true) }
                            .take(LIBRARY_PAGE_LIMIT)
                    }
                    listAdapter.notifyDataSetChanged()
                    return true
                }
            })
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dpToPx(), 14.dpToPx(), 14.dpToPx(), 12.dpToPx())
            addView(
                searchView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    42.dpToPx()
                )
            )
            addView(
                recyclerView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    360.dpToPx()
                ).apply { topMargin = 8.dpToPx() }
            )
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.aloud_sound_library_title))
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private data class LibEntry(val name: String, val file: File)

    /** 已下载到本地的音效库音频（跨包合并，供手动挑选）。 */
    private fun downloadedLibraryEntries(): List<LibEntry> =
        SoundPackManager.packs().flatMap { pack ->
            pack.files.mapNotNull { relative ->
                val file = SoundPackManager.localFile(pack, relative)
                if (file.isFile && file.length() > 0) {
                    LibEntry(relative.substringAfterLast('/').substringBeforeLast('.'), file)
                } else {
                    null
                }
            }
        }

    private class LibraryHolder(
        root: LinearLayout,
        val name: TextView,
        val preview: Button,
        val add: Button
    ) : RecyclerView.ViewHolder(root)

    private fun File.toBgmTrack(): BgmTrack = BgmTrack(
        id = UUID.randomUUID().toString(),
        name = name,
        filePath = absolutePath,
        volume = 1f,
        loop = false
    )

    private fun File.toEffectRule(): SoundEffectRule = SoundEffectRule(
        id = UUID.randomUUID().toString(),
        name = name,
        pattern = AloudSoundImporter.defaultKeywords(name),
        isRegex = false,
        filePath = absolutePath,
        volume = 1f,
        cooldownMs = 1500L
    )

    private companion object {
        /** 音效库单页最多列出多少条：937 条全量进 RecyclerView 也没问题，但搜索无关键词时少些更利落 */
        const val LIBRARY_PAGE_LIMIT = 300
    }
}
