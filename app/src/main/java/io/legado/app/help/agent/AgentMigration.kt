package io.legado.app.help.agent

import io.legado.app.data.agent.AgentPayloadStore
import org.json.JSONObject
import splitties.init.appCtx

/**
 * 破坏性升级：AI 配置只看 AgentConfig.SCHEMA_VERSION，对不上就删了重建。
 * 不校验旧数据、不迁移旧配置，自用应用不保留历史数据。
 */
object AgentMigration {
    @Volatile private var initialized = false

    /**
     * 判定用的版本号存在 SharedPreferences 而不是 AgentStore 文档：
     * 之前只看 DB 里的 migration/v1 标记，一旦该标记没写成功（或事务失败），
     * 每次启动都会判为「版本不符」→ 反复清空 AI 配置。
     * 现在以 prefs 里的已应用版本为准：只有 SCHEMA_VERSION 真正变化才重置一次。
     */
    private const val PREF_NAME = "agent_schema_pref"
    private const val PREF_KEY = "applied_schema_version"

    private fun prefs() = appCtx.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)

    private fun appliedVersion(): Int = prefs().getInt(PREF_KEY, -1)

    private fun markApplied() {
        prefs().edit().putInt(PREF_KEY, AgentConfig.SCHEMA_VERSION).apply()
    }

    @Synchronized
    fun ensure() {
        if (initialized) return
        val marker = AgentStore.get("migration", "v1")
        val dbMatches = marker != null &&
            marker.optInt("schemaVersion", -1) == AgentConfig.SCHEMA_VERSION &&
            marker.optBoolean("complete", false)
        // 用户明确要求：禁止任何「装新包就清空 AI 设置」的行为。
        // 版本号无论是否变化都不再 resetAll，只补写标记，保证数据永不被删。
        if (appliedVersion() != AgentConfig.SCHEMA_VERSION) {
            markApplied()
        }
        if (marker == null) {
            // 版本没变但 DB 标记丢失：只补写标记，绝不再清数据
            AgentStore.put("migration", "v1", JSONObject().put("schemaVersion", AgentConfig.SCHEMA_VERSION)
                .put("complete", true).put("completedAt", System.currentTimeMillis()))
        }
        // 数据永不删除；无论版本与标记状态，只做「上次进程中断」的状态复位。
        if (true) {
            AgentStore.database.runInTransaction {
                AgentStore.dao.unfinished().forEach { run ->
                    val reason = "应用进程在任务完成前终止；未完成写操作结果未知，未自动重放"
                    AgentStore.dao.state(run.id, "interrupted", reason)
                    AgentStore.event(run.id, "interrupted", JSONObject().put("reason", reason))
                }
                AgentStore.dao.documents("mcp.server.status").forEach { document ->
                    if (JSONObject(document.json).optString("state") == "running") AgentStore.put("mcp.server.status", document.key,
                        JSONObject().put("state", "interrupted").put("reason", "上次进程已终止，等待监听服务重新启动"))
                }
            }
        }
        initialized = true
    }

    fun resetAfterRestore() { initialized = false }

    private fun resetAll() {
        io.legado.app.service.AgentMcpService.stopListeners()
        AgentRuntime.stopAll("Agent 配置版本升级，已重置重建")
        AgentStore.database.runInTransaction {
            val dao = AgentStore.dao
            dao.clearDocuments()
            dao.clearRuns()
            dao.clearEvents()
            dao.clearMessages()
            dao.clearAllVectors()
            AgentPayloadStore.clear()
            val defaults = AgentConfig.defaults()
            AgentStore.put("config", "agent", JSONObject().put("enabled", defaults.getBoolean("enabled"))
                .put("mode", defaults.getString("mode")).put("schemaVersion", AgentConfig.SCHEMA_VERSION))
            AgentStore.put("config", "modules", defaults.getJSONObject("modules"))
            AgentStore.put("module.settings", "memory", defaults.getJSONObject("memory"))
            AgentStore.put("module.settings", "web", JSONObject()
                .put("apiKey", "")
                .put("baseUrl", "https://api.tavily.com/search")
                .put("searchDepth", "basic")
                .put("topic", "general")
                .put("maxResults", 5))
            AgentStore.put("config", "ui", JSONObject().put("enterToSend", true).put("showToolSummary", false))
            AgentStore.put("migration", "v1", JSONObject().put("schemaVersion", AgentConfig.SCHEMA_VERSION)
                .put("complete", true).put("completedAt", System.currentTimeMillis()))
        }
        AgentPlugins.root.deleteRecursively()
        AgentPlugins.installBuiltin()
    }
}
