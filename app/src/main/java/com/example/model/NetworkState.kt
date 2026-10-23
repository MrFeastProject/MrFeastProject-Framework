package com.example.model

data class NetworkStatus(
    val isNetworkConnected: Boolean = false,
    val isServerReachable: Boolean? = null,
    val responseCode: Int = 0,
    val latencyMs: Long = 0,
    val lastCheckTime: Long = 0,
    val checkCount: Int = 0,
    val failureCount: Int = 0,
    val errorMessage: String? = null,
    val isChecking: Boolean = false
)

data class LogEntry(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val isError: Boolean = false
)
