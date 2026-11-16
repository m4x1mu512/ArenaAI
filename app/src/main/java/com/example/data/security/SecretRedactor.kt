package com.example.data.security

/**
 * Redacts sensitive API keys and Bearer tokens from error messages and diagnostics
 * so credentials can never appear in UI error details, logs, or chat history.
 */
object SecretRedactor {
    private val bearerRegex = Regex("(?i)(bearer\\s+)([^\\s,;\"']+)")
    private val skKeyRegex = Regex("\\bsk-[A-Za-z0-9_-]{8,}\\b")

    /**
     * Replaces any occurrence of [apiKey] or Bearer token patterns inside [input] with `[REDACTED]`.
     */
    fun redact(input: String?, apiKey: String? = null): String {
        if (input.isNullOrBlank()) return ""
        var sanitized = input
        val trimmedKey = apiKey?.trim().orEmpty()
        if (trimmedKey.isNotEmpty()) {
            sanitized = sanitized.replace(trimmedKey, "[REDACTED]")
        }
        sanitized = bearerRegex.replace(sanitized) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        sanitized = skKeyRegex.replace(sanitized, "[REDACTED]")
        return sanitized
    }
}
