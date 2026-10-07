@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.rfm.edubot.mobile

import com.rfm.edubot.mobile.core.common.BiometricAvailability
import com.rfm.edubot.mobile.core.common.BiometricPromptText
import com.rfm.edubot.mobile.core.common.DeviceSigner
import com.rfm.edubot.mobile.core.common.KeyResult
import com.rfm.edubot.mobile.core.common.SignResult
import com.rfm.edubot.mobile.core.common.SignerError
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.isEqualToData
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRef
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAccessControlPrivateKeyUsage
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef
import platform.Security.kSecUseAuthenticationContext
import platform.UIKit.UIDevice
import kotlin.coroutines.resume

/**
 * EC P-256 keys in the Secure Enclave that sign only after Face ID or Touch ID with the biometrics
 * enrolled when the key was made. The private key never leaves the enclave.
 *
 * A change to the enrolled fingerprints or face is caught by comparing LocalAuthentication's domain
 * state with the one seen at the last signature, which says [SignerError.KEY_INVALIDATED] plainly
 * where the keychain would only fail.
 */
internal class IosDeviceSigner : DeviceSigner {
    override val platform: String = "IOS"

    override val deviceName: String = UIDevice.currentDevice.model

    private val defaults = NSUserDefaults.standardUserDefaults

