package com.rfm.edubot.mobile

import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.rfm.edubot.mobile.core.common.BiometricAvailability
import com.rfm.edubot.mobile.core.common.BiometricPromptText
import com.rfm.edubot.mobile.core.common.DeviceSigner
import com.rfm.edubot.mobile.core.common.KeyResult
import com.rfm.edubot.mobile.core.common.SignResult
import com.rfm.edubot.mobile.core.common.SignerError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.resume

/**
 * EC P-256 keys in the Android Keystore (StrongBox where the phone has one) that sign only through a
 * BiometricPrompt with a strong biometric, and stop working when the phone's fingerprints or face
 * change. The private key never leaves the secure hardware.
 */
internal class AndroidDeviceSigner(private val activity: FragmentActivity) : DeviceSigner {
    override val platform: String = "ANDROID"

    override val deviceName: String = run {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    override fun availability(): BiometricAvailability = when (BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
        else -> BiometricAvailability.UNAVAILABLE
    }

    override fun publicKey(alias: String): String? = runCatching {
        keyStore().getCertificate(alias)?.publicKey?.encoded?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }.getOrNull()

    override suspend fun createKey(alias: String): KeyResult = withContext(Dispatchers.Default) {
        val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val created = runCatching { generate(alias, strongBox) }.recoverCatching { error ->
            val noStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && error is StrongBoxUnavailableException
            if (strongBox && noStrongBox) generate(alias, strongBox = false) else throw error
        }
        created.fold(
            onSuccess = { KeyResult.Created(Base64.encodeToString(it, Base64.NO_WRAP)) },
            onFailure = { KeyResult.Failed(SignerError.FAILED) },
        )
    }

    /** The new key's SubjectPublicKeyInfo. */
    private fun generate(alias: String, strongBox: Boolean): ByteArray {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    // -1: every use needs its own authentication, through a CryptoObject.
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
            }
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(spec)
        return generator.generateKeyPair().public.encoded
    }

    override suspend fun sign(alias: String, message: String, prompt: BiometricPromptText): SignResult = withContext(Dispatchers.Main) {
        val key = runCatching { keyStore().getKey(alias, null) as? PrivateKey }.getOrNull()
            ?: return@withContext SignResult.Failed(SignerError.NO_KEY)
        val signature = try {
            Signature.getInstance(SIGNATURE_ALGORITHM).apply { initSign(key) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            return@withContext SignResult.Failed(SignerError.KEY_INVALIDATED)
        } catch (_: Exception) {
            return@withContext SignResult.Failed(SignerError.FAILED)
        }
        authenticate(signature, message, prompt)
    }

    private suspend fun authenticate(signature: Signature, message: String, text: BiometricPromptText): SignResult =
        suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val signed = runCatching {
                        val unlocked = result.cryptoObject?.signature ?: error("no signature")
                        unlocked.update(message.encodeToByteArray())
                        Base64.encodeToString(unlocked.sign(), Base64.NO_WRAP)
                    }
                    if (continuation.isActive) {
                        continuation.resume(signed.fold({ SignResult.Signed(it) }, { SignResult.Failed(SignerError.FAILED) }))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(SignResult.Failed(errorCode.signerError()))
                }

                // One finger or face that didn't match: the prompt stays up for another try.
                override fun onAuthenticationFailed() = Unit
            }
            val biometricPrompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(text.title)
                .setSubtitle(text.subtitle)
                .setNegativeButtonText(text.cancel)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
            biometricPrompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
            continuation.invokeOnCancellation {
                ContextCompat.getMainExecutor(activity).execute { biometricPrompt.cancelAuthentication() }
            }
        }

    override fun deleteKey(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun Int.signerError(): SignerError = when (this) {
        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> SignerError.CANCELLED
        BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> SignerError.LOCKED_OUT
        BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_HW_UNAVAILABLE -> SignerError.UNAVAILABLE
        else -> SignerError.FAILED
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
