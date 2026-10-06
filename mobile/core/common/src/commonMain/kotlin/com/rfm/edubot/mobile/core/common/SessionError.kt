package com.rfm.edubot.mobile.core.common

enum class SessionError {
    MISSING_CREDENTIALS,
    INVALID_CREDENTIALS,
    SESSION_EXPIRED,
    ACCOUNT_INACTIVE,
    CONNECTION_FAILED;

    companion object {
        /** What the sign-in screen should say about [error]. */
        fun ofLogin(error: AppError): SessionError = when (error) {
            AppError.Unauthorized -> INVALID_CREDENTIALS
            AppError.Forbidden -> ACCOUNT_INACTIVE
            is AppError.Rejected -> if (error.code == "account_inactive") ACCOUNT_INACTIVE else INVALID_CREDENTIALS
            else -> CONNECTION_FAILED
        }
    }
}
