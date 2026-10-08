package com.rfm.edubot.mobile.core.common

enum class BiometricAvailability {
    AVAILABLE,

    /** The phone can, but no fingerprint or face is set up on it. */
    NOT_ENROLLED,

    /** No strong biometric hardware, or it can't be used right now. */
    UNAVAILABLE,
}

enum class SignerError {
    CANCELLED,
    LOCKED_OUT,

    /** The fingerprints or face changed since the key was made, so it no longer opens. */
    KEY_INVALIDATED,
    NO_KEY,
    UNAVAILABLE,
    FAILED,
}

sealed interface KeyResult {
    /** [publicKeyBase64] as the platform exports it: a SubjectPublicKeyInfo (Android) or a raw X9.63 point (iOS). */
    data class Created(val publicKeyBase64: String) : KeyResult

    data class Failed(val error: SignerError) : KeyResult
}

sealed interface SignResult {
    /** An ECDSA P-256 / SHA-256 signature in DER, base64. */
    data class Signed(val signatureBase64: String) : SignResult

    data class Failed(val error: SignerError) : SignResult
}

/** The system prompt's copy, in the reader's language. */
data class BiometricPromptText(val title: String, val subtitle: String, val cancel: String)

/**
 * A key in the phone's secure hardware (Android Keystore, iOS Secure Enclave) that signs only right
 * after a strong biometric check. The fingerprint or face never reaches the app or the server: the
 * signature proves the phone's owner unlocked it for that one message.
 */
interface DeviceSigner {
    /** `ANDROID` or `IOS`, as the backend names them. */
    val platform: String

    /** What the team sees for this phone ("Google Pixel 8", "iPhone"). */
    val deviceName: String

    fun availability(): BiometricAvailability

    /** The public key behind [alias], or null when there is none on this phone. */
    fun publicKey(alias: String): String?

    /** Makes a new key for [alias], replacing any previous one. */
    suspend fun createKey(alias: String): KeyResult

    /** Shows the biometric prompt and, once it passes, signs [message] with [alias]'s key. */
    suspend fun sign(alias: String, message: String, prompt: BiometricPromptText): SignResult

    fun deleteKey(alias: String)
}
