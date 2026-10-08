package com.rfm.edubot.timesheets

import com.mongodb.ErrorCategory
import com.mongodb.MongoServerException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.getInstant
import com.rfm.edubot.crm.toDate
import com.rfm.edubot.crm.toInstantValue
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.timesheets.model.BiometricPolicy
import com.rfm.edubot.timesheets.model.DevicePlatform
import com.rfm.edubot.timesheets.model.GeofencePolicy
import com.rfm.edubot.timesheets.model.LocationPolicy
import com.rfm.edubot.timesheets.model.Punch
import com.rfm.edubot.timesheets.model.PunchChannel
import com.rfm.edubot.timesheets.model.PunchLocation
import com.rfm.edubot.timesheets.model.PunchType
import com.rfm.edubot.timesheets.model.PunchVerification
import com.rfm.edubot.timesheets.model.ReviewStatus
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.ShiftBreak
import com.rfm.edubot.timesheets.model.ShiftEdit
import com.rfm.edubot.timesheets.model.ShiftReview
import com.rfm.edubot.timesheets.model.ShiftStatus
import com.rfm.edubot.timesheets.model.ShiftTimes
import com.rfm.edubot.timesheets.model.SiteVerdict
import com.rfm.edubot.timesheets.model.TimeDevice
import com.rfm.edubot.timesheets.model.TimesheetSettings
import com.rfm.edubot.timesheets.model.VerificationMethod
import com.rfm.edubot.timesheets.model.WorkSite
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import kotlin.time.Duration.Companion.minutes

class ShiftRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    suspend fun findById(id: ObjectId): Shift? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toShift()

    suspend fun open(employeeId: ObjectId): Shift? =
        collection.find(scoped(Filters.and(Filters.eq("employeeId", employeeId), Filters.eq("status", ShiftStatus.OPEN.name)))).firstOrNull()?.toShift()

    /** Everyone clocked in right now, longest first. */
    suspend fun openShifts(): List<Shift> =
        collection.find(scoped(Filters.eq("status", ShiftStatus.OPEN.name))).sort(Document("startAt", 1)).limit(MAX_LIST).toList().map { it.toShift() }

    /** Shifts that started on a company-local day from [from] to [to], both included, in start order. */
    suspend fun list(from: LocalDate, to: LocalDate, employeeId: ObjectId? = null): List<Shift> {
        val filters = listOfNotNull(
            Filters.eq("tenantId", tenantId),
            Filters.gte("day", from.toString()),
            Filters.lte("day", to.toString()),
            employeeId?.let { Filters.eq("employeeId", it) },
        )
        return collection.find(Filters.and(filters)).sort(Document("startAt", 1)).limit(MAX_LIST).toList().map { it.toShift() }
    }

    suspend fun byIds(ids: Collection<ObjectId>): List<Shift> =
        if (ids.isEmpty()) emptyList() else collection.find(scoped(Filters.`in`("_id", ids))).toList().map { it.toShift() }

    suspend fun pendingCount(): Long =
        collection.countDocuments(scoped(Filters.and(Filters.eq("status", ShiftStatus.CLOSED.name), Filters.eq("review.status", ReviewStatus.PENDING.name))))

    /** Stores a clock-in; false when the employee already has an open shift (the partial unique index decides). */
    suspend fun insertOpen(shift: Shift): Boolean = try {
        collection.insertOne(shift.toDocument())
        true
    } catch (e: MongoServerException) {
        if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
        false
    }

    suspend fun insert(shift: Shift) {
        collection.insertOne(shift.toDocument())
    }

    /**
     * Applies [change] to shift [id] and stores the result only over the version it was computed from, reading
     * again when someone else wrote in between (two taps, the team editing while the employee clocks out).
     * Null when there is no such shift.
     */
    suspend fun update(id: ObjectId, change: (Shift) -> Transition): Transition? {
        repeat(RETRIES) {
            val current = findById(id) ?: return null
            when (val transition = change(current)) {
                is Transition.Refused -> return transition
                is Transition.Ok -> {
                    if (transition.shift == current) return transition
                    val next = transition.shift.copy(version = current.version + 1)
                    val stored = collection.replaceOne(
                        scoped(Filters.and(Filters.eq("_id", id), Filters.eq("version", current.version))),
                        next.toDocument(),
                    )
                    if (stored.matchedCount > 0) return Transition.Ok(next)
                }
            }
        }
        return Transition.Refused("conflict")
    }

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    companion object {
        const val COLLECTION = "timesheets.shifts"
        private const val MAX_LIST = 5000
        private const val RETRIES = 3
    }
}

class WorkSiteRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    suspend fun list(activeOnly: Boolean = false): List<WorkSite> {
        val filter = if (activeOnly) scoped(Filters.eq("active", true)) else Filters.eq("tenantId", tenantId)
        return collection.find(filter).sort(Document("name", 1)).limit(MAX_SITES).toList().map { it.toSite() }
    }

    suspend fun findById(id: ObjectId): WorkSite? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toSite()

    suspend fun count(): Long = collection.countDocuments(Filters.eq("tenantId", tenantId))

    suspend fun create(site: WorkSite): WorkSite {
        collection.insertOne(site.toDocument())
        return site
    }

    suspend fun replace(site: WorkSite): WorkSite? =
        site.takeIf { collection.replaceOne(scoped(Filters.eq("_id", site.id)), site.toDocument()).matchedCount > 0 }

    suspend fun delete(id: ObjectId): Boolean = collection.deleteOne(scoped(Filters.eq("_id", id))).deletedCount > 0

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    private fun WorkSite.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("name", name)
        .append("address", address)
        .append("latitude", latitude)
        .append("longitude", longitude)
        .append("radiusM", radiusM)
        .append("clientId", clientId)
        .append("active", active)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun Document.toSite() = WorkSite(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        name = getString("name").orEmpty(),
        address = getString("address"),
        latitude = (get("latitude") as Number).toDouble(),
        longitude = (get("longitude") as Number).toDouble(),
        radiusM = (get("radiusM") as? Number)?.toInt() ?: DEFAULT_RADIUS_M,
        clientId = get("clientId", ObjectId::class.java),
        active = getBoolean("active") ?: true,
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    companion object {
        const val COLLECTION = "timesheets.sites"
        const val MAX_SITES = 200
        const val DEFAULT_RADIUS_M = 150
        val RADIUS_M = 25..2000
    }
}

class TimeDeviceRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    /** The employee's phones, active first, then the ones revoked most recently. */
    suspend fun list(employeeId: ObjectId): List<TimeDevice> =
        collection.find(scoped(Filters.eq("employeeId", employeeId))).sort(Document("active", -1).append("createdAt", -1)).limit(20).toList().map { it.toDevice() }

    suspend fun active(employeeId: ObjectId): List<TimeDevice> =
        collection.find(scoped(Filters.and(Filters.eq("employeeId", employeeId), Filters.eq("active", true)))).toList().map { it.toDevice() }

    suspend fun findActive(employeeId: ObjectId, keyId: String): TimeDevice? =
        collection.find(scoped(Filters.and(Filters.eq("employeeId", employeeId), Filters.eq("keyId", keyId), Filters.eq("active", true)))).firstOrNull()?.toDevice()

    suspend fun findById(id: ObjectId): TimeDevice? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toDevice()

    /**
     * Makes this key the employee's one phone: the others stop counting (revoked as replaced). Enrolling the
     * same key again just brings it back.
     */
    suspend fun enroll(employeeId: ObjectId, userId: ObjectId?, keyId: String, publicKey: String, platform: DevicePlatform, name: String, now: Instant): TimeDevice {
        collection.updateMany(
            scoped(Filters.and(Filters.eq("employeeId", employeeId), Filters.eq("active", true), Filters.ne("keyId", keyId))),
            Updates.combine(Updates.set("active", false), Updates.set("revokedAt", now.toDate()), Updates.set("revokedBy", REPLACED)),
        )
        val doc = collection.findOneAndUpdate(
            scoped(Filters.and(Filters.eq("employeeId", employeeId), Filters.eq("keyId", keyId))),
            Updates.combine(
                Updates.set("userId", userId),
                Updates.set("publicKey", publicKey),
                Updates.set("platform", platform.name),
                Updates.set("name", name),
                Updates.set("active", true),
                Updates.unset("revokedAt"),
                Updates.unset("revokedBy"),
                Updates.setOnInsert("_id", ObjectId()),
                Updates.setOnInsert("createdAt", now.toDate()),
            ),
            FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
        )
        return doc!!.toDevice()
    }

    suspend fun touch(id: ObjectId, at: Instant) {
        collection.updateOne(scoped(Filters.eq("_id", id)), Updates.set("lastUsedAt", at.toDate()))
    }

    /** Null when there is no such phone; revoking twice keeps the first date. */
    suspend fun revoke(filter: Bson, by: String, now: Instant): TimeDevice? {
        collection.updateOne(
            scoped(Filters.and(filter, Filters.eq("active", true))),
            Updates.combine(Updates.set("active", false), Updates.set("revokedAt", now.toDate()), Updates.set("revokedBy", by)),
        )
        return collection.find(scoped(filter)).firstOrNull()?.toDevice()
    }

    suspend fun revoke(id: ObjectId, by: String, now: Instant): TimeDevice? = revoke(Filters.eq("_id", id), by, now)

    suspend fun revokeKey(employeeId: ObjectId, keyId: String, by: String, now: Instant): TimeDevice? =
        revoke(Filters.and(Filters.eq("employeeId", employeeId), Filters.eq("keyId", keyId)), by, now)

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    private fun Document.toDevice() = TimeDevice(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        employeeId = getObjectId("employeeId"),
        userId = get("userId", ObjectId::class.java),
        keyId = getString("keyId"),
        publicKey = getString("publicKey").orEmpty(),
        platform = enumOr(getString("platform"), DevicePlatform.ANDROID),
        name = getString("name").orEmpty(),
        active = getBoolean("active") ?: false,
        createdAt = getInstant("createdAt"),
        lastUsedAt = getDate("lastUsedAt")?.toInstantValue(),
        revokedAt = getDate("revokedAt")?.toInstantValue(),
        revokedBy = getString("revokedBy"),
    )

    companion object {
        const val COLLECTION = "timesheets.devices"
        /** `revokedBy` of a phone another enrollment replaced. */
        const val REPLACED = "replaced"
    }
}

/** One-time nonces a phone signs, so a captured signature can't be sent again. */
class TimeChallengeRepository(mongo: MongoModule) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    suspend fun issue(tenantId: ObjectId, employeeId: ObjectId, now: Instant): Pair<String, Instant> {
        val nonce = DeviceKeys.nonce()
        collection.insertOne(Document("_id", nonce).append("tenantId", tenantId).append("employeeId", employeeId).append("createdAt", now.toDate()))
        return nonce to now + VALIDITY
    }

    /** True once, for a nonce issued to this employee within the last [VALIDITY]. */
    suspend fun consume(tenantId: ObjectId, employeeId: ObjectId, nonce: String, now: Instant): Boolean {
        if (nonce.isBlank() || nonce.length > 100) return false
        val found = collection.findOneAndDelete(
            Filters.and(Filters.eq("_id", nonce), Filters.eq("tenantId", tenantId), Filters.eq("employeeId", employeeId)),
        ) ?: return false
        return found.getInstant("createdAt") >= now - VALIDITY
    }

    companion object {
        const val COLLECTION = "timesheets.challenges"
        val VALIDITY = 2.minutes
    }
}

