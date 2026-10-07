package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

/** `GET /app/api/portal/time`: everything the clock screen shows, in one call. */
@Serializable
data class TimeClockStatus(
    val policy: TimesheetPolicy,
    /** [STATE_OFF], [STATE_WORKING] or [STATE_ON_BREAK]. */
    val state: String,
    val open: Shift? = null,
    /** Clocked in for longer than the company's limit: a forgotten clock-out. */
    val overdue: Boolean = false,
    val todayMinutes: Int = 0,
    val weekMinutes: Int = 0,
    val sites: List<WorkSite> = emptyList(),
    val devices: List<TimeDevice> = emptyList(),
    val serverTime: String,
    val timezone: String = "Europe/Lisbon",
) {
    companion object {
        const val STATE_OFF = "OFF"
        const val STATE_WORKING = "WORKING"
        const val STATE_ON_BREAK = "ON_BREAK"
    }
}

@Serializable
data class TimesheetPolicy(
    /** `OFF`, `OPTIONAL` or `REQUIRED`. */
    val location: String = POLICY_OPTIONAL,
    /** `FLAG` or `BLOCK`. */
    val geofence: String = GEOFENCE_FLAG,
    /** `OFF`, `OPTIONAL` or `REQUIRED`. */
    val biometric: String = POLICY_OPTIONAL,
    val maxShiftHours: Int = 12,
    val dailyHours: Int = 8,
    val weeklyHours: Int = 40,
) {
    companion object {
        const val POLICY_OFF = "OFF"
        const val POLICY_OPTIONAL = "OPTIONAL"
        const val POLICY_REQUIRED = "REQUIRED"
        const val GEOFENCE_FLAG = "FLAG"
        const val GEOFENCE_BLOCK = "BLOCK"
    }
}

/** A shift as the backend sends it: instants plus `HH:mm` on the company's clock. */
@Serializable
data class Shift(
    val id: String,
    val employeeId: String,
    val status: String,
    val day: String,
    val startAt: String,
    val endAt: String? = null,
    val startTime: String,
    val endTime: String? = null,
    val endsNextDay: Boolean = false,
    val breaks: List<ShiftBreak> = emptyList(),
    val workedMinutes: Int = 0,
    val breakMinutes: Int = 0,
    val onBreak: Boolean = false,
    val overdue: Boolean = false,
    val siteName: String? = null,
    val note: String? = null,
    val flags: List<String> = emptyList(),
    /** `PENDING` or `APPROVED`. */
    val review: String = "PENDING",
    val punches: List<Punch> = emptyList(),
) {
    val isOpen: Boolean get() = status == "OPEN"
}

@Serializable
data class ShiftBreak(val startAt: String, val endAt: String? = null, val startTime: String, val endTime: String? = null)

@Serializable
data class Punch(
    /** `IN`, `BREAK_START`, `BREAK_END` or `OUT`. */
    val type: String,
    val at: String,
    val time: String,
    val channel: String,
    val accuracyM: Double? = null,
    val mocked: Boolean = false,
    val locationError: String? = null,
    val siteName: String? = null,
    val siteDistanceM: Int? = null,
    val inside: Boolean? = null,
    val verified: Boolean = false,
    val deviceName: String? = null,
    val flags: List<String> = emptyList(),
)

@Serializable
data class WorkSite(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int,
)

@Serializable
data class TimeDevice(
    val id: String,
    val keyId: String,
    val name: String,
    val platform: String,
    val active: Boolean = true,
)

@Serializable data class TimeChallenge(val nonce: String, val expiresAt: String)

@Serializable data class PunchResult(val status: TimeClockStatus, val shift: Shift)

@Serializable
data class PunchRequest(
    val type: String,
    val location: PunchLocation? = null,
    val locationError: String? = null,
    val verification: PunchVerification? = null,
    val channel: String = CHANNEL_APP,
) {
    companion object {
        const val CHANNEL_APP = "APP"
        const val IN = "IN"
        const val BREAK_START = "BREAK_START"
        const val BREAK_END = "BREAK_END"
        const val OUT = "OUT"
    }
}

@Serializable
data class PunchLocation(val latitude: Double, val longitude: Double, val accuracyM: Double? = null, val mocked: Boolean = false)

@Serializable data class PunchVerification(val keyId: String, val nonce: String, val signature: String)

@Serializable
data class EnrollDevice(val name: String, val platform: String, val publicKey: String, val nonce: String, val signature: String)

/** `HH:mm` on the company's clock. */
@Serializable data class CloseShift(val end: String)
