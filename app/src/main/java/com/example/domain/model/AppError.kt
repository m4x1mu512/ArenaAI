package com.example.domain.model

/**
 * Typed domain errors mapped from network, HTTP, serialization, and validation failures.
 * Never includes raw API key credentials inside [technicalReason].
 */
sealed class AppError(
    open val technicalReason: String,
) {
    /** HTTP 401 Unauthorized. */
    data class Unauthorized(
        override val technicalReason: String,
    ) : AppError(technicalReason)

    /** HTTP 403 Forbidden. */
    data class Forbidden(
        override val technicalReason: String,
    ) : AppError(technicalReason)

    /** HTTP 429 Too Many Requests. */
    data class RateLimited(
        override val technicalReason: String,
    ) : AppError(technicalReason)

    /** HTTP 5xx Server Error. */
    data class ServerError(
        val statusCode: Int,
        override val technicalReason: String,
    ) : AppError(technicalReason)

    /** Non-2xx HTTP status outside specific cases. */
    data class HttpError(
        val statusCode: Int,
        override val technicalReason: String,
    ) : AppError(technicalReason)

    /** Socket timeout or call timeout. */
    data class Timeout(
        override val technicalReason: String,
    ) : AppError(technicalReason)

    /** Network connectivity or DNS failure. */
    data class NetworkError(
        override val technicalReason: String,
    ) : AppError(technicalReason)

    /** JSON or SSE payload parsing failure. */
    data class SerializationError(
        override val technicalReason: String,
    ) : AppError(technicalReason)

    /** Missing API key in local secure storage. */
    data class MissingApiKey(
        override val technicalReason: String = "API key is not configured.",
    ) : AppError(technicalReason)

    /** Invalid Base URL format. */
    data class InvalidUrl(
        override val technicalReason: String,
    ) : AppError(technicalReason)

    /** Fallback unexpected failure. */
    data class Unknown(
        override val technicalReason: String,
    ) : AppError(technicalReason)
}
