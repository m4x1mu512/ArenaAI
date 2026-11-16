package com.example.domain.model

/**
 * Account credit balance returned by `GET /api/v1/credits`.
 */
data class CreditsInfo(
    val balance: String,
    val currency: String? = null,
    val totalGranted: String? = null,
    val totalUsed: String? = null,
    val extraDetails: Map<String, String> = emptyMap(),
)

/**
 * Single key-value metric inside [LimitsInfo].
 */
data class LimitItem(
    val key: String,
    val value: String,
)

/**
 * Account rate and quota limits returned by `GET /api/v1/limits`.
 */
data class LimitsInfo(
    val requestsPerMinute: String? = null,
    val tokensPerMinute: String? = null,
    val dailyLimit: String? = null,
    val remainingRequests: String? = null,
    val items: List<LimitItem> = emptyList(),
)

/**
 * Single request log entry returned by `GET /api/v1/requests`.
 */
data class RequestHistoryItem(
    val id: String,
    val createdAt: String,
    val model: String,
    val status: String,
    val totalTokens: Int? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

/**
 * Result of testing API connectivity and credentials.
 */
data class ConnectionTestResult(
    val isSuccess: Boolean,
    val modelCount: Int? = null,
    val error: AppError? = null,
)
