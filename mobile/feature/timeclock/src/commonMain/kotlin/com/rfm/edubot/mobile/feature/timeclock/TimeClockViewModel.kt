package com.rfm.edubot.mobile.feature.timeclock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.BiometricAvailability
import com.rfm.edubot.mobile.core.common.BiometricPromptText
import com.rfm.edubot.mobile.core.common.DeviceKeys
import com.rfm.edubot.mobile.core.common.DeviceSigner
import com.rfm.edubot.mobile.core.common.KeyResult
import com.rfm.edubot.mobile.core.common.LocationFailure
import com.rfm.edubot.mobile.core.common.LocationProvider
import com.rfm.edubot.mobile.core.common.LocationReading
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.common.SignResult
import com.rfm.edubot.mobile.core.common.SignerError
import com.rfm.edubot.mobile.core.data.TimeClockRepository
import com.rfm.edubot.mobile.core.model.EnrollDevice
import com.rfm.edubot.mobile.core.model.PunchLocation
import com.rfm.edubot.mobile.core.model.PunchRequest
import com.rfm.edubot.mobile.core.model.PunchVerification
import com.rfm.edubot.mobile.core.model.TimeClockStatus
import com.rfm.edubot.mobile.core.model.TimesheetPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the phone found wrong before anything reached the backend. */
enum class TimeClockProblem {
    LOCATION_DENIED,
    LOCATION_OFF,
    LOCATION_TIMEOUT,
    LOCATION_UNAVAILABLE,
    BIOMETRIC_NONE,
    BIOMETRIC_UNAVAILABLE,
    BIOMETRIC_LOCKED,
    BIOMETRIC_FAILED,
    KEY_CHANGED,
    SETUP_NEEDED,
}

enum class TimeClockStep { IDLE, LOCATING, CONFIRMING, SENDING }

/** What just went through, for the confirmation line. */
enum class TimeClockDone { IN, OUT, BREAK_START, BREAK_END, PHONE_READY, SHIFT_CLOSED }

/** Whether this phone confirms clock-ins with a fingerprint or face. */
enum class PhoneSetup {
    /** The company doesn't use biometrics. */
    NOT_USED,
    READY,

    /** It can: the employee only has to set it up. */
    NEEDED,

    /** No fingerprint or face set up on the phone itself. */
    NO_BIOMETRICS,
    UNSUPPORTED,
}

data class TimeClockUiState(
    val step: TimeClockStep = TimeClockStep.IDLE,
    val problem: TimeClockProblem? = null,
    val error: AppError? = null,
    val done: TimeClockDone? = null,
    val phone: PhoneSetup = PhoneSetup.NOT_USED,
) {
    val busy: Boolean get() = step != TimeClockStep.IDLE
}

/**
 * Clocking in from the phone: one location fix (only if the company uses location), then a signature
 * from the phone's biometric key over a nonce the backend issued (only if the company uses biometrics
 * and this phone is set up), then the punch. The backend's clock and rules decide; this only gathers
 * the evidence and stops early when the company's rules can't be met.
 */
