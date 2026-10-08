package com.rfm.edubot.timesheets

import com.rfm.edubot.timesheets.model.SiteVerdict
import com.rfm.edubot.timesheets.model.WorkSite
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Distances between a punch and the company's work sites. */
object Geofence {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine): plenty for radii of tens of metres to a few kilometres. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * The site a point is in (the nearest one, when sites overlap), or else the nearest site with
     * `inside = false`. Null without sites. Inside means within the site's radius of its centre.
     */
    fun match(sites: List<WorkSite>, latitude: Double, longitude: Double): SiteVerdict? {
        val measured = sites.map { it to distanceM(latitude, longitude, it.latitude, it.longitude) }
        val (site, distance) = measured.filter { (site, d) -> d <= site.radiusM }.minByOrNull { it.second }
            ?: measured.minByOrNull { it.second }
            ?: return null
        return SiteVerdict(site.id, site.name, distance.roundToInt(), inside = distance <= site.radiusM)
    }

    fun validCoordinates(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}
