package com.rfm.edubot.shared

sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Failure(val error: AppError) : Result<Nothing>()

    companion object {
        fun <T> success(data: T): Result<T> = Success(data)
        fun failure(error: AppError): Result<Nothing> = Failure(error)
    }
}

sealed class AppError {
    data class UserBlocked(val waId: String) : AppError()
    data class RateLimited(val waId: String, val retryAfter: String) : AppError()
    data class AiUnavailable(val message: String) : AppError()
    data class WhatsAppApiError(val code: Int, val message: String) : AppError()
    data class ValidationError(val field: String, val message: String) : AppError()
    object UnknownError : AppError()
}