    override fun availability(): BiometricAvailability = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error.ptr)) {
            return BiometricAvailability.AVAILABLE
        }
        when (error.value?.code) {
            LAErrorBiometryNotEnrolled, LAErrorPasscodeNotSet -> BiometricAvailability.NOT_ENROLLED
            // Locked out still has biometrics: signing says so, with the way out.
            LAErrorBiometryLockout -> BiometricAvailability.AVAILABLE
            else -> BiometricAvailability.UNAVAILABLE
        }
    }

    override fun publicKey(alias: String): String? {
        val key = findKey(alias) ?: return null
        return exportPublicKey(key).also { CFRelease(key) }
    }

    override suspend fun createKey(alias: String): KeyResult = withContext(Dispatchers.Default) {
        deleteKey(alias)
        memScoped {
            val access = SecAccessControlCreateWithFlags(
                kCFAllocatorDefault,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                kSecAccessControlPrivateKeyUsage or kSecAccessControlBiometryCurrentSet,
                null,
            ) ?: return@withContext KeyResult.Failed(SignerError.FAILED)
            val privateAttributes = CfDictionary(
                kSecAttrIsPermanent to kCFBooleanTrue,
                kSecAttrApplicationTag to tag(alias),
                kSecAttrAccessControl to access,
            )
            val attributes = CfDictionary(
                kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeySizeInBits to NSNumber(int = 256),
                kSecAttrTokenID to kSecAttrTokenIDSecureEnclave,
                kSecPrivateKeyAttrs to privateAttributes.ref,
            )
            val error = alloc<CFErrorRefVar>()
            val privateKey = SecKeyCreateRandomKey(attributes.ref, error.ptr)
            attributes.release()
            privateAttributes.release()
            CFRelease(access)
            error.value?.let { CFRelease(it) }
            val publicKey = privateKey?.let { key -> exportPublicKey(key).also { CFRelease(key) } }
            if (publicKey == null) KeyResult.Failed(SignerError.FAILED) else KeyResult.Created(publicKey)
        }
    }

    override suspend fun sign(alias: String, message: String, prompt: BiometricPromptText): SignResult {
        if (findKey(alias)?.also { CFRelease(it) } == null) return SignResult.Failed(SignerError.NO_KEY)
        val context = LAContext().apply {
            localizedCancelTitle = prompt.cancel
            // Biometrics only: no passcode fallback, which would let anyone who knows it clock in.
            localizedFallbackTitle = ""
        }
        val reason = listOf(prompt.title, prompt.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
        evaluate(context, reason)?.let { return SignResult.Failed(it) }

        val state = context.evaluatedPolicyDomainState
        val seen = defaults.dataForKey(domainKey(alias))
        if (seen != null && state != null && !seen.isEqualToData(state)) return SignResult.Failed(SignerError.KEY_INVALIDATED)

        return withContext(Dispatchers.Default) {
            val key = findKey(alias, context) ?: return@withContext SignResult.Failed(SignerError.NO_KEY)
            val signature = memScoped {
                @Suppress("UNCHECKED_CAST")
                val data = CFBridgingRetain(message.encodeToByteArray().toNSData()) as CFDataRef?
                val error = alloc<CFErrorRefVar>()
                val signed = SecKeyCreateSignature(key, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, data, error.ptr)
                CFRelease(data)
                error.value?.let { CFRelease(it) }
                CFBridgingRelease(signed) as? NSData
            }
            CFRelease(key)
            if (signature == null) return@withContext SignResult.Failed(SignerError.FAILED)
            if (seen == null && state != null) defaults.setObject(state, forKey = domainKey(alias))
            SignResult.Signed(signature.base64EncodedStringWithOptions(0u))
        }
    }

    override fun deleteKey(alias: String) {
        val query = CfDictionary(kSecClass to kSecClassKey, kSecAttrApplicationTag to tag(alias))
        SecItemDelete(query.ref)
        query.release()
        defaults.removeObjectForKey(domainKey(alias))
    }

    /** Null once Face ID or Touch ID passed; otherwise why not. */
    private suspend fun evaluate(context: LAContext, reason: String): SignerError? = suspendCancellableCoroutine { continuation ->
        context.evaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, localizedReason = reason) { success, error ->
            if (continuation.isActive) continuation.resume(if (success) null else error?.code.signerError())
        }
        continuation.invokeOnCancellation { context.invalidate() }
    }

    /** The key behind [alias]; with [context], one that already passed, so using it asks for nothing more. */
    private fun findKey(alias: String, context: LAContext? = null): SecKeyRef? = memScoped {
        val query = CfDictionary(
            kSecClass to kSecClassKey,
            kSecAttrApplicationTag to tag(alias),
            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnRef to kCFBooleanTrue,
            kSecUseAuthenticationContext to context,
        )
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query.ref, result.ptr)
        query.release()
        @Suppress("UNCHECKED_CAST")
        if (status == errSecSuccess) result.value as SecKeyRef? else null
    }

    /** The 65-byte X9.63 point, base64; the backend completes it into a SubjectPublicKeyInfo. */
    private fun exportPublicKey(privateKey: SecKeyRef): String? {
        val publicKey = SecKeyCopyPublicKey(privateKey) ?: return null
        val data = SecKeyCopyExternalRepresentation(publicKey, null)
        CFRelease(publicKey)
        return (CFBridgingRelease(data) as? NSData)?.base64EncodedStringWithOptions(0u)
    }

    private fun tag(alias: String): NSData = "$TAG_PREFIX$alias".encodeToByteArray().toNSData()

    private fun domainKey(alias: String) = "$TAG_PREFIX$alias.domainState"

    private fun Long?.signerError(): SignerError = when (this) {
        LAErrorUserCancel, LAErrorSystemCancel, LAErrorAppCancel, LAErrorUserFallback -> SignerError.CANCELLED
        LAErrorBiometryLockout -> SignerError.LOCKED_OUT
        LAErrorBiometryNotEnrolled, LAErrorBiometryNotAvailable, LAErrorPasscodeNotSet -> SignerError.UNAVAILABLE
        else -> SignerError.FAILED
    }

    private companion object {
        const val TAG_PREFIX = "pt.thebotslab.edubot."
    }
}

/**
 * A CFDictionary for the Security calls. Core Foundation values go in as they are; Kotlin and
 * Objective-C objects are bridged, and the dictionary retains what it holds.
 */
private class CfDictionary(vararg entries: Pair<CFStringRef?, Any?>) {
    val ref: CFMutableDictionaryRef? = CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        entries.size.convert(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )

    init {
        entries.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is CPointer<*> -> CFDictionaryAddValue(ref, key, value)
                else -> {
                    val bridged = CFBridgingRetain(value)
                    CFDictionaryAddValue(ref, key, bridged)
                    CFRelease(bridged)
                }
            }
        }
    }

    fun release() = CFRelease(ref)
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