class TimeClockViewModel(
    private val repository: TimeClockRepository,
    private val location: LocationProvider,
    private val signer: DeviceSigner,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(TimeClockUiState())
    val state: StateFlow<TimeClockUiState> = mutable.asStateFlow()

    private val alias = DeviceKeys.alias(repository.employeeId)

    fun load() = scope.launch {
        repository.status.load()
        repository.shifts.load()
        refreshPhone()
    }

    fun refresh() = scope.launch {
        mutable.update { it.copy(error = null, problem = null) }
        repository.status.refresh()
        repository.shifts.refresh()
        refreshPhone()
    }

    fun dismiss() = mutable.update { it.copy(done = null, problem = null, error = null) }

    fun punch(type: String, prompt: BiometricPromptText) = scope.launch {
        if (mutable.value.busy) return@launch
        val status = repository.status.state.value.value ?: return@launch
        val policy = status.policy
        mutable.update { it.copy(done = null, problem = null, error = null) }

        var fix: PunchLocation? = null
        var locationError: String? = null
        if (policy.location != TimesheetPolicy.POLICY_OFF) {
            step(TimeClockStep.LOCATING)
            when (val reading = location.current()) {
                is LocationReading.Fix -> fix = PunchLocation(reading.latitude, reading.longitude, reading.accuracyMeters, reading.mocked)
                is LocationReading.Failed -> {
                    locationError = reading.reason.name
                    val blocking = policy.geofence == TimesheetPolicy.GEOFENCE_BLOCK && status.sites.isNotEmpty() && type == PunchRequest.IN
                    if (policy.location == TimesheetPolicy.POLICY_REQUIRED || blocking) return@launch stop(reading.reason.problem())
                }
            }
        }

        var verification: PunchVerification? = null
        if (policy.biometric != TimesheetPolicy.POLICY_OFF) {
            when (val check = verify(type, status, prompt)) {
                is Check.Verified -> verification = check.verification
                Check.Skipped -> Unit
                is Check.Stopped -> return@launch stop(check.problem, check.error)
            }
        }

        step(TimeClockStep.SENDING)
        when (val sent = repository.punch(PunchRequest(type, fix, locationError, verification))) {
            is Outcome.Success -> finish(done = type.done())
            is Outcome.Failure -> {
                if ((sent.error as? AppError.Rejected)?.code == DEVICE_NOT_ENROLLED) refreshPhone()
                stop(error = sent.error)
            }
        }
    }

    /** Makes this phone's key and enrolls it, after one biometric check that also proves the key works. */
    fun setUpPhone(prompt: BiometricPromptText) = scope.launch {
        if (mutable.value.busy) return@launch
        mutable.update { it.copy(done = null, problem = null, error = null) }
        when (signer.availability()) {
            BiometricAvailability.NOT_ENROLLED -> return@launch stop(TimeClockProblem.BIOMETRIC_NONE)
            BiometricAvailability.UNAVAILABLE -> return@launch stop(TimeClockProblem.BIOMETRIC_UNAVAILABLE)
            BiometricAvailability.AVAILABLE -> Unit
        }
        step(TimeClockStep.CONFIRMING)
        val publicKey = when (val created = signer.createKey(alias)) {
            is KeyResult.Created -> DeviceKeys.subjectPublicKeyInfo(created.publicKeyBase64)
            is KeyResult.Failed -> return@launch stop(created.error.problem())
        } ?: return@launch stop(TimeClockProblem.BIOMETRIC_FAILED)
        val nonce = when (val challenge = repository.challenge()) {
            is Outcome.Success -> challenge.value.nonce
            is Outcome.Failure -> return@launch stop(error = challenge.error)
        }
        val signature = when (val signed = signer.sign(alias, DeviceKeys.message(nonce, ENROLL), prompt)) {
            is SignResult.Signed -> signed.signatureBase64
            is SignResult.Failed -> return@launch stop(signed.error.problem())
        }
        when (val enrolled = repository.enroll(EnrollDevice(signer.deviceName, signer.platform, publicKey, nonce, signature))) {
            is Outcome.Success -> finish(done = TimeClockDone.PHONE_READY)
            is Outcome.Failure -> stop(error = enrolled.error)
        }
    }

    /** The employee forgot to clock out: [end] is `HH:mm` on the company's clock. */
    fun closeForgotten(shiftId: String, end: String) = scope.launch {
        if (mutable.value.busy) return@launch
        val time = end.trim()
        if (!TIME.matches(time)) return@launch stop(error = AppError.Rejected(INVALID_TIME))
        step(TimeClockStep.SENDING)
        when (val closed = repository.closeForgotten(shiftId, time)) {
            is Outcome.Success -> finish(done = TimeClockDone.SHIFT_CLOSED)
            is Outcome.Failure -> stop(error = closed.error)
        }
    }

    private sealed interface Check {
        data class Verified(val verification: PunchVerification) : Check
        data object Skipped : Check
        data class Stopped(val problem: TimeClockProblem? = null, val error: AppError? = null) : Check
    }

    /**
     * A signature for [type] when this phone can give one. Without biometrics or a set-up phone the punch
     * goes unverified (the backend flags it) unless the company requires it.
     */
    private suspend fun verify(type: String, status: TimeClockStatus, prompt: BiometricPromptText): Check {
        val required = status.policy.biometric == TimesheetPolicy.POLICY_REQUIRED
        val unavailable = when (signer.availability()) {
            BiometricAvailability.AVAILABLE -> null
            BiometricAvailability.NOT_ENROLLED -> TimeClockProblem.BIOMETRIC_NONE
            BiometricAvailability.UNAVAILABLE -> TimeClockProblem.BIOMETRIC_UNAVAILABLE
        }
        if (unavailable != null) return if (required) Check.Stopped(unavailable) else Check.Skipped
        val keyId = enrolledKeyId(status) ?: return if (required) Check.Stopped(TimeClockProblem.SETUP_NEEDED) else Check.Skipped
        step(TimeClockStep.CONFIRMING)
        val nonce = when (val challenge = repository.challenge()) {
            is Outcome.Success -> challenge.value.nonce
            is Outcome.Failure -> return Check.Stopped(error = challenge.error)
        }
        return when (val signed = signer.sign(alias, DeviceKeys.message(nonce, type), prompt)) {
            is SignResult.Signed -> Check.Verified(PunchVerification(keyId, nonce, signed.signatureBase64))
            is SignResult.Failed -> {
                if (signed.error == SignerError.KEY_INVALIDATED) {
                    signer.deleteKey(alias)
                    refreshPhone()
                }
                Check.Stopped(signed.error.problem())
            }
        }
    }

    /** This phone's key id, when the backend lists it among the employee's active phones. */
    private fun enrolledKeyId(status: TimeClockStatus): String? {
        val keyId = signer.publicKey(alias)?.let(DeviceKeys::keyId) ?: return null
        return keyId.takeIf { id -> status.devices.any { it.active && it.keyId == id } }
    }

    private fun refreshPhone() {
        val status = repository.status.state.value.value
        val phone = when {
            status == null || status.policy.biometric == TimesheetPolicy.POLICY_OFF -> PhoneSetup.NOT_USED
            else -> when (signer.availability()) {
                BiometricAvailability.UNAVAILABLE -> PhoneSetup.UNSUPPORTED
                BiometricAvailability.NOT_ENROLLED -> PhoneSetup.NO_BIOMETRICS
                BiometricAvailability.AVAILABLE -> if (enrolledKeyId(status) != null) PhoneSetup.READY else PhoneSetup.NEEDED
            }
        }
        mutable.update { it.copy(phone = phone) }
    }

    private fun step(step: TimeClockStep) = mutable.update { it.copy(step = step) }

    private fun finish(done: TimeClockDone?) {
        refreshPhone()
        mutable.update { it.copy(step = TimeClockStep.IDLE, done = done, problem = null, error = null) }
    }

    private fun stop(problem: TimeClockProblem? = null, error: AppError? = null) =
        mutable.update { it.copy(step = TimeClockStep.IDLE, problem = problem, error = error) }

    private fun LocationFailure.problem(): TimeClockProblem = when (this) {
        LocationFailure.PERMISSION_DENIED -> TimeClockProblem.LOCATION_DENIED
        LocationFailure.DISABLED -> TimeClockProblem.LOCATION_OFF
        LocationFailure.TIMEOUT -> TimeClockProblem.LOCATION_TIMEOUT
        LocationFailure.UNAVAILABLE -> TimeClockProblem.LOCATION_UNAVAILABLE
    }

    /** Cancelling the prompt is a choice, not a failure: it just stops, with nothing to show. */
    private fun SignerError.problem(): TimeClockProblem? = when (this) {
        SignerError.CANCELLED -> null
        SignerError.LOCKED_OUT -> TimeClockProblem.BIOMETRIC_LOCKED
        SignerError.KEY_INVALIDATED -> TimeClockProblem.KEY_CHANGED
        SignerError.NO_KEY -> TimeClockProblem.SETUP_NEEDED
        SignerError.UNAVAILABLE -> TimeClockProblem.BIOMETRIC_UNAVAILABLE
        SignerError.FAILED -> TimeClockProblem.BIOMETRIC_FAILED
    }

    private fun String.done(): TimeClockDone = when (this) {
        PunchRequest.IN -> TimeClockDone.IN
        PunchRequest.BREAK_START -> TimeClockDone.BREAK_START
        PunchRequest.BREAK_END -> TimeClockDone.BREAK_END
        else -> TimeClockDone.OUT
    }

    private companion object {
        const val ENROLL = "ENROLL"
        const val DEVICE_NOT_ENROLLED = "device_not_enrolled"
        const val INVALID_TIME = "invalid_time"
        val TIME = Regex("""([01]\d|2[0-3]):[0-5]\d""")
    }
}
