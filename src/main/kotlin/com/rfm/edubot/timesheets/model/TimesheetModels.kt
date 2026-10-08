package com.rfm.edubot.timesheets.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

enum class ShiftStatus { OPEN, CLOSED }

/** Every closed shift waits for the team; an edit sends an approved one back. */
enum class ReviewStatus { PENDING, APPROVED }

enum class PunchType { IN, BREAK_START, BREAK_END, OUT }

/**
 * Where a punch came from: the employee's app or browser, the team ([TEAM], closing or adding a shift), or
 * the employee saying when they stopped after forgetting to clock out ([CORRECTION]).
 */
enum class PunchChannel { APP, WEB, TEAM, CORRECTION }

/** [DEVICE_KEY]: signed by the employee's enrolled phone with a key only a strong biometric unlocks. */
enum class VerificationMethod { NONE, DEVICE_KEY }

/** [OFF] never asks for or stores a location; [OPTIONAL] flags a punch without one; [REQUIRED] refuses it. */
enum class LocationPolicy { OFF, OPTIONAL, REQUIRED }

/** Outside every work site: [FLAG] keeps the punch with a flag, [BLOCK] refuses a clock-in (never a break or clock-out). */
enum class GeofencePolicy { FLAG, BLOCK }

/** [OPTIONAL] flags a punch no enrolled phone signed; [REQUIRED] refuses it, so only the app can clock in. */
enum class BiometricPolicy { OFF, OPTIONAL, REQUIRED }

enum class DevicePlatform { ANDROID, IOS }

object ShiftFlags {
    const val NO_LOCATION = "NO_LOCATION"
    const val LOW_ACCURACY = "LOW_ACCURACY"
    const val MOCK_LOCATION = "MOCK_LOCATION"
    const val OUTSIDE_SITE = "OUTSIDE_SITE"
    const val UNVERIFIED = "UNVERIFIED"
    const val LONG_SHIFT = "LONG_SHIFT"
    const val MISSED_CLOCK_OUT = "MISSED_CLOCK_OUT"
    const val EDITED = "EDITED"
    const val MANUAL = "MANUAL"
}

/** Coordinates go after the retention period; accuracy and the site verdict stay with the punch. */
data class PunchLocation(
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Double?,
    val mocked: Boolean = false,
)

/** The work site nearest the punch, and whether the punch was inside its radius. */
data class SiteVerdict(
    val siteId: ObjectId,
    val siteName: String,
    val distanceM: Int,
    val inside: Boolean,
)

data class PunchVerification(
    val method: VerificationMethod = VerificationMethod.NONE,
    val deviceId: ObjectId? = null,
    val deviceName: String? = null,
)

/** Evidence of one clock action, appended to its shift and never changed (only its coordinates expire). */
data class Punch(
    val type: PunchType,
    /** Server time: the record's clock, whatever the phone's says. */
    val at: Instant,
    val channel: PunchChannel,
    val location: PunchLocation? = null,
    /** Why the app sent no location (permission denied, location off…), as the app reported it. */
    val locationError: String? = null,
    val site: SiteVerdict? = null,
    val verification: PunchVerification = PunchVerification(),
    val flags: List<String> = emptyList(),
    /** Who recorded it, for [PunchChannel.TEAM]. */
    val by: String? = null,
    /** When a correction or the team's clock-out was entered; [at] is then the time it claims. */
    val recordedAt: Instant? = null,
)

data class ShiftBreak(val startAt: Instant, val endAt: Instant? = null)

data class ShiftTimes(val startAt: Instant, val endAt: Instant?, val breaks: List<ShiftBreak>)

/** One correction by the team; [before] is null for a shift the team added. */
data class ShiftEdit(
    val at: Instant,
    val by: String,
    val reason: String,
    val before: ShiftTimes?,
    val after: ShiftTimes,
)

data class ShiftReview(
    val status: ReviewStatus = ReviewStatus.PENDING,
    val by: String? = null,
    val at: Instant? = null,
)

/**
 * One continuous period of work. [startAt], [endAt] and [breaks] are the effective times (what the team
 * corrected them to, if anything); [punches] is what actually happened. [day] is the company-local date of
 * [startAt], so a night shift belongs to the day it started.
 */
data class Shift(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val employeeId: ObjectId,
    val status: ShiftStatus,
    val startAt: Instant,
    val endAt: Instant? = null,
    val breaks: List<ShiftBreak> = emptyList(),
    val day: LocalDate,
    val siteId: ObjectId? = null,
    val siteName: String? = null,
    val clientId: ObjectId? = null,
    val note: String? = null,
    val punches: List<Punch> = emptyList(),
    val flags: List<String> = emptyList(),
    val review: ShiftReview = ShiftReview(),
    val edits: List<ShiftEdit> = emptyList(),
    val manual: Boolean = false,
    val workedMinutes: Int = 0,
    val breakMinutes: Int = 0,
    /** Bumped on every write; a write only lands on the version it was computed from. */
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isOpen: Boolean get() = status == ShiftStatus.OPEN

    val onBreak: Boolean get() = isOpen && breaks.lastOrNull()?.let { it.endAt == null } == true

    val times: ShiftTimes get() = ShiftTimes(startAt, endAt, breaks)

    val lastPunchAt: Instant get() = punches.maxOfOrNull { it.at } ?: startAt
}

/** A place work happens (an office, a building site), with the radius a punch must fall within. */
data class WorkSite(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val name: String,
    val address: String? = null,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int,
    /** The client the site belongs to, when it is a client's job. */
    val clientId: ObjectId? = null,
    val active: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * A phone an employee enrolled to sign their punches. Only the public key is kept; [keyId] is the SHA-256 of
 * its SubjectPublicKeyInfo, which the phone sends with each signature.
 */
data class TimeDevice(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val employeeId: ObjectId,
    val userId: ObjectId?,
    val keyId: String,
    val publicKey: String,
    val platform: DevicePlatform,
    val name: String,
    val active: Boolean = true,
    val createdAt: Instant,
    val lastUsedAt: Instant? = null,
    val revokedAt: Instant? = null,
    val revokedBy: String? = null,
)

/** A company's time clock rules. Companies that never saved any get [DEFAULT]. */
data class TimesheetSettings(
    val location: LocationPolicy = LocationPolicy.OPTIONAL,
    val geofence: GeofencePolicy = GeofencePolicy.FLAG,
    val biometric: BiometricPolicy = BiometricPolicy.OPTIONAL,
    /** Longer shifts are flagged, and an open shift older than this counts as a forgotten clock-out. */
    val maxShiftHours: Int = 12,
    val dailyHours: Int = 8,
    val weeklyHours: Int = 40,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
) {
    companion object {
        val DEFAULT = TimesheetSettings()
        val MAX_SHIFT_HOURS = 4..24
        val DAILY_HOURS = 1..24
        val WEEKLY_HOURS = 1..80
    }
}
