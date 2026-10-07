package com.rfm.edubot.mobile.core.common

/** Why there is no location; the same names the backend stores with a punch. */
enum class LocationFailure {
    PERMISSION_DENIED,
    DISABLED,
    TIMEOUT,
    UNAVAILABLE,
}

sealed interface LocationReading {
    /** [mocked] is what the platform reports: a test provider on Android, a simulated fix on iOS. */
    data class Fix(val latitude: Double, val longitude: Double, val accuracyMeters: Double?, val mocked: Boolean) : LocationReading

    data class Failed(val reason: LocationFailure) : LocationReading
}

/**
 * One location fix, read only when someone clocks in or out with the app open. Implementations ask
 * for the "while using the app" permission when needed and never for background location.
 */
interface LocationProvider {
    suspend fun current(): LocationReading
}
