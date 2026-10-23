package com.example.manager

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.example.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LauncherIconManager {

    suspend fun setLauncherIconHidden(context: Context, hidden: Boolean): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val componentName = ComponentName(context, MainActivity::class.java)
            val newState = if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }

            try {
                pm.setComponentEnabledSetting(
                    componentName,
                    newState,
                    PackageManager.DONT_KILL_APP
                )
                val isHidden = newState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                val msg = if (isHidden) {
                    "Иконка скрыта с рабочего стола (приложение системно скрыто)"
                } else {
                    "Иконка отображается на рабочем столе"
                }
                Pair(isHidden, msg)
            } catch (e: Exception) {
                Pair(hidden, "Статус видимости изменен")
            }
        }

    suspend fun isLauncherIconHidden(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val componentName = ComponentName(context, MainActivity::class.java)
            val state = pm.getComponentEnabledSetting(componentName)
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (e: Exception) {
            false
        }
    }
}
