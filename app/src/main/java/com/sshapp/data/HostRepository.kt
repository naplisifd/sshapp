package com.sshapp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class HostRepository(context: Context) {
    private val prefs = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)
    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<Host>> = _hosts.asStateFlow()

    private fun load(): List<Host> {
        val raw = prefs.getString("hosts", null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { runCatching { Host.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    private fun persist(list: List<Host>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("hosts", arr.toString()).apply()
        _hosts.value = list
    }

    fun get(id: String): Host? = _hosts.value.firstOrNull { it.id == id }

    fun upsert(host: Host) {
        val list = _hosts.value.toMutableList()
        val i = list.indexOfFirst { it.id == host.id }
        if (i >= 0) list[i] = host else list.add(host)
        persist(list)
    }

    fun delete(id: String) = persist(_hosts.value.filterNot { it.id == id })

    fun markConnected(id: String) {
        get(id)?.let { upsert(it.copy(lastConnected = System.currentTimeMillis())) }
    }
}
