package io.legado.app.plugin.jread

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R

/**
 * 「Z阅读插件引擎」管理页：插件列表、导入、启停、变量编辑、音色刷新。
 *
 * 一个引擎承载全部插件：导入的插件包统一进 [JReadPluginStore]，
 * 音色目录 [JReadVoiceDirectory] 随之整体刷新，AI 选角立即看到新音色。
 */
class JReadPluginManageActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolbar.addView(Button(this).apply {
            text = "导入插件"
            setOnClickListener { importFromClipboard() }
        })
        toolbar.addView(Button(this).apply {
            text = "刷新音色"
            setOnClickListener {
                JReadVoiceDirectory.refresh()
                toast("已刷新音色目录")
                render()
            }
        })
        root.addView(toolbar)

        root.addView(TextView(this).apply {
            text = "点击切换启用，长按编辑变量或删除。导入格式：Z·阅读 的插件包 JSON 或单个插件 JSON。"
            setPadding(0, 16, 0, 16)
        })

        listView = ListView(this)
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val plugin = JReadPluginStore.all().getOrNull(position) ?: return@setOnItemClickListener
            JReadPluginStore.setEnabled(plugin.pluginId, !plugin.enabled)
            JReadRuntime.invalidate(plugin.pluginId)
            JReadVoiceDirectory.refresh()
            render()
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val plugin = JReadPluginStore.all().getOrNull(position) ?: return@setOnItemLongClickListener false
            showActions(plugin)
            true
        }
        render()
    }

    private fun render() {
        val plugins = JReadPluginStore.all()
        adapter.clear()
        plugins.forEach { plugin ->
            val voiceCount = runCatching { JReadRuntime.voices(plugin).size }.getOrDefault(0)
            val state = if (plugin.enabled) "启用" else "停用"
            adapter.add("${plugin.name}  v${plugin.version}\n$state · $voiceCount 个音色 · ${plugin.pluginId}")
        }
        adapter.notifyDataSetChanged()
        if (plugins.isEmpty()) {
            adapter.add("尚未导入插件：点「导入插件」粘贴 Z·阅读 插件包 JSON")
            adapter.notifyDataSetChanged()
        }
    }

    private fun showActions(plugin: JReadPlugin) {
        val items = arrayOf("编辑变量", "重载插件", "删除")
        AlertDialog.Builder(this)
            .setTitle(plugin.name)
            .setItems(items) { _, index ->
                when (index) {
                    0 -> editVars(plugin)
                    1 -> {
                        JReadRuntime.invalidate(plugin.pluginId)
                        JReadVoiceDirectory.refresh()
                        render()
                        toast("已重载")
                    }
                    else -> confirmDelete(plugin)
                }
            }
            .show()
    }

    private fun editVars(plugin: JReadPlugin) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val fields = LinkedHashMap<String, EditText>()
        val keys = LinkedHashSet<String>()
        keys += plugin.defVars.keys
        keys += plugin.userVars.keys
        if (keys.isEmpty()) {
            container.addView(TextView(this).apply { text = "该插件没有声明变量" })
        }
        keys.forEach { key ->
            val spec = plugin.defVars[key]
            container.addView(TextView(this).apply {
                text = buildString {
                    append(spec?.label ?: key)
                    spec?.hint?.takeIf { it.isNotBlank() }?.let { append("（").append(it).append("）") }
                    spec?.loginUrl?.takeIf { it.isNotBlank() }?.let { append("\n登录页：").append(it) }
                }
            })
            val edit = EditText(this).apply {
                setText(plugin.userVars[key].orEmpty())
                hint = key
            }
            container.addView(edit)
            fields[key] = edit
        }
        val scroll = android.widget.ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this)
            .setTitle("设置变量")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val values = fields.mapValues { it.value.text.toString() }
                JReadPluginStore.setUserVars(plugin.pluginId, values)
                JReadRuntime.invalidate(plugin.pluginId)
                JReadVoiceDirectory.refresh()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(plugin: JReadPlugin) {
        AlertDialog.Builder(this)
            .setTitle("删除插件")
            .setMessage("确定删除「${plugin.name}」？删除后其音色立即从选角目录移除。")
            .setPositiveButton("删除") { _, _ ->
                JReadPluginStore.remove(plugin.pluginId)
                JReadRuntime.invalidate(plugin.pluginId)
                JReadVoiceDirectory.refresh()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            toast("剪贴板为空：先把插件包 JSON 复制进来")
            return
        }
        val result = JReadPluginStore.import(text)
        JReadVoiceDirectory.refresh()
        render()
        toast(result.message)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
