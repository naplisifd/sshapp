package com.sshapp

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import com.sshapp.data.Credentials
import com.sshapp.data.Host

/**
 * Owns the live sessions at process level, so they outlive the Activity (backgrounding, recents swipe).
 * [SessionService] keeps the process in the foreground while any session exists.
 */
object SessionHolder {
    /** Open sessions in the order they were started. Several may point at the same host. */
    val sessions = mutableStateListOf<SessionController>()

    fun find(id: String): SessionController? = sessions.firstOrNull { it.id == id }

    fun start(context: Context, host: Host, credentials: Credentials): SessionController {
        val number = (sessions.filter { it.host.id == host.id }.maxOfOrNull { it.number } ?: 0) + 1
        val s = SessionController(context.applicationContext, host, credentials, number)
        sessions += s
        ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        return s
    }

    fun stop(context: Context, session: SessionController) {
        session.close()
        sessions.remove(session)
        if (sessions.isEmpty()) context.stopService(Intent(context, SessionService::class.java))
    }

    fun stopAll(context: Context) {
        sessions.forEach { it.close() }
        sessions.clear()
        context.stopService(Intent(context, SessionService::class.java))
    }
}
