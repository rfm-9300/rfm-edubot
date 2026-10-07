package com.rfm.edubot.mobile.feature.timeclock

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.BiometricAvailability
import com.rfm.edubot.mobile.core.common.BiometricPromptText
import com.rfm.edubot.mobile.core.common.DeviceKeys
import com.rfm.edubot.mobile.core.common.DeviceSigner
import com.rfm.edubot.mobile.core.common.InMemorySnapshotStore
import com.rfm.edubot.mobile.core.common.KeyResult
import com.rfm.edubot.mobile.core.common.LocationFailure
import com.rfm.edubot.mobile.core.common.LocationProvider
import com.rfm.edubot.mobile.core.common.LocationReading
import com.rfm.edubot.mobile.core.common.SignResult
import com.rfm.edubot.mobile.core.common.SignerError
import com.rfm.edubot.mobile.core.data.SnapshotCache
import com.rfm.edubot.mobile.core.data.TimeClockRepository
import com.rfm.edubot.mobile.core.model.CloseShift
import com.rfm.edubot.mobile.core.model.EnrollDevice
import com.rfm.edubot.mobile.core.model.PunchRequest
import com.rfm.edubot.mobile.core.model.PunchResult
import com.rfm.edubot.mobile.core.model.Shift
import com.rfm.edubot.mobile.core.model.TimeChallenge
import com.rfm.edubot.mobile.core.model.TimeClockStatus
import com.rfm.edubot.mobile.core.model.TimeDevice
import com.rfm.edubot.mobile.core.model.TimesheetPolicy
import com.rfm.edubot.mobile.core.model.WorkSite
import com.rfm.edubot.mobile.core.network.TimeClockApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
private val RAW_PUBLIC_KEY = Base64.Default.encode(byteArrayOf(4) + ByteArray(64) { (it + 1).toByte() })

private val PROMPT = BiometricPromptText("Confirm it’s you", "Clock in", "Cancel")

private class FakeLocation(var reading: LocationReading = LocationReading.Fix(38.7223, -9.1393, 12.0, mocked = false)) : LocationProvider {
    var calls = 0

    override suspend fun current(): LocationReading {
        calls += 1
        return reading
    }
}

private class FakeSigner(
    var available: BiometricAvailability = BiometricAvailability.AVAILABLE,
    var signResult: SignResult = SignResult.Signed("c2lnbmVk"),
) : DeviceSigner {
    override val platform = "ANDROID"
    override val deviceName = "Google Pixel 8"
    val keys = mutableMapOf<String, String>()
    val signed = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    override fun availability() = available

    override fun publicKey(alias: String): String? = keys[alias]

    override suspend fun createKey(alias: String): KeyResult {
        keys[alias] = RAW_PUBLIC_KEY
        return KeyResult.Created(RAW_PUBLIC_KEY)
    }

    override suspend fun sign(alias: String, message: String, prompt: BiometricPromptText): SignResult {
        signed += message
        return signResult
    }

    override fun deleteKey(alias: String) {
        deleted += alias
        keys.remove(alias)
    }
}

private class FakeApi(var policy: TimesheetPolicy, var sites: List<WorkSite> = emptyList()) : TimeClockApi {
    val punches = mutableListOf<PunchRequest>()
    val enrolled = mutableListOf<EnrollDevice>()
    val closed = mutableListOf<Pair<String, String>>()
    var devices = emptyList<TimeDevice>()

    private fun statusOf(state: String = TimeClockStatus.STATE_OFF) =
        TimeClockStatus(policy = policy, state = state, sites = sites, devices = devices, serverTime = "2026-10-07T08:00:00Z")

    private val shift = Shift(id = "s1", employeeId = "e1", status = "OPEN", day = "2026-10-07", startAt = "2026-10-07T07:00:00Z", startTime = "08:00")

    override suspend fun status(): TimeClockStatus = statusOf()

    override suspend fun shifts(): List<Shift> = emptyList()

    override suspend fun challenge() = TimeChallenge("n1", "2026-10-07T08:02:00Z")

    override suspend fun punch(request: PunchRequest): PunchResult {
        punches += request
        return PunchResult(statusOf(TimeClockStatus.STATE_WORKING), shift)
    }

    override suspend fun closeForgotten(shiftId: String, request: CloseShift): PunchResult {
        closed += shiftId to request.end
        return PunchResult(statusOf(), shift.copy(status = "CLOSED", endTime = request.end))
    }

    override suspend fun enroll(request: EnrollDevice): TimeDevice {
        enrolled += request
        val device = TimeDevice("d1", DeviceKeys.keyId(request.publicKey)!!, request.name, request.platform)
        devices = listOf(device)
        return device
    }

    override suspend fun removeDevice(keyId: String) = Unit
}

