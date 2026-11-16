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
    /** The planned shift that matters now: the open shift's, the one clocking in would match, or today's next. */
    val planned: PlannedShift? = null,
    /** The planned shifts still to come in the next two weeks, besides [planned]. */
    val upcoming: List<PlannedShift> = emptyList(),
    val clockIn: ClockIn = ClockIn(),
) {
    companion object {
        const val STATE_OFF = "OFF"
        const val STATE_WORKING = "WORKING"
        const val STATE_ON_BREAK = "ON_BREAK"
    }
}

/** Whether clocking in is open now; the company's strict schedule closes it outside planned shifts. */
@Serializable
data class ClockIn(
    /** [ALLOWED], [TOO_EARLY] (until [opensTime]) or [NOT_SCHEDULED]. */
    val state: String = ALLOWED,
    val opensAt: String? = null,
    val opensTime: String? = null,
) {
    val allowed: Boolean get() = state == ALLOWED

    companion object {
        const val ALLOWED = "ALLOWED"
        const val TOO_EARLY = "TOO_EARLY"
        const val NOT_SCHEDULED = "NOT_SCHEDULED"
    }
}

/** The parts a complete job has, when every worked minute needs one. */
@Serializable
data class JobNeeds(val client: Boolean = false, val project: Boolean = false, val activity: Boolean = false, val note: Boolean = false)

/** What a block of work was for; the names are as they were when it was picked. */
@Serializable
data class Job(
    val clientId: String? = null,
    val clientName: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
    val activityId: String? = null,
    val activityName: String? = null,
    val note: String? = null,
    val billable: Boolean = false,
) {
    /** "Casa Silva · Limpeza", else the note; null when there is nothing to name. */
    val label: String? get() = listOfNotNull(clientName, projectName, activityName).joinToString(" · ").ifEmpty { note }

    val request: JobRequest get() = JobRequest(clientId, projectId, activityId, note)
}

/** A stretch of a shift's working time and its job; [source] is `CLOCK`, `EMPLOYEE` or `TEAM`. */
@Serializable
data class ShiftSegment(
    val startAt: String,
    val endAt: String? = null,
    val startTime: String,
    val endTime: String? = null,
    val minutes: Int = 0,
    val job: Job? = null,
    val source: String = SOURCE_CLOCK,
    val siteName: String? = null,
    /** Has every part the company's jobs need. */
    val complete: Boolean = true,
) {
    companion object {
        const val SOURCE_CLOCK = "CLOCK"
        const val SOURCE_EMPLOYEE = "EMPLOYEE"
        const val SOURCE_TEAM = "TEAM"
    }
}

/** The planned shift a clocked one was matched to. */
@Serializable data class PlanLink(val id: String, val startAt: String, val endAt: String, val startTime: String, val endTime: String)

/** How a planned shift went: [state] is one of the `STATE_` names. */
@Serializable
data class Attendance(
    val state: String = STATE_UPCOMING,
    val lateMinutes: Int = 0,
    val earlyLeaveMinutes: Int = 0,
    val late: Boolean = false,
    val leftEarly: Boolean = false,
    val workedMinutes: Int = 0,
    val shiftIds: List<String> = emptyList(),
) {
    companion object {
        const val STATE_UPCOMING = "UPCOMING"
        const val STATE_DUE = "DUE"
        const val STATE_MISSING = "MISSING"
        const val STATE_ABSENT = "ABSENT"
        const val STATE_WORKING = "WORKING"
        const val STATE_ATTENDED = "ATTENDED"
        const val STATE_EXCUSED = "EXCUSED"
    }
}

/** A shift the company planned for the employee. */
@Serializable
data class PlannedShift(
    val id: String,
    val day: String,
    val startAt: String,
    val endAt: String,
    val startTime: String,
    val endTime: String,
    val endsNextDay: Boolean = false,
    val minutes: Int = 0,
    val siteName: String? = null,
    val job: Job? = null,
    val note: String? = null,
    val attendance: Attendance = Attendance(),
)

/** `GET /app/api/portal/time/jobs`: what a job can name. */
@Serializable
data class JobOptions(
    val clients: List<JobClient> = emptyList(),
    val projects: List<JobProject> = emptyList(),
    val activities: List<JobActivity> = emptyList(),
) {
    val isEmpty: Boolean get() = clients.isEmpty() && projects.isEmpty() && activities.isEmpty()

    /** [job] in a few words from these lists ("Casa Silva · Limpeza"), else its note; null for nothing named here. */
    fun label(job: JobRequest?): String? {
        if (job == null) return null
        val parts = listOfNotNull(
            job.clientId?.let { id -> clients.firstOrNull { it.id == id }?.name },
            job.projectId?.let { id -> projects.firstOrNull { it.id == id }?.name },
            job.activityId?.let { id -> activities.firstOrNull { it.id == id }?.name },
        )
        return parts.joinToString(" · ").ifEmpty { job.note?.takeIf(String::isNotBlank) }
    }

    /** The projects a job with [clientId] can name: that client's and the ones without a client. */
    fun projectsFor(clientId: String?): List<JobProject> = projects.filter { clientId == null || it.clientId == null || it.clientId == clientId }
}

