package com.example.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.remote.CoarenaApiException
import com.example.domain.model.AppError

/**
 * Maps a [Throwable] into a typed [AppError].
 */
fun Throwable.toAppError(): AppError {
    return when (this) {
        is CoarenaApiException -> this.appError
        else -> AppError.Unknown(this.message ?: this::class.simpleName ?: "Unknown error")
    }
}

/**
 * Resolves a user-friendly localized error message for [AppError] inside Composable scope.
 */
@Composable
fun AppError.localizedMessage(): String {
    return when (this) {
        is AppError.Unauthorized -> stringResource(R.string.error_unauthorized)
        is AppError.Forbidden -> stringResource(R.string.error_forbidden)
        is AppError.RateLimited -> stringResource(R.string.error_rate_limited)
        is AppError.ServerError -> stringResource(R.string.error_server, statusCode)
        is AppError.HttpError -> stringResource(R.string.error_http_generic, statusCode)
        is AppError.Timeout -> stringResource(R.string.error_timeout)
        is AppError.NetworkError -> stringResource(R.string.error_network)
        is AppError.SerializationError -> stringResource(R.string.error_serialization)
        is AppError.MissingApiKey -> stringResource(R.string.error_missing_api_key)
        is AppError.InvalidUrl -> stringResource(R.string.error_invalid_url)
        is AppError.Unknown -> stringResource(R.string.error_unknown)
    }
}

/**
 * Resolves a user-friendly localized error message for [AppError] using a [Context].
 */
fun AppError.formatMessage(context: Context): String {
    return when (this) {
        is AppError.Unauthorized -> context.getString(R.string.error_unauthorized)
        is AppError.Forbidden -> context.getString(R.string.error_forbidden)
        is AppError.RateLimited -> context.getString(R.string.error_rate_limited)
        is AppError.ServerError -> context.getString(R.string.error_server, statusCode)
        is AppError.HttpError -> context.getString(R.string.error_http_generic, statusCode)
        is AppError.Timeout -> context.getString(R.string.error_timeout)
        is AppError.NetworkError -> context.getString(R.string.error_network)
        is AppError.SerializationError -> context.getString(R.string.error_serialization)
        is AppError.MissingApiKey -> context.getString(R.string.error_missing_api_key)
        is AppError.InvalidUrl -> context.getString(R.string.error_invalid_url)
        is AppError.Unknown -> context.getString(R.string.error_unknown)
    }
}
