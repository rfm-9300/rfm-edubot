package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.Booking
import com.rfm.edubot.mobile.core.model.UpdateBooking
import com.rfm.edubot.mobile.core.network.BookingsApi
import kotlinx.serialization.builtins.ListSerializer

class BookingsRepository(
    private val api: BookingsApi,
    cache: SnapshotCache,
) {
    /**
     * The calendar the phone shows: from the start of today onwards. A window rather than
     * everything, because a busy tenant's full history is not what anyone opens the app for.
     */
    val upcoming = CachedResource(
        key = "bookings.upcoming",
        serializer = ListSerializer(Booking.serializer()),
        cache = cache,
        fetch = { api.bookings() },
    )

    suspend fun setStatus(booking: Booking, status: String): Outcome<Booking> =
        update(booking, UpdateBooking(status = status))

    suspend fun update(booking: Booking, change: UpdateBooking): Outcome<Booking> {
        val updated = apiCall { api.updateBooking(booking.id, change) }
        updated.valueOrNull?.let { saved ->
            upcoming.mutate { current -> current.map { if (it.id == saved.id) saved else it } }
        }
        return updated
    }
}