@Serializable data class JobClient(val id: String, val number: String = "", val name: String)

@Serializable data class JobProject(val id: String, val number: String = "", val name: String, val clientId: String? = null)

@Serializable data class JobActivity(val id: String, val name: String, val code: String? = null, val billable: Boolean = true)

/** A job as the backend takes it: ids of the company's records and a note, every part optional. */
@Serializable
data class JobRequest(val clientId: String? = null, val projectId: String? = null, val activityId: String? = null, val note: String? = null) {
    val isEmpty: Boolean get() = clientId == null && projectId == null && activityId == null && note.isNullOrBlank()
}

/** The company's rule for a complete job, as the backend applies it, so the app can say what is missing first. */
object JobRules {
    const val PART_JOB = "job"
    const val PART_CLIENT = "client"
    const val PART_PROJECT = "project"
    const val PART_ACTIVITY = "activity"
    const val PART_NOTE = "note"

    /**
     * The first part a complete job lacks under the strict work log (`job` when nothing is named), or null when the
     * job will do. Without the strict rule any job, or none, will do.
     */
    fun missing(job: JobRequest?, policy: TimesheetPolicy): String? {
        if (!policy.jobsRequired) return null
        val needs = policy.jobNeeds
        val picked = job?.takeUnless { it.isEmpty } ?: return PART_JOB
        return when {
            needs.client && picked.clientId == null -> PART_CLIENT
            needs.project && picked.projectId == null -> PART_PROJECT
            needs.activity && picked.activityId == null -> PART_ACTIVITY
            needs.note && picked.note.isNullOrBlank() -> PART_NOTE
            picked.clientId == null && picked.projectId == null && picked.activityId == null && !needs.note -> PART_JOB
            else -> null
        }
    }
}

/** A shift's time split between jobs; [start] is `HH:mm` on the company's clock (the first is the shift's start). */
@Serializable data class SegmentRequest(val start: String, val job: JobRequest? = null)

@Serializable data class SegmentsRequest(val segments: List<SegmentRequest>)

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
    /** `OFF`, `OPTIONAL` or `REQUIRED`: whether time gets a job. */
    val workLog: String = POLICY_OFF,
    val jobNeeds: JobNeeds = JobNeeds(),
    /** `OFF`, `FLAG` or `BLOCK`: planned shifts against clock-ins. */
    val schedule: String = POLICY_OFF,
    val lateGraceMinutes: Int = 5,
) {
    val logsJobs: Boolean get() = workLog != POLICY_OFF

    val jobsRequired: Boolean get() = workLog == POLICY_REQUIRED

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
    /** What the working time was for; empty when the company doesn't log jobs. */
    val segments: List<ShiftSegment> = emptyList(),
    val planned: PlanLink? = null,
    val lateMinutes: Int = 0,
    val earlyLeaveMinutes: Int = 0,
    /** Minutes on no complete job, by the company's rules. */
    val unallocatedMinutes: Int = 0,
) {
    val isOpen: Boolean get() = status == "OPEN"

    val approved: Boolean get() = review == "APPROVED"

    /** The job being worked on now, for an open shift. */
    val currentJob: Job? get() = segments.lastOrNull()?.job
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
    /** The job a clock-in or switch picked. */
    val job: Job? = null,
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
    /** What the work is for, on a clock-in or a switch. */
    val job: JobRequest? = null,
) {
    companion object {
        const val CHANNEL_APP = "APP"
        const val IN = "IN"
        const val BREAK_START = "BREAK_START"
        const val BREAK_END = "BREAK_END"
        const val OUT = "OUT"
        const val SWITCH = "SWITCH"
    }
}

@Serializable
data class PunchLocation(val latitude: Double, val longitude: Double, val accuracyM: Double? = null, val mocked: Boolean = false)

@Serializable data class PunchVerification(val keyId: String, val nonce: String, val signature: String)

@Serializable
data class EnrollDevice(val name: String, val platform: String, val publicKey: String, val nonce: String, val signature: String)

/** `HH:mm` on the company's clock. */
@Serializable data class CloseShift(val end: String)
