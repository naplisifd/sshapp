package com.sshapp

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.sshapp.data.Credentials
import com.sshapp.data.Host

/**
 * Owns the live session at process level, so it outlives the Activity (backgrounding, recents swipe).
 * [SessionService] keeps the process in the foreground while a session exists.
 */
object SessionHolder {
    var session by mutableStateOf<SessionController?>(null)
        private set

    fun start(context: Context, host: Host, credentials: Credentials): SessionController {
        session?.close()
        val s = SessionController(context.applicationContext, host, credentials)
        session = s
        ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        return s
    }

    fun stop(context: Context) {
        session?.close()
        session = null
        context.stopService(Intent(context, SessionService::class.java))
    }
}
