package com.rfm.edubot.timesheets.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

enum class ShiftStatus { OPEN, CLOSED }

/** Every closed shift waits for the team; an edit sends an approved one back. */
enum class ReviewStatus { PENDING, APPROVED }

/** [SWITCH]: the employee moves to another job without stopping work. */
enum class PunchType { IN, BREAK_START, BREAK_END, OUT, SWITCH }

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

/**
 * Whether time gets a job: [OFF] never asks; [OPTIONAL] lets people tag their time; [REQUIRED] needs a complete
 * job for every worked minute, so a clock-in or switch without one is refused and a shift with time left
 * untagged can't be approved.
 */
enum class WorkLogPolicy { OFF, OPTIONAL, REQUIRED }

/**
 * Planned shifts against clock-ins: [OFF] plans without checking; [FLAG] flags late arrivals, early leaves and
 * unplanned shifts and tells the team about no-shows; [BLOCK] also refuses a clock-in too long before a planned
 * shift, or with none planned.
 */
enum class SchedulePolicy { OFF, FLAG, BLOCK }

/** Who set a segment's job: a clock-in or switch on the clock, the employee afterwards, or the team. */
enum class SegmentSource { CLOCK, EMPLOYEE, TEAM }

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

    /** Worked time without a complete job, under [WorkLogPolicy.REQUIRED]. */
    const val UNALLOCATED = "UNALLOCATED"

    /** Clocked in later than the planned start, past the company's grace. */
    const val LATE = "LATE"

    /** Clocked out before the planned end, past the company's grace. */
    const val LEFT_EARLY = "LEFT_EARLY"

    /** Clocked with no planned shift, while the company checks schedules. */
    const val UNSCHEDULED = "UNSCHEDULED"
}

/**
 * What a block of work was for: a client, a Building project (which implies its client), an activity and a note.
 * The names are snapshots taken when the job was picked, so the record reads the same after a rename or delete.
 */
data class Job(
    val clientId: ObjectId? = null,
    val clientName: String? = null,
    val projectId: ObjectId? = null,
    val projectName: String? = null,
    val activityId: ObjectId? = null,
    val activityName: String? = null,
    val note: String? = null,
    /** From the activity, or, without one, whether the work was for a client or project. */
    val billable: Boolean = false,
) {
    /** The same work, whatever the names said when each was picked. */
    fun sameWork(other: Job?): Boolean =
        other != null && clientId == other.clientId && projectId == other.projectId && activityId == other.activityId && note == other.note
}

/** The parts a company's complete job has. With none ticked, a job needs a client, a project or an activity. */
data class JobNeeds(
    val client: Boolean = false,
    val project: Boolean = false,
    val activity: Boolean = false,
    val note: Boolean = false,
)

/**
 * A stretch of a shift's working time and its job. A segment runs from [startAt] to the next segment's start, the
 * last one to the shift's end (or now); breaks inside it don't count.
 */
data class ShiftSegment(
    val startAt: Instant,
    val job: Job? = null,
    val source: SegmentSource = SegmentSource.CLOCK,
    /** The work site the punch that started it was inside. */
    val siteName: String? = null,
)

/**
 * The planned shift a clocked one was matched to, with the times it had then. Only the [first] shift linked to a
 * plan is checked for lateness, and only one nobody [continued] (clocking in again for the same plan) for an
 * early leave, so clocking out for lunch isn't an early leave.
 */
data class PlanLink(
    val id: ObjectId,
    val startAt: Instant,
    val endAt: Instant,
    val first: Boolean = true,
    val continued: Boolean = false,
)

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
    /** The job a clock-in or switch picked. */
    val job: Job? = null,
)

data class ShiftBreak(val startAt: Instant, val endAt: Instant? = null)

data class ShiftTimes(val startAt: Instant, val endAt: Instant?, val breaks: List<ShiftBreak>)

/** The jobs of a shift before and after the team changed them. */
data class JobsChange(val before: List<ShiftSegment>, val after: List<ShiftSegment>)

/**
 * One correction by the team; [before] is null for a shift the team added. A change to the jobs alone keeps the
 * times on both sides and carries [jobs].
 */
data class ShiftEdit(
    val at: Instant,
    val by: String,
    val reason: String,
    val before: ShiftTimes?,
    val after: ShiftTimes,
    val jobs: JobsChange? = null,
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
    /** What the working time was for, in order; empty when the company doesn't log jobs. */
    val segments: List<ShiftSegment> = emptyList(),
    val planned: PlanLink? = null,
    /** How long after the planned start it began (0 when on time or unplanned). */
    val lateMinutes: Int = 0,
    /** How long before the planned end it finished (0 when it didn't, or is still open). */
    val earlyLeaveMinutes: Int = 0,
    /** Bumped on every write; a write only lands on the version it was computed from. */
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isOpen: Boolean get() = status == ShiftStatus.OPEN

    val onBreak: Boolean get() = isOpen && breaks.lastOrNull()?.let { it.endAt == null } == true

    val times: ShiftTimes get() = ShiftTimes(startAt, endAt, breaks)

    val lastPunchAt: Instant get() = punches.maxOfOrNull { it.at } ?: startAt

    /** The job being worked on now, for an open shift. */
    val currentJob: Job? get() = segments.lastOrNull()?.job
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
    val workLog: WorkLogPolicy = WorkLogPolicy.OFF,
    val jobNeeds: JobNeeds = JobNeeds(),
    val schedule: SchedulePolicy = SchedulePolicy.OFF,
    /** Clocking in this long after the planned start isn't late yet. */
    val lateGraceMinutes: Int = 5,
    /** Clocking out this long before the planned end isn't leaving early yet. */
    val earlyLeaveGraceMinutes: Int = 5,
    /** Under [SchedulePolicy.BLOCK], how long before a planned start clocking in opens. */
    val earlyClockInMinutes: Int = 15,
    /** A planned shift nobody clocked in for this long after its start is a no-show the team hears about. */
    val noShowMinutes: Int = 30,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
) {
    val logsJobs: Boolean get() = workLog != WorkLogPolicy.OFF

    val checksSchedule: Boolean get() = schedule != SchedulePolicy.OFF

    companion object {
        val DEFAULT = TimesheetSettings()
        val MAX_SHIFT_HOURS = 4..24
        val DAILY_HOURS = 1..24
        val WEEKLY_HOURS = 1..80
        val GRACE_MINUTES = 0..120
        val EARLY_CLOCK_IN_MINUTES = 0..240
        val NO_SHOW_MINUTES = 5..240
    }
}

/** A kind of work a company logs time against (a task code): "Cleaning", "Travel", "Painting". */
data class Activity(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val name: String,
    val code: String? = null,
    /** Time on it is billable to the client by default. */
    val billable: Boolean = true,
    val active: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Why the team doesn't count a planned shift nobody worked as an absence. */
data class Excuse(val reason: String, val by: String, val at: Instant)

/**
 * A shift the team planned for an employee: [day] is the company-local date of [startAt]; [endAt] may fall on the
 * next day. Clocked shifts link to it ([PlanLink]); [matchedShiftId] is the first of them, kept so the no-show
 * watch can skip attended plans without a lookup.
 */
data class PlannedShift(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val employeeId: ObjectId,
    val day: LocalDate,
    val startAt: Instant,
    val endAt: Instant,
    val siteId: ObjectId? = null,
    val siteName: String? = null,
    val job: Job? = null,
    val note: String? = null,
    val excuse: Excuse? = null,
    val matchedShiftId: ObjectId? = null,
    /** When the team was told nobody clocked in for it. */
    val alertedAt: Instant? = null,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
