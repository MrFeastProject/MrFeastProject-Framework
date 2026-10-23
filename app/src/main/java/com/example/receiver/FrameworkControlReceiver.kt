package com.example.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.MainActivity
import com.example.service.NetworkMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FrameworkControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pm = context.packageManager
        val componentName = ComponentName(context, MainActivity::class.java)

        when (action) {
            "com.mrfeastproject.framework.CHECK_NOW" -> {
                NetworkMonitorService.checkNow(context)
            }
            "com.mrfeastproject.framework.HIDE_ICON" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pm.setComponentEnabledSetting(
                            componentName,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    } catch (_: Exception) {}
                }
            }
            "com.mrfeastproject.framework.SHOW_ICON" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pm.setComponentEnabledSetting(
                            componentName,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
