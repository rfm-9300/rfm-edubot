package com.rfm.edubot.timesheets

import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import com.rfm.edubot.timesheets.Timesheets.workedUntil
import com.rfm.edubot.timesheets.model.Punch
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.ShiftBreak
import com.rfm.edubot.timesheets.model.ShiftEdit
import com.rfm.edubot.timesheets.model.ShiftTimes
import com.rfm.edubot.timesheets.model.TimeDevice
import com.rfm.edubot.timesheets.model.TimesheetSettings
import com.rfm.edubot.timesheets.model.VerificationMethod
import com.rfm.edubot.timesheets.model.WorkSite
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

internal fun Tenant.zone(): TimeZone = TimeZone.of(TenantTimeZones.normalize(timezone))

/**
 * A shift as both sides read it. Times come as instants and as `HH:mm` in the company's timezone, so a
 * browser or phone in another zone shows and edits the company's clock. [punches] and [edits] are only
 * sent where someone looks at one shift.
 */
@Serializable
internal data class ShiftDto(
    val id: String,
    val employeeId: String,
    val employeeName: String? = null,
    val employeeNumber: String? = null,
    val status: String,
    val day: String,
    val startAt: String,
    val endAt: String? = null,
    val startTime: String,
    val endTime: String? = null,
    /** The end falls on the day after [day] (a night shift). */
    val endsNextDay: Boolean,
    val breaks: List<BreakDto>,
    /** Up to now for an open shift. */
    val workedMinutes: Int,
    val breakMinutes: Int,
    val onBreak: Boolean,
    /** Open for longer than the company's limit: a forgotten clock-out. */
    val overdue: Boolean,
    val siteId: String? = null,
    val siteName: String? = null,
    val note: String? = null,
    val flags: List<String>,
    val review: String,
    val reviewedBy: String? = null,
    val reviewedAt: String? = null,
    val manual: Boolean,
    val edited: Boolean,
    val punches: List<PunchDto>? = null,
    val edits: List<EditDto>? = null,
)

@Serializable
internal data class BreakDto(val startAt: String, val endAt: String? = null, val startTime: String, val endTime: String? = null)

@Serializable
internal data class PunchDto(
    val type: String,
    val at: String,
    val time: String,
    val channel: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Double? = null,
    val mocked: Boolean,
    val locationError: String? = null,
    val siteName: String? = null,
    val siteDistanceM: Int? = null,
    val inside: Boolean? = null,
    val verified: Boolean,
    val deviceName: String? = null,
    val flags: List<String>,
    val by: String? = null,
    val recordedAt: String? = null,
)

@Serializable
internal data class EditDto(val at: String, val by: String, val reason: String, val before: TimesDto? = null, val after: TimesDto)

@Serializable
internal data class TimesDto(val startAt: String, val endAt: String? = null, val startTime: String, val endTime: String? = null, val breaks: List<BreakDto>)

@Serializable
internal data class TimesheetPolicyDto(
    val location: String,
    val geofence: String,
    val biometric: String,
    val maxShiftHours: Int,
    val dailyHours: Int,
    val weeklyHours: Int,
    val canEdit: Boolean,
)

@Serializable
internal data class SiteDto(
    val id: String,
    val name: String,
    val address: String? = null,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int,
    val clientId: String? = null,
    val clientName: String? = null,
    val active: Boolean,
)

@Serializable
internal data class DeviceDto(
    val id: String,
    val keyId: String,
    val name: String,
    val platform: String,
    val active: Boolean,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val revokedAt: String? = null,
    val revokedBy: String? = null,
)

/** What the employee's clock screen needs, in one call. */
@Serializable
internal data class TimeClockStatusDto(
    val policy: TimesheetPolicyDto,
    /** `OFF`, `WORKING` or `ON_BREAK`. */
    val state: String,
    val open: ShiftDto? = null,
    val overdue: Boolean,
    val todayMinutes: Int,
    val weekMinutes: Int,
    val sites: List<SiteDto>,
    val devices: List<DeviceDto>,
    val serverTime: String,
    val timezone: String,
)

@Serializable internal data class ChallengeDto(val nonce: String, val expiresAt: String)

@Serializable internal data class PunchResultDto(val status: TimeClockStatusDto, val shift: ShiftDto)

/** A refused punch; [siteName] and [distanceM] say how far the nearest site was for `outside_sites`. */
@Serializable internal data class PunchRefusalDto(val error: String, val siteName: String? = null, val distanceM: Int? = null)

@Serializable
internal data class EmployeeTotalsDto(
    val employeeId: String,
    val employeeName: String,
    val employeeNumber: String,
    val workedMinutes: Int,
    val days: Int,
    val overDailyMinutes: Int,
    val overWeeklyMinutes: Int,
    val toReview: Int,
    val flagged: Int,
    val open: Int,
)

@Serializable
internal data class TimesheetDto(
    val from: String,
    val to: String,
    val shifts: List<ShiftDto>,
    val totals: List<EmployeeTotalsDto>,
    val policy: TimesheetPolicyDto,
)

@Serializable internal data class BoardDto(val working: List<ShiftDto>, val serverTime: String)