class TimesheetSettingsRepository(mongo: MongoModule) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    suspend fun get(tenantId: ObjectId): TimesheetSettings =
        collection.find(Filters.eq("_id", tenantId)).firstOrNull()?.toSettings() ?: TimesheetSettings.DEFAULT

    suspend fun save(tenantId: ObjectId, settings: TimesheetSettings): TimesheetSettings {
        collection.replaceOne(
            Filters.eq("_id", tenantId),
            Document("_id", tenantId)
                .append("location", settings.location.name)
                .append("geofence", settings.geofence.name)
                .append("biometric", settings.biometric.name)
                .append("maxShiftHours", settings.maxShiftHours)
                .append("dailyHours", settings.dailyHours)
                .append("weeklyHours", settings.weeklyHours)
                .append("updatedAt", settings.updatedAt?.toDate())
                .append("updatedBy", settings.updatedBy),
            ReplaceOptions().upsert(true),
        )
        return settings
    }

    private fun Document.toSettings(): TimesheetSettings {
        val default = TimesheetSettings.DEFAULT
        return TimesheetSettings(
            location = enumOr(getString("location"), default.location),
            geofence = enumOr(getString("geofence"), default.geofence),
            biometric = enumOr(getString("biometric"), default.biometric),
            maxShiftHours = (get("maxShiftHours") as? Number)?.toInt() ?: default.maxShiftHours,
            dailyHours = (get("dailyHours") as? Number)?.toInt() ?: default.dailyHours,
            weeklyHours = (get("weeklyHours") as? Number)?.toInt() ?: default.weeklyHours,
            updatedAt = getDate("updatedAt")?.toInstantValue(),
            updatedBy = getString("updatedBy"),
        )
    }

    companion object {
        const val COLLECTION = "timesheets.settings"
    }
}

private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
    value?.let { raw -> enumValues<E>().firstOrNull { it.name == raw } } ?: default

internal fun Shift.toDocument(): Document = Document("_id", id)
    .append("tenantId", tenantId)
    .append("employeeId", employeeId)
    .append("status", status.name)
    .append("startAt", startAt.toDate())
    .append("endAt", endAt?.toDate())
    .append("breaks", breaks.map { it.toDocument() })
    .append("day", day.toString())
    .append("siteId", siteId)
    .append("siteName", siteName)
    .append("clientId", clientId)
    .append("note", note)
    .append("punches", punches.map { it.toDocument() })
    .append("flags", flags)
    .append("review", Document("status", review.status.name).append("by", review.by).append("at", review.at?.toDate()))
    .append("edits", edits.map { it.toDocument() })
    .append("manual", manual)
    .append("workedMinutes", workedMinutes)
    .append("breakMinutes", breakMinutes)
    .append("version", version)
    .append("createdAt", createdAt.toDate())
    .append("updatedAt", updatedAt.toDate())

internal fun Document.toShift(): Shift {
    val review = get("review", Document::class.java)
    return Shift(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        employeeId = getObjectId("employeeId"),
        status = enumOr(getString("status"), ShiftStatus.CLOSED),
        startAt = getInstant("startAt"),
        endAt = getDate("endAt")?.toInstantValue(),
        breaks = docs("breaks").map { it.toBreak() },
        day = LocalDate.parse(getString("day")),
        siteId = get("siteId", ObjectId::class.java),
        siteName = getString("siteName"),
        clientId = get("clientId", ObjectId::class.java),
        note = getString("note"),
        punches = docs("punches").map { it.toPunch() },
        flags = getList("flags", String::class.java).orEmpty(),
        review = ShiftReview(
            status = enumOr(review?.getString("status"), ReviewStatus.PENDING),
            by = review?.getString("by"),
            at = review?.getDate("at")?.toInstantValue(),
        ),
        edits = docs("edits").map { it.toEdit() },
        manual = getBoolean("manual") ?: false,
        workedMinutes = (get("workedMinutes") as? Number)?.toInt() ?: 0,
        breakMinutes = (get("breakMinutes") as? Number)?.toInt() ?: 0,
        version = (get("version") as? Number)?.toLong() ?: 0,
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )
}

