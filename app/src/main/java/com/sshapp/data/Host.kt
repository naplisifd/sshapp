package com.sshapp.data

import org.json.JSONObject
import java.util.UUID

enum class AuthType { PASSWORD, KEY }

data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PASSWORD,
    val lastConnected: Long = 0L,
) {
    val label: String get() = name.ifBlank { "$username@$hostname" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("hostname", hostname)
        .put("port", port)
        .put("username", username)
        .put("authType", authType.name)
        .put("lastConnected", lastConnected)

    companion object {
        fun fromJson(o: JSONObject) = Host(
            id = o.getString("id"),
            name = o.optString("name"),
            hostname = o.getString("hostname"),
            port = o.optInt("port", 22),
            username = o.getString("username"),
            authType = runCatching { AuthType.valueOf(o.optString("authType")) }.getOrDefault(AuthType.PASSWORD),
            lastConnected = o.optLong("lastConnected"),
        )
    }
}

/** Credentials for a host. Stored encrypted, separately from [Host]. */
data class Credentials(
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
)
