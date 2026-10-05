package com.sshapp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

data class HistoryEntry(val command: String, val count: Int, val lastUsed: Long) {
    /** Frequency weighted by recency: a command used often last week beats one used once today, but decays over ~a month. */
    fun score(now: Long): Double {
        val days = (now - lastUsed).coerceAtLeast(0) / 86_400_000.0
        return count * (0.3 + 0.7 * exp(-days / 30.0))
    }
}

/** Per-host record of commands the user has run, used to rank suggestions. Shared by all sessions to that host. */
class CommandHistory private constructor(context: Context, private val hostId: String) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private fun load(): List<HistoryEntry> {
        val raw = prefs.getString(hostId, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryEntry(o.getString("c"), o.getInt("n"), o.getLong("t"))
        }
    }

    fun record(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || cmd.length > 500) return
        val now = System.currentTimeMillis()
        val list = _entries.value.toMutableList()
        val i = list.indexOfFirst { it.command == cmd }
        if (i >= 0) list[i] = list[i].copy(count = list[i].count + 1, lastUsed = now)
        else list.add(HistoryEntry(cmd, 1, now))
        val trimmed = list.sortedByDescending { it.score(now) }.take(MAX_ENTRIES)
        _entries.value = trimmed
        val arr = JSONArray()
        trimmed.forEach { arr.put(JSONObject().put("c", it.command).put("n", it.count).put("t", it.lastUsed)) }
        prefs.edit().putString(hostId, arr.toString()).apply()
    }

    fun remove(command: String) {
        _entries.value = _entries.value.filterNot { it.command == command }
        val arr = JSONArray()
        _entries.value.forEach { arr.put(JSONObject().put("c", it.command).put("n", it.count).put("t", it.lastUsed)) }
        prefs.edit().putString(hostId, arr.toString()).apply()
    }

    fun frequent(limit: Int): List<HistoryEntry> {
        val now = System.currentTimeMillis()
        return _entries.value.sortedByDescending { it.score(now) }.take(limit)
    }

    companion object {
        private const val MAX_ENTRIES = 300
        private val instances = HashMap<String, CommandHistory>()

        /** One instance per host, so parallel sessions don't overwrite each other's saved history. */
        fun forHost(context: Context, hostId: String): CommandHistory = synchronized(instances) {
            instances.getOrPut(hostId) { CommandHistory(context.applicationContext, hostId) }
        }
    }
}
