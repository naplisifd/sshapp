package com.sshapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.sshapp.ui.AppTheme
import com.sshapp.ui.HostEditScreen
import com.sshapp.ui.HostListScreen
import com.sshapp.ui.SessionScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                when (val s = vm.screen) {
                    Screen.HostList -> HostListScreen(vm)
                    is Screen.EditHost -> {
                        BackHandler { vm.screen = Screen.HostList }
                        HostEditScreen(vm, s.hostId)
                    }
                    Screen.Session -> {
                        val session = vm.session
                        if (session == null) LaunchedEffect(Unit) { vm.screen = Screen.HostList }
                        else SessionScreen(session, onBackToHosts = { vm.screen = Screen.HostList }, onDisconnect = vm::disconnect)
                    }
                }
            }
        }
    }
}