private class Harness(
    policy: TimesheetPolicy,
    sites: List<WorkSite> = emptyList(),
    scope: CoroutineScope,
) {
    val api = FakeApi(policy, sites)
    val location = FakeLocation()
    val signer = FakeSigner()
    val alias = DeviceKeys.alias("e1")
    val repository = TimeClockRepository(api, SnapshotCache(InMemorySnapshotStore()), "e1")
    val vm = TimeClockViewModel(repository, location, signer, scope)

    /** This phone holds a key the backend lists among the employee's phones. */
    fun enrolled() {
        signer.keys[alias] = RAW_PUBLIC_KEY
        api.devices = listOf(TimeDevice("d1", DeviceKeys.keyId(RAW_PUBLIC_KEY)!!, "Google Pixel 8", "ANDROID"))
    }
}

private fun TestScope.harness(policy: TimesheetPolicy, sites: List<WorkSite> = emptyList()) = Harness(policy, sites, backgroundScope)

private val SITE = WorkSite("w1", "Escritório Lisboa", 38.7223, -9.1393, 150)

class TimeClockViewModelTest {
    private val nothingAsked = TimesheetPolicy(location = TimesheetPolicy.POLICY_OFF, biometric = TimesheetPolicy.POLICY_OFF)

    @Test
    fun `a company that records neither location nor biometrics gets a bare punch`() = runTest {
        val h = harness(nothingAsked)
        h.vm.load().join()
        h.vm.punch(PunchRequest.IN, PROMPT).join()

        val sent = h.api.punches.single()
        assertNull(sent.location)
        assertNull(sent.verification)
        assertEquals(0, h.location.calls, "the phone is not asked where it is")
        assertEquals(TimeClockDone.IN, h.vm.state.value.done)
    }

    @Test
    fun `an optional location that fails still punches and says why`() = runTest {
        val h = harness(nothingAsked.copy(location = TimesheetPolicy.POLICY_OPTIONAL))
        h.location.reading = LocationReading.Failed(LocationFailure.TIMEOUT)
        h.vm.load().join()
        h.vm.punch(PunchRequest.IN, PROMPT).join()

        val sent = h.api.punches.single()
        assertNull(sent.location)
        assertEquals("TIMEOUT", sent.locationError)
    }

    @Test
    fun `a required location stops on the phone when there is none`() = runTest {
        val h = harness(nothingAsked.copy(location = TimesheetPolicy.POLICY_REQUIRED))
        h.location.reading = LocationReading.Failed(LocationFailure.PERMISSION_DENIED)
        h.vm.load().join()
        h.vm.punch(PunchRequest.IN, PROMPT).join()

        assertTrue(h.api.punches.isEmpty())
        assertEquals(TimeClockProblem.LOCATION_DENIED, h.vm.state.value.problem)
        assertEquals(TimeClockStep.IDLE, h.vm.state.value.step)
    }

    @Test
    fun `a blocking geofence stops a clock-in without a location but never a clock-out`() = runTest {
        val h = harness(nothingAsked.copy(location = TimesheetPolicy.POLICY_OPTIONAL, geofence = TimesheetPolicy.GEOFENCE_BLOCK), listOf(SITE))
        h.location.reading = LocationReading.Failed(LocationFailure.DISABLED)
        h.vm.load().join()

        h.vm.punch(PunchRequest.IN, PROMPT).join()
        assertTrue(h.api.punches.isEmpty())
        assertEquals(TimeClockProblem.LOCATION_OFF, h.vm.state.value.problem)

        h.vm.punch(PunchRequest.OUT, PROMPT).join()
        assertEquals(listOf(PunchRequest.OUT), h.api.punches.map { it.type }, "nobody is kept from leaving")
    }

    @Test
    fun `the fix goes with the punch as the phone reported it`() = runTest {
        val h = harness(nothingAsked.copy(location = TimesheetPolicy.POLICY_REQUIRED))
        h.location.reading = LocationReading.Fix(38.7, -9.1, 35.0, mocked = true)
        h.vm.load().join()
        h.vm.punch(PunchRequest.IN, PROMPT).join()

        val sent = h.api.punches.single().location!!
        assertEquals(38.7, sent.latitude)
        assertEquals(35.0, sent.accuracyM)
        assertTrue(sent.mocked, "the backend flags a mocked fix, so the phone must not hide it")
    }

    @Test
    fun `a set-up phone signs the backend's nonce with the action`() = runTest {
        val h = harness(nothingAsked.copy(biometric = TimesheetPolicy.POLICY_REQUIRED))
        h.enrolled()
        h.vm.load().join()
        assertEquals(PhoneSetup.READY, h.vm.state.value.phone)

        h.vm.punch(PunchRequest.BREAK_START, PROMPT).join()

        assertEquals(listOf("n1:BREAK_START"), h.signer.signed)
        val verification = h.api.punches.single().verification!!
        assertEquals(DeviceKeys.keyId(RAW_PUBLIC_KEY), verification.keyId)
        assertEquals("n1", verification.nonce)
        assertEquals("c2lnbmVk", verification.signature)
    }

