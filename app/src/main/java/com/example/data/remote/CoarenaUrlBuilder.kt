package com.example.data.remote

import com.example.domain.model.AppError
import com.example.domain.model.AppSettings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validates and constructs full Coarena endpoint URLs from a user-configurable Base URL.
 */
object CoarenaUrlBuilder {

    /**
     * Validates that [rawBaseUrl] is a valid `http://` or `https://` URL and strips trailing slashes.
     *
     * @throws IllegalArgumentException if the URL cannot be parsed.
     */
    fun normalizeBaseUrl(rawBaseUrl: String): String {
        val trimmed = rawBaseUrl.trim().trimEnd('/')
        val parsed = trimmed.toHttpUrlOrNull()
        require(parsed != null && (parsed.scheme == "http" || parsed.scheme == "https")) {
            "Invalid Base URL: must begin with http:// or https://"
        }
        return trimmed
    }

    /**
     * Checks whether [rawBaseUrl] is syntactically valid for HTTP/HTTPS calls.
     */
    fun isValidBaseUrl(rawBaseUrl: String): Boolean {
        val trimmed = rawBaseUrl.trim().trimEnd('/')
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "http" || parsed.scheme == "https"
    }

    /**
     * Builds an endpoint URL from [baseUrl] and relative [endpointPath] (e.g. `models`, `chat/completions`).
     */
    fun buildEndpoint(baseUrl: String, endpointPath: String): String {
        val effectiveBase = baseUrl.trim().ifEmpty { AppSettings.DEFAULT_BASE_URL }
        val normalized = normalizeBaseUrl(effectiveBase)
        val cleanPath = endpointPath.trim().trimStart('/')
        return "$normalized/$cleanPath"
    }

    /** Builds the models endpoint (`GET {baseUrl}/models`). */
    fun modelsUrl(baseUrl: String): String = buildEndpoint(baseUrl, "models")

    /** Builds the chat completions endpoint (`POST {baseUrl}/chat/completions`). */
    fun chatCompletionsUrl(baseUrl: String): String = buildEndpoint(baseUrl, "chat/completions")

    /** Builds the credits endpoint (`GET {baseUrl}/credits`). */
    fun creditsUrl(baseUrl: String): String = buildEndpoint(baseUrl, "credits")

    /** Builds the limits endpoint (`GET {baseUrl}/limits`). */
    fun limitsUrl(baseUrl: String): String = buildEndpoint(baseUrl, "limits")

    /** Builds the request history endpoint (`GET {baseUrl}/requests`). */
    fun requestsUrl(baseUrl: String): String = buildEndpoint(baseUrl, "requests")

    /**
     * Validates [baseUrl] and maps invalid input into [AppError.InvalidUrl] if needed.
     */
    fun requireValidEndpoint(baseUrl: String, endpointPath: String): Result<String> {
        return runCatching { buildEndpoint(baseUrl, endpointPath) }.recoverCatching { throwable ->
            throw CoarenaApiException(
                AppError.InvalidUrl(throwable.message ?: "Invalid Base URL"),
            )
        }
    }
}
