package com.example.model

import java.util.UUID

data class FilterModule(
    val index: Int,
    val id: String,
    val name: String,
    val badge: String,
    val description: String,
    val url: String,
    val version: String = "1.0",
    val domainsCount: Int = 0,
    val lastETag: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class BlockedThreatEvent(
    val id: String = UUID.randomUUID().toString(),
    val domain: String,
    val source: String,
    val timestamp: Long = System.currentTimeMillis()
)
