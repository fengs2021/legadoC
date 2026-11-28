package io.legado.app.help.sound

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.io.File
import java.util.UUID

/**
 * AI 提示词库：三类提示词各自可有多条模板，每类单选启用。
 *
 * 与 Z 阅读一致：BGM 选曲、音效关键字提取、音效选择是三个独立槽位，
 * 用户可在每类里新建多条、随时切换，内置模板不可删除但可改后另存。
 */
object AiSoundPromptStore {

    enum class Kind { BGM, EXTRACT, PICK }

    data class PromptSet(
        val id: String = "",
        val kind: String = Kind.BGM.name,
        val name: String = "",
        val content: String = "",
        val builtin: Boolean = false
    ) {
        val kindValue: Kind get() = runCatching { Kind.valueOf(kind) }.getOrDefault(Kind.BGM)
    }

    private const val ASSET = "ai_sound_prompts.json"

    private val file: File get() = AloudSoundPaths.promptFile()

    @Volatile
    private var cached: MutableList<PromptSet>? = null

    /** 内置默认：来自 assets，作为三条不可删的基线模板。 */
    private fun builtin(): List<PromptSet> {
        val raw = runCatching {
            appCtx.assets.open(ASSET).use { input ->
                GSON.fromJson(input.reader(Charsets.UTF_8), AssetPrompts::class.java)
            }
        }.onFailure { AppLog.put("内置音效提示词读取失败", it) }.getOrNull() ?: AssetPrompts()
        return listOf(
            PromptSet("builtin_bgm", Kind.BGM.name, "默认提示词", raw.bgm, true),
            PromptSet("builtin_extract", Kind.EXTRACT.name, "音效关键字提取", raw.extractKeywords, true),
            PromptSet("builtin_pick", Kind.PICK.name, "音效选择", raw.pickEffects, true)
        )
    }

    private fun snapshot(): MutableList<PromptSet> {
        cached?.let { return it }
        val stored = runCatching {
            if (!file.isFile) emptyList<PromptSet>() else {
                val text = file.readText(Charsets.UTF_8)
                if (text.isBlank()) emptyList()
                else GSON.fromJson(text, StoredPrompts::class.java)?.sets.orEmpty()
            }
        }.onFailure { AppLog.put("音效提示词缓存读取失败", it) }.getOrDefault(emptyList())
        // 内置三条始终在最前，且内容以 assets 为准；用户改过的以另存的新条目存在
        val merged = (builtin() + stored.filter { !it.builtin }).toMutableList()
        cached = merged
        return merged
    }

    private fun persist() {
        val all = snapshot()
        val payload = StoredPrompts(sets = all.filter { !it.builtin })
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(GSON.toJson(payload), Charsets.UTF_8)
            if (!temp.renameTo(file)) throw IllegalStateException("cannot replace prompts.json")
        }.onFailure { AppLog.put("音效提示词保存失败", it) }
    }

    fun sets(kind: Kind): List<PromptSet> = snapshot().filter { it.kindValue == kind }

    fun all(): List<PromptSet> = snapshot().toList()

    fun byId(id: String): PromptSet? = snapshot().firstOrNull { it.id == id }

    /** 当前启用：未配置或指向已删除条目时回落到该类内置模板。 */
    fun active(kind: Kind): PromptSet {
        val key = activeKey(kind)
        val id = appCtx.getPrefString(key).orEmpty()
        val hit = snapshot().firstOrNull { it.id == id && it.kindValue == kind }
        if (hit != null) return hit
        return snapshot().firstOrNull { it.kindValue == kind && it.builtin }
            ?: PromptSet(id = "fallback", kind = kind.name, name = "默认", content = "")
    }

    fun setActive(kind: Kind, id: String) {
        appCtx.putPrefString(activeKey(kind), id)
    }

    /** 新增或更新。内置条目不允许原地覆盖，调用方应新建副本。 */
    fun save(set: PromptSet): PromptSet {
        val all = snapshot()
        val index = all.indexOfFirst { it.id == set.id }
        val normalized = set.copy(
            id = set.id.ifBlank { UUID.randomUUID().toString() },
            builtin = false
        )
        if (index >= 0) {
            if (all[index].builtin) {
                all.add(normalized)
            } else {
                all[index] = normalized
            }
        } else {
            all.add(normalized)
        }
        persist()
        return normalized
    }

    fun delete(id: String): Boolean {
        val all = snapshot()
        val target = all.firstOrNull { it.id == id } ?: return false
        if (target.builtin) return false
        all.removeAll { it.id == id }
        persist()
        Kind.entries.forEach { kind ->
            if (appCtx.getPrefString(activeKey(kind)).orEmpty() == id) {
                appCtx.putPrefString(activeKey(kind), "")
            }
        }
        return true
    }

    private fun activeKey(kind: Kind): String = when (kind) {
        Kind.BGM -> PreferKey.aloudSoundPromptBgm
        Kind.EXTRACT -> PreferKey.aloudSoundPromptExtract
        Kind.PICK -> PreferKey.aloudSoundPromptPick
    }

    private data class AssetPrompts(
        val bgm: String = "",
        val extractKeywords: String = "",
        val pickEffects: String = ""
    )

    private data class StoredPrompts(val sets: List<PromptSet>? = null)
}
