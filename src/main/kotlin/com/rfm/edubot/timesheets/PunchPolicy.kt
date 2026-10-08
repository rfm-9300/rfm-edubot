package com.rfm.edubot.timesheets

import com.rfm.edubot.timesheets.model.BiometricPolicy
import com.rfm.edubot.timesheets.model.GeofencePolicy
import com.rfm.edubot.timesheets.model.LocationPolicy
import com.rfm.edubot.timesheets.model.PunchLocation
import com.rfm.edubot.timesheets.model.PunchType
import com.rfm.edubot.timesheets.model.ShiftFlags
import com.rfm.edubot.timesheets.model.SiteVerdict
import com.rfm.edubot.timesheets.model.TimesheetSettings
import com.rfm.edubot.timesheets.model.WorkSite
import kotlin.math.round

/** What an employee's punch brings: where the phone or browser said it was, and whether an enrolled phone signed it. */
data class PunchInput(
    val type: PunchType,
    val location: PunchLocation?,
    val verified: Boolean,
)

sealed interface PunchDecision {
    /** [location] is what to store: nothing when the company doesn't use location, rounded to about a metre otherwise. */
    data class Accept(val location: PunchLocation?, val site: SiteVerdict?, val flags: List<String>) : PunchDecision

    /** [status] is the HTTP status; [site] is the nearest site when the punch was outside every one. */
    data class Refuse(val error: String, val status: Int, val site: SiteVerdict? = null) : PunchDecision
}

/**
 * The company's rules applied to one punch. Only a clock-in is refused for where it happened: refusing a
 * break or clock-out after someone left the site would leave them clocked in, so those are flagged instead.
 */
object PunchPolicy {
    /** A fix less precise than this can't place someone on a site with any confidence. */
    const val LOW_ACCURACY_M = 150.0

    fun decide(settings: TimesheetSettings, sites: List<WorkSite>, input: PunchInput): PunchDecision {
        if (settings.biometric == BiometricPolicy.REQUIRED && !input.verified) {
            return PunchDecision.Refuse("verification_required", 403)
        }
        val location = input.location.takeIf { settings.location != LocationPolicy.OFF }
        if (location != null && !location.isValid()) return PunchDecision.Refuse("invalid_location", 400)
        val fenced = sites.isNotEmpty() && settings.location != LocationPolicy.OFF
        val blocks = fenced && settings.geofence == GeofencePolicy.BLOCK && input.type == PunchType.IN
        if (location == null && (settings.location == LocationPolicy.REQUIRED || blocks)) {
            return PunchDecision.Refuse("location_required", 400)
        }

        val flags = mutableListOf<String>()
        if (location == null && settings.location != LocationPolicy.OFF) flags += ShiftFlags.NO_LOCATION
        var verdict: SiteVerdict? = null
        if (location != null) {
            if ((location.accuracyM ?: 0.0) > LOW_ACCURACY_M) flags += ShiftFlags.LOW_ACCURACY
            if (location.mocked) {
                if (blocks) return PunchDecision.Refuse("mock_location", 409)
                flags += ShiftFlags.MOCK_LOCATION
            }
            if (fenced) {
                verdict = Geofence.match(sites, location.latitude!!, location.longitude!!)
                if (verdict != null && !verdict.inside) {
                    if (blocks) return PunchDecision.Refuse("outside_sites", 409, verdict)
                    flags += ShiftFlags.OUTSIDE_SITE
                }
            }
        }
        if (!input.verified && settings.biometric == BiometricPolicy.OPTIONAL) flags += ShiftFlags.UNVERIFIED
        return PunchDecision.Accept(location?.rounded(), verdict, flags)
    }

    private fun PunchLocation.isValid(): Boolean {
        val lat = latitude ?: return false
        val lng = longitude ?: return false
        val accuracy = accuracyM
        return Geofence.validCoordinates(lat, lng) && (accuracy == null || (accuracy.isFinite() && accuracy >= 0))
    }

    /** Five decimals is about a metre: enough to check a site, no more precise than a phone's fix anyway. */
    private fun PunchLocation.rounded() = copy(
        latitude = latitude?.let { round(it * 100_000) / 100_000 },
        longitude = longitude?.let { round(it * 100_000) / 100_000 },
        accuracyM = accuracyM?.let { round(it * 10) / 10 },
    )
}
