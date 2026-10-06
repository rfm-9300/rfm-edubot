package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.network.ApiException
import kotlinx.coroutines.CancellationException

/**
 * Runs one dashboard call and reports the outcome instead of throwing.
 *
 * Every repository goes through here, which is what lets screens stop writing
 * `catch (_: Exception) { error = "load" }` and lose the reason the call failed.
 */
internal suspend inline fun <T> apiCall(crossinline block: suspend () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: ApiException) {
    Outcome.Failure(failure.error)
} catch (failure: Exception) {
    Outcome.Failure(AppError.Offline(failure.message))
}