private fun Document.docs(field: String): List<Document> = getList(field, Document::class.java).orEmpty()

private fun ShiftBreak.toDocument() = Document("startAt", startAt.toDate()).append("endAt", endAt?.toDate())

private fun Document.toBreak() = ShiftBreak(getInstant("startAt"), getDate("endAt")?.toInstantValue())

private fun ShiftTimes.toDocument() = Document("startAt", startAt.toDate())
    .append("endAt", endAt?.toDate())
    .append("breaks", breaks.map { it.toDocument() })

private fun Document.toTimes() = ShiftTimes(getInstant("startAt"), getDate("endAt")?.toInstantValue(), docs("breaks").map { it.toBreak() })

private fun ShiftEdit.toDocument() = Document("at", at.toDate())
    .append("by", by)
    .append("reason", reason)
    .append("before", before?.toDocument())
    .append("after", after.toDocument())

private fun Document.toEdit() = ShiftEdit(
    at = getInstant("at"),
    by = getString("by").orEmpty(),
    reason = getString("reason").orEmpty(),
    before = get("before", Document::class.java)?.toTimes(),
    after = get("after", Document::class.java).toTimes(),
)

private fun Punch.toDocument() = Document("type", type.name)
    .append("at", at.toDate())
    .append("channel", channel.name)
    .append(
        "location",
        location?.let {
            Document("latitude", it.latitude).append("longitude", it.longitude).append("accuracyM", it.accuracyM).append("mocked", it.mocked)
        },
    )
    .append("locationError", locationError)
    .append(
        "site",
        site?.let { Document("siteId", it.siteId).append("siteName", it.siteName).append("distanceM", it.distanceM).append("inside", it.inside) },
    )
    .append(
        "verification",
        Document("method", verification.method.name).append("deviceId", verification.deviceId).append("deviceName", verification.deviceName),
    )
    .append("flags", flags)
    .append("by", by)
    .append("recordedAt", recordedAt?.toDate())

private fun Document.toPunch(): Punch {
    val location = get("location", Document::class.java)
    val site = get("site", Document::class.java)
    val verification = get("verification", Document::class.java)
    return Punch(
        type = enumOr(getString("type"), PunchType.IN),
        at = getInstant("at"),
        channel = enumOr(getString("channel"), PunchChannel.WEB),
        location = location?.let {
            PunchLocation(
                latitude = (it.get("latitude") as? Number)?.toDouble(),
                longitude = (it.get("longitude") as? Number)?.toDouble(),
                accuracyM = (it.get("accuracyM") as? Number)?.toDouble(),
                mocked = it.getBoolean("mocked") ?: false,
            )
        },
        locationError = getString("locationError"),
        site = site?.let {
            SiteVerdict(
                siteId = it.getObjectId("siteId"),
                siteName = it.getString("siteName").orEmpty(),
                distanceM = (it.get("distanceM") as? Number)?.toInt() ?: 0,
                inside = it.getBoolean("inside") ?: false,
            )
        },
        verification = PunchVerification(
            method = enumOr(verification?.getString("method"), VerificationMethod.NONE),
            deviceId = verification?.get("deviceId", ObjectId::class.java),
            deviceName = verification?.getString("deviceName"),
        ),
        flags = getList("flags", String::class.java).orEmpty(),
        by = getString("by"),
        recordedAt = getDate("recordedAt")?.toInstantValue(),
    )
}

/** A policy name sent in a request, or null when it isn't one. */
internal fun locationPolicyOf(value: String?): LocationPolicy? = LocationPolicy.entries.firstOrNull { it.name == value?.trim()?.uppercase() }
internal fun geofencePolicyOf(value: String?): GeofencePolicy? = GeofencePolicy.entries.firstOrNull { it.name == value?.trim()?.uppercase() }
internal fun biometricPolicyOf(value: String?): BiometricPolicy? = BiometricPolicy.entries.firstOrNull { it.name == value?.trim()?.uppercase() }
