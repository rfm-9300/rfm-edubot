package com.rfm.edubot.mobile.feature.bookings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.BookingsRepository
import com.rfm.edubot.mobile.core.model.Booking
import com.rfm.edubot.mobile.core.model.BookingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BookingFilter { Today, Upcoming, NeedsConfirm }

class BookingsViewModel(
    private val repository: BookingsRepository,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope

    private val mutableFilter = MutableStateFlow(BookingFilter.Today)
    val filter: StateFlow<BookingFilter> = mutableFilter.asStateFlow()

    private val mutablePending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = mutablePending.asStateFlow()

    private val mutableFailure = MutableStateFlow<AppError?>(null)
    val failure: StateFlow<AppError?> = mutableFailure.asStateFlow()

    fun load() = scope.launch { repository.upcoming.load() }

    fun refresh() = scope.launch {
        mutableFailure.value = null
        repository.upcoming.refresh()
    }

    fun setFilter(value: BookingFilter) {
        mutableFilter.value = value
    }

    fun setStatus(booking: Booking, status: String) = scope.launch {
        if (mutablePending.value != null) return@launch
        mutablePending.value = booking.id
        mutableFailure.value = null
        val result = repository.setStatus(booking, status)
        mutablePending.value = null
        if (result is Outcome.Failure) mutableFailure.value = result.error
    }

    fun visible(all: List<Booking>, filter: BookingFilter, clock: TenantClock): List<Booking> {
        val today = clock.today()
        return when (filter) {
            BookingFilter.Today -> all.filter { clock.localDateTime(it.startAt)?.date == today }
            BookingFilter.Upcoming -> all.filter { (clock.localDateTime(it.startAt)?.date ?: today) >= today }
            BookingFilter.NeedsConfirm -> all.filter { it.pending }
        }.sortedBy { it.startAt }
    }

    fun counts(all: List<Booking>, clock: TenantClock): Map<BookingFilter, Int> {
        val today = clock.today()
        return mapOf(
            BookingFilter.Today to all.count { clock.localDateTime(it.startAt)?.date == today },
            BookingFilter.Upcoming to all.count { (clock.localDateTime(it.startAt)?.date ?: today) >= today },
            BookingFilter.NeedsConfirm to all.count { it.pending },
        )
    }

    /** The one transition worth a single tap, given the booking's current state. */
    fun primaryTransition(booking: Booking): String? = when (booking.status) {
        BookingStatus.PENDING -> BookingStatus.CONFIRMED
        BookingStatus.CONFIRMED -> BookingStatus.COMPLETED
        else -> null
    }
}
