@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rfm.edubot.mobile

import com.rfm.edubot.mobile.core.common.LocationFailure
import com.rfm.edubot.mobile.core.common.LocationProvider
import com.rfm.edubot.mobile.core.common.LocationReading
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * One fix from Core Location, asking for "while using the app" the first time. A fix the system says
 * was simulated by software goes to the backend as mocked, which flags it.
 */
internal class IosLocationProvider : LocationProvider {
    override suspend fun current(): LocationReading = withContext(Dispatchers.Main) {
        val request = OneFix()
        try {
            request.read()
        } finally {
            request.stop()
        }
    }
}

/** A manager per request: its delegate callbacks then belong to that request alone. */
private class OneFix : NSObject(), CLLocationManagerDelegateProtocol {
    private val manager = CLLocationManager()
    private var authorization: CompletableDeferred<Unit>? = null
    private var fix: CompletableDeferred<LocationReading>? = null

    init {
        manager.delegate = this
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    suspend fun read(): LocationReading {
        if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) {
            val answered = CompletableDeferred<Unit>().also { authorization = it }
            manager.requestWhenInUseAuthorization()
            withTimeoutOrNull(PERMISSION_TIMEOUT_MILLIS) { answered.await() }
        }
        when (manager.authorizationStatus) {
            kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> Unit
            // Denied also covers location switched off for the whole phone.
            kCLAuthorizationStatusDenied -> return LocationReading.Failed(
                if (servicesEnabled()) LocationFailure.PERMISSION_DENIED else LocationFailure.DISABLED,
            )
            else -> return LocationReading.Failed(LocationFailure.PERMISSION_DENIED)
        }
        val pending = CompletableDeferred<LocationReading>().also { fix = it }
        manager.requestLocation()
        return withTimeoutOrNull(FIX_TIMEOUT_MILLIS) { pending.await() } ?: LocationReading.Failed(LocationFailure.TIMEOUT)
    }

    fun stop() {
        manager.stopUpdatingLocation()
        manager.delegate = null
    }

    /** Off the main thread, where Core Location warns it can stall the UI. */
    private suspend fun servicesEnabled(): Boolean = withContext(Dispatchers.Default) { CLLocationManager.locationServicesEnabled() }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        if (manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) authorization?.complete(Unit)
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        fix?.complete(location.reading())
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        val reason = if (didFailWithError.code == kCLErrorDenied) LocationFailure.PERMISSION_DENIED else LocationFailure.UNAVAILABLE
        fix?.complete(LocationReading.Failed(reason))
    }

    private fun CLLocation.reading(): LocationReading.Fix {
        val (latitude, longitude) = coordinate.useContents { latitude to longitude }
        return LocationReading.Fix(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = horizontalAccuracy.takeIf { it >= 0 },
            mocked = sourceInformation?.isSimulatedBySoftware ?: false,
        )
    }
}

private const val FIX_TIMEOUT_MILLIS = 20_000L
private const val PERMISSION_TIMEOUT_MILLIS = 120_000L
