package com.example.data.remote

import com.example.domain.model.AppError

/**
 * Exception wrapper carrying a typed [AppError] with redacted diagnostics.
 */
class CoarenaApiException(
    val appError: AppError,
    cause: Throwable? = null,
) : Exception(appError.technicalReason, cause)
