package com.example.data

import com.example.model.LogEntry
import com.example.model.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NetworkStatusRepository {
    private val _status = MutableStateFlow(NetworkStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun updateChecking(isChecking: Boolean) {
        _status.update { it.copy(isChecking = isChecking) }
    }

    fun updateNetworkState(connected: Boolean) {
        val changed = _status.value.isNetworkConnected != connected
        _status.update { it.copy(isNetworkConnected = connected) }
        if (changed) {
            val time = timeFormat.format(Date())
            val msg = if (connected) {
                "[$time] Подключение к сети установлено"
            } else {
                "[$time] Сеть недоступна (нет интернет-соединения)"
            }
            addLog(msg, isError = !connected)
        }
    }

    fun updateServerResult(
        reachable: Boolean,
        responseCode: Int,
        latencyMs: Long,
        errorMessage: String? = null
    ) {
        _status.update { current ->
            current.copy(
                isServerReachable = reachable,
                responseCode = responseCode,
                latencyMs = latencyMs,
                lastCheckTime = System.currentTimeMillis(),
                checkCount = current.checkCount + 1,
                failureCount = if (reachable) current.failureCount else current.failureCount + 1,
                errorMessage = errorMessage,
                isChecking = false
            )
        }

        val time = timeFormat.format(Date())
        val logMsg = if (reachable) {
            "[$time] Сервер Canada #1 ДОСТУПЕН (HTTP $responseCode, ${latencyMs}ms)"
        } else {
            "[$time] Сервер НЕ ДОСТУПЕН: ${errorMessage ?: "код $responseCode"}"
        }
        addLog(logMsg, isError = !reachable)
    }

    fun addLog(message: String, isError: Boolean = false) {
        _logs.update { currentList ->
            val updated = (listOf(LogEntry(message = message, isError = isError)) + currentList)
            if (updated.size > 200) updated.take(200) else updated
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