    @Test
    fun `without a fingerprint or face on the phone an optional check goes unverified`() = runTest {
        val h = harness(nothingAsked.copy(biometric = TimesheetPolicy.POLICY_OPTIONAL))
        h.enrolled()
        h.signer.available = BiometricAvailability.NOT_ENROLLED
        h.vm.load().join()
        h.vm.punch(PunchRequest.IN, PROMPT).join()

        assertNull(h.api.punches.single().verification)
        assertTrue(h.signer.signed.isEmpty())
    }

    @Test
    fun `a required check on a phone that is not set up asks for the setup first`() = runTest {
        val h = harness(nothingAsked.copy(biometric = TimesheetPolicy.POLICY_REQUIRED))
        h.vm.load().join()
        assertEquals(PhoneSetup.NEEDED, h.vm.state.value.phone)

        h.vm.punch(PunchRequest.IN, PROMPT).join()

        assertTrue(h.api.punches.isEmpty())
        assertEquals(TimeClockProblem.SETUP_NEEDED, h.vm.state.value.problem)
    }

    @Test
    fun `a key the backend no longer lists counts as not set up`() = runTest {
        val h = harness(nothingAsked.copy(biometric = TimesheetPolicy.POLICY_REQUIRED))
        h.signer.keys[h.alias] = RAW_PUBLIC_KEY
        h.vm.load().join()

        assertEquals(PhoneSetup.NEEDED, h.vm.state.value.phone, "the team revoked this phone")
    }

    @Test
    fun `cancelling the prompt stops without an error`() = runTest {
        val h = harness(nothingAsked.copy(biometric = TimesheetPolicy.POLICY_REQUIRED))
        h.enrolled()
        h.signer.signResult = SignResult.Failed(SignerError.CANCELLED)
        h.vm.load().join()
        h.vm.punch(PunchRequest.IN, PROMPT).join()

        val state = h.vm.state.value
        assertTrue(h.api.punches.isEmpty())
        assertNull(state.problem)
        assertNull(state.error)
        assertEquals(TimeClockStep.IDLE, state.step)
    }

    @Test
    fun `changed biometrics drop the key and ask to set the phone up again`() = runTest {
        val h = harness(nothingAsked.copy(biometric = TimesheetPolicy.POLICY_REQUIRED))
        h.enrolled()
        h.signer.signResult = SignResult.Failed(SignerError.KEY_INVALIDATED)
        h.vm.load().join()
        h.vm.punch(PunchRequest.IN, PROMPT).join()

        assertEquals(listOf(h.alias), h.signer.deleted)
        assertEquals(TimeClockProblem.KEY_CHANGED, h.vm.state.value.problem)
        assertEquals(PhoneSetup.NEEDED, h.vm.state.value.phone)
    }

    @Test
    fun `setting up enrolls the key as a SubjectPublicKeyInfo signed over the nonce`() = runTest {
        val h = harness(nothingAsked.copy(biometric = TimesheetPolicy.POLICY_OPTIONAL))
        h.vm.load().join()
        h.vm.setUpPhone(PROMPT).join()

        val enrolled = h.api.enrolled.single()
        assertEquals(DeviceKeys.subjectPublicKeyInfo(RAW_PUBLIC_KEY), enrolled.publicKey)
        assertEquals("n1", enrolled.nonce)
        assertEquals(listOf("n1:ENROLL"), h.signer.signed)
        assertEquals(TimeClockDone.PHONE_READY, h.vm.state.value.done)
        assertEquals(PhoneSetup.READY, h.vm.state.value.phone)
    }

    @Test
    fun `setting up a phone without biometrics explains rather than tries`() = runTest {
        val h = harness(nothingAsked.copy(biometric = TimesheetPolicy.POLICY_OPTIONAL))
        h.signer.available = BiometricAvailability.NOT_ENROLLED
        h.vm.load().join()
        h.vm.setUpPhone(PROMPT).join()

        assertTrue(h.api.enrolled.isEmpty())
        assertEquals(TimeClockProblem.BIOMETRIC_NONE, h.vm.state.value.problem)
    }

    @Test
    fun `a forgotten clock-out needs a real time of day`() = runTest {
        val h = harness(nothingAsked)
        h.vm.load().join()

        h.vm.closeForgotten("s1", "25:00").join()
        assertTrue(h.api.closed.isEmpty())
        assertEquals(AppError.Rejected("invalid_time"), h.vm.state.value.error)

        h.vm.closeForgotten("s1", " 18:30 ").join()
        assertEquals(listOf("s1" to "18:30"), h.api.closed)
        assertEquals(TimeClockDone.SHIFT_CLOSED, h.vm.state.value.done)
    }
}
