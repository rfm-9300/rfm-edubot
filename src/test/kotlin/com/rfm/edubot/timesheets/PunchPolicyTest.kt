package com.rfm.edubot.timesheets

import com.rfm.edubot.timesheets.model.BiometricPolicy
import com.rfm.edubot.timesheets.model.GeofencePolicy
import com.rfm.edubot.timesheets.model.LocationPolicy
import com.rfm.edubot.timesheets.model.PunchLocation
import com.rfm.edubot.timesheets.model.PunchType
import com.rfm.edubot.timesheets.model.ShiftFlags
import com.rfm.edubot.timesheets.model.TimesheetSettings
import com.rfm.edubot.timesheets.model.WorkSite
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PunchPolicyTest {
    private val tenantId = ObjectId()
    private val epoch = Instant.fromEpochMilliseconds(0)
    private fun site(name: String, lat: Double, lng: Double, radius: Int = 150) =
        WorkSite(tenantId = tenantId, name = name, latitude = lat, longitude = lng, radiusM = radius, createdAt = epoch, updatedAt = epoch)

    private val office = site("Escritório", 38.72230, -9.13930)
    private val building = site("Obra Rua do Sol", 38.73000, -9.15000, radius = 300)
    private val atOffice = PunchLocation(38.72240, -9.13935, 12.0)
    /** About 1.4 km from the building site, 2.7 km from the office. */
    private val farAway = PunchLocation(38.74000, -9.16000, 10.0)

    private fun decide(settings: TimesheetSettings, type: PunchType = PunchType.IN, location: PunchLocation? = atOffice, verified: Boolean = false, sites: List<WorkSite> = listOf(office, building)) =
        PunchPolicy.decide(settings, sites, PunchInput(type, location, verified))

    @Test
    fun `geofence math measures metres and picks the site a punch is in`() {
        assertEquals(274.0, Geofence.distanceM(38.7223, -9.1393, 41.1579, -8.6291) / 1000, 2.0)
        assertEquals(111.2, Geofence.distanceM(38.0, -9.0, 38.001, -9.0), 0.5)
        val verdict = Geofence.match(listOf(office, building), atOffice.latitude!!, atOffice.longitude!!)!!
        assertEquals("Escritório", verdict.siteName)
        assertTrue(verdict.inside)
        val outside = Geofence.match(listOf(office, building), farAway.latitude!!, farAway.longitude!!)!!
        assertEquals(false, outside.inside)
        assertEquals("Obra Rua do Sol", outside.siteName)
        assertEquals(1410.0, outside.distanceM.toDouble(), 30.0)
        assertNull(Geofence.match(emptyList(), 0.0, 0.0))
    }

    @Test
    fun `overlapping sites resolve to the nearest one the punch is inside`() {
        val big = site("Bairro", 38.72230, -9.13930, radius = 2000)
        val small = site("Loja", 38.72500, -9.14000, radius = 100)
        val verdict = Geofence.match(listOf(big, small), 38.72505, -9.14002)!!
        assertEquals("Loja", verdict.siteName)
    }

    @Test
    fun `by default a punch is kept and flagged for what it lacks`() {
        val decision = assertIs<PunchDecision.Accept>(decide(TimesheetSettings.DEFAULT, location = null))
        assertEquals(listOf(ShiftFlags.NO_LOCATION, ShiftFlags.UNVERIFIED), decision.flags)
        val verified = assertIs<PunchDecision.Accept>(decide(TimesheetSettings.DEFAULT, verified = true))
        assertEquals(emptyList(), verified.flags)
        assertEquals("Escritório", verified.site?.siteName)
    }

    @Test
    fun `a company that doesn't use location stores none, even when sent`() {
        val decision = assertIs<PunchDecision.Accept>(decide(TimesheetSettings(location = LocationPolicy.OFF, biometric = BiometricPolicy.OFF), location = farAway))
        assertNull(decision.location)
        assertNull(decision.site)
        assertEquals(emptyList(), decision.flags)
    }

    @Test
    fun `required location and biometrics refuse what is missing`() {
        assertEquals("location_required", assertIs<PunchDecision.Refuse>(decide(TimesheetSettings(location = LocationPolicy.REQUIRED), location = null)).error)
        val unverified = assertIs<PunchDecision.Refuse>(decide(TimesheetSettings(biometric = BiometricPolicy.REQUIRED)))
        assertEquals("verification_required", unverified.error)
        assertEquals(403, unverified.status)
        assertEquals("invalid_location", assertIs<PunchDecision.Refuse>(decide(TimesheetSettings.DEFAULT, location = PunchLocation(91.0, 0.0, 5.0))).error)
        assertEquals("invalid_location", assertIs<PunchDecision.Refuse>(decide(TimesheetSettings.DEFAULT, location = PunchLocation(Double.NaN, 0.0, 5.0))).error)
    }

    @Test
    fun `blocking sites refuses a clock-in outside them but only flags a clock-out`() {
        val block = TimesheetSettings(geofence = GeofencePolicy.BLOCK, biometric = BiometricPolicy.OFF)
        val refused = assertIs<PunchDecision.Refuse>(decide(block, location = farAway))
        assertEquals("outside_sites", refused.error)
        assertEquals(409, refused.status)
        assertEquals("Obra Rua do Sol", refused.site?.siteName)
        val out = assertIs<PunchDecision.Accept>(decide(block, type = PunchType.OUT, location = farAway))
        assertEquals(listOf(ShiftFlags.OUTSIDE_SITE), out.flags)
        assertEquals("location_required", assertIs<PunchDecision.Refuse>(decide(block, location = null)).error)
        assertIs<PunchDecision.Accept>(decide(block, location = null, sites = emptyList()))
    }

    @Test
    fun `a simulated location can't clock in where sites block, and is flagged elsewhere`() {
        val mocked = atOffice.copy(mocked = true)
        assertEquals("mock_location", assertIs<PunchDecision.Refuse>(decide(TimesheetSettings(geofence = GeofencePolicy.BLOCK), location = mocked)).error)
        val flagged = assertIs<PunchDecision.Accept>(decide(TimesheetSettings.DEFAULT, location = mocked, verified = true))
        assertEquals(listOf(ShiftFlags.MOCK_LOCATION), flagged.flags)
    }

    @Test
    fun `an imprecise fix is flagged and the stored position is rounded to about a metre`() {
        val decision = assertIs<PunchDecision.Accept>(decide(TimesheetSettings.DEFAULT, location = PunchLocation(38.7224012345, -9.1393598765, 480.26), verified = true))
        assertEquals(listOf(ShiftFlags.LOW_ACCURACY), decision.flags)
        assertEquals(38.7224, decision.location?.latitude)
        assertEquals(-9.13936, decision.location?.longitude)
        assertEquals(480.3, decision.location?.accuracyM)
    }
}
