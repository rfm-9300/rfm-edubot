package com.rfm.edubot.mobile.core.common

/**
 * Why a dashboard call failed, in the shape a screen can act on.
 *
 * [Rejected] carries the backend's stable error code (`tax_id_required`, `id_taken`,
 * `address_required`, …) so a form can point at the field that is wrong instead of showing one
 * generic message for every 400.
 */
sealed interface AppError {
    /** The token is gone or expired. The session layer signs out when it sees this. */
    data object Unauthorized : AppError

    /** Authenticated, but the tenant does not have the module or the user lacks the role. */
    data object Forbidden : AppError

    data object NotFound : AppError

    /**
     * A 400/409 with a stable code in the body. [code] is empty when the body carried none; [details] are
     * the body's other plain fields (the nearest site and its distance for `outside_sites`).
     */
    data class Rejected(val code: String, val status: Int = 400, val details: Map<String, String> = emptyMap()) : AppError

    /** 5xx. */
    data class Unavailable(val status: Int) : AppError

    /** The request never reached the backend, or the body could not be read. */
    data class Offline(val detail: String? = null) : AppError
}

/** True while retrying could plausibly succeed without the user changing anything. */
val AppError.isTransient: Boolean
    get() = this is AppError.Offline || this is AppError.Unavailable