@Serializable internal data class ApprovedDto(val approved: Int)

internal fun TimesheetSettings.dto(canEdit: Boolean = false) = TimesheetPolicyDto(
    location = location.name,
    geofence = geofence.name,
    biometric = biometric.name,
    maxShiftHours = maxShiftHours,
    dailyHours = dailyHours,
    weeklyHours = weeklyHours,
    canEdit = canEdit,
)

internal fun TimeDevice.dto() = DeviceDto(
    id = id.toHexString(),
    keyId = keyId,
    name = name,
    platform = platform.name,
    active = active,
    createdAt = createdAt.toString(),
    lastUsedAt = lastUsedAt?.toString(),
    revokedAt = revokedAt?.toString(),
    revokedBy = revokedBy,
)

internal fun WorkSite.dto(clientName: String? = null) = SiteDto(
    id = id.toHexString(),
    name = name,
    address = address,
    latitude = latitude,
    longitude = longitude,
    radiusM = radiusM,
    clientId = clientId?.toHexString(),
    clientName = clientName,
    active = active,
)

/** Turns shifts into what the API sends, in the company's timezone. */
internal class ShiftView(
    private val zone: TimeZone,
    private val settings: TimesheetSettings,
    private val now: Instant,
    private val employees: Map<ObjectId, Employee> = emptyMap(),
) {
    fun dto(shift: Shift, detail: Boolean = false): ShiftDto {
        val employee = employees[shift.employeeId]
        val end = shift.endAt
        return ShiftDto(
            id = shift.id.toHexString(),
            employeeId = shift.employeeId.toHexString(),
            employeeName = employee?.name,
            employeeNumber = employee?.number,
            status = shift.status.name,
            day = shift.day.toString(),
            startAt = shift.startAt.toString(),
            endAt = end?.toString(),
            startTime = ShiftMath.localTime(shift.startAt, zone),
            endTime = end?.let { ShiftMath.localTime(it, zone) },
            endsNextDay = end != null && ShiftMath.localDay(end, zone) > shift.day,
            breaks = shift.breaks.map { it.dto() },
            workedMinutes = shift.workedUntil(now),
            breakMinutes = if (shift.isOpen) ShiftMath.breakMinutes(shift.times, now) else shift.breakMinutes,
            onBreak = shift.onBreak,
            overdue = ShiftRules.isOverdue(shift, settings, now),
            siteId = shift.siteId?.toHexString(),
            siteName = shift.siteName,
            note = shift.note,
            flags = shift.flags,
            review = shift.review.status.name,
            reviewedBy = shift.review.by,
            reviewedAt = shift.review.at?.toString(),
            manual = shift.manual,
            edited = shift.edits.any { it.before != null },
            punches = if (detail) shift.punches.map { it.dto() } else null,
            edits = if (detail) shift.edits.map { it.dto() } else null,
        )
    }

    private fun ShiftBreak.dto() = BreakDto(
        startAt = startAt.toString(),
        endAt = endAt?.toString(),
        startTime = ShiftMath.localTime(startAt, zone),
        endTime = endAt?.let { ShiftMath.localTime(it, zone) },
    )

    private fun ShiftTimes.dto() = TimesDto(
        startAt = startAt.toString(),
        endAt = endAt?.toString(),
        startTime = ShiftMath.localTime(startAt, zone),
        endTime = endAt?.let { ShiftMath.localTime(it, zone) },
        breaks = breaks.map { it.dto() },
    )

    private fun ShiftEdit.dto() = EditDto(at.toString(), by, reason, before?.dto(), after.dto())

    private fun Punch.dto() = PunchDto(
        type = type.name,
        at = at.toString(),
        time = ShiftMath.localTime(at, zone),
        channel = channel.name,
        latitude = location?.latitude,
        longitude = location?.longitude,
        accuracyM = location?.accuracyM,
        mocked = location?.mocked == true,
        locationError = locationError,
        siteName = site?.siteName,
        siteDistanceM = site?.distanceM,
        inside = site?.inside,
        verified = verification.method == VerificationMethod.DEVICE_KEY,
        deviceName = verification.deviceName,
        flags = flags,
        by = by,
        recordedAt = recordedAt?.toString(),
    )

    companion object {
        /** The employees behind [shifts], each looked up once. */
        suspend fun employees(mongo: MongoModule, tenantId: ObjectId, shifts: List<Shift>): Map<ObjectId, Employee> {
            val repository = EmployeeRepository(mongo, tenantId)
            return shifts.map { it.employeeId }.distinct().mapNotNull { id -> repository.findById(id)?.let { id to it } }.toMap()
        }
    }
}

/** Sites with the name of the client each one belongs to. */
internal suspend fun siteDtos(mongo: MongoModule, tenantId: ObjectId, sites: List<WorkSite>): List<SiteDto> {
    val clients = ClientRepository(mongo, tenantId)
    val names = sites.mapNotNull { it.clientId }.distinct().mapNotNull { id -> clients.findById(id)?.let { id to it.name } }.toMap()
    return sites.map { it.dto(it.clientId?.let(names::get)) }
}
