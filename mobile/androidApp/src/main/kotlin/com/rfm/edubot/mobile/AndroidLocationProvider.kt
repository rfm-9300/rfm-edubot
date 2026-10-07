package com.rfm.edubot.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import com.rfm.edubot.mobile.core.common.LocationFailure
import com.rfm.edubot.mobile.core.common.LocationProvider
import com.rfm.edubot.mobile.core.common.LocationReading
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One fix from the platform's own providers, so the app needs no Play services. Asks for location
 * "while using the app" the first time; the user may grant only approximate location, which the
 * backend then flags as low accuracy.
 */
internal class AndroidLocationProvider(private val activity: ComponentActivity) : LocationProvider {
    private val manager: LocationManager? = ContextCompat.getSystemService(activity, LocationManager::class.java)
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    // Registered while the activity is being created, as the result API requires.
    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        pendingPermission?.complete(grants.values.any { it })
        pendingPermission = null
    }

    override suspend fun current(): LocationReading = withContext(Dispatchers.Main) {
        if (!granted() && !requestPermission()) return@withContext LocationReading.Failed(LocationFailure.PERMISSION_DENIED)
        val manager = manager ?: return@withContext LocationReading.Failed(LocationFailure.UNAVAILABLE)
        if (!LocationManagerCompat.isLocationEnabled(manager)) return@withContext LocationReading.Failed(LocationFailure.DISABLED)
        val providers = providers(manager)
        if (providers.isEmpty()) return@withContext LocationReading.Failed(LocationFailure.DISABLED)

        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MILLIS) { firstFix(manager, providers) }
        val fix = fresh?.location ?: recentLastKnown(manager, providers)
        when {
            fix != null -> fix.reading()
            fresh == null -> LocationReading.Failed(LocationFailure.TIMEOUT)
            else -> LocationReading.Failed(LocationFailure.UNAVAILABLE)
        }
    }

    private fun granted(): Boolean = LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun requestPermission(): Boolean {
        val pending = CompletableDeferred<Boolean>().also { pendingPermission = it }
        permissionLauncher.launch(LOCATION_PERMISSIONS)
        return pending.await()
    }

    /** Fused where the platform has it, GPS only with precise location, then the network's estimate. */
    private fun providers(manager: LocationManager): List<String> {
        val precise = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            if (precise) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /** Null [location] when every provider answered without a fix. */
    private class Answer(val location: Location?)

    /** Asks every provider at once and takes the first fix, since GPS alone can take minutes indoors. */
    @SuppressLint("MissingPermission")
    private suspend fun firstFix(manager: LocationManager, providers: List<String>): Answer = suspendCancellableCoroutine { continuation ->
        val signals = providers.map { CancellationSignal() }
        var answered = 0
        providers.forEachIndexed { index, provider ->
            LocationManagerCompat.getCurrentLocation(manager, provider, signals[index], ContextCompat.getMainExecutor(activity)) { location ->
                answered += 1
                if (!continuation.isActive) return@getCurrentLocation
                if (location != null) {
                    signals.forEach { it.cancel() }
                    continuation.resume(Answer(location))
                } else if (answered == providers.size) {
                    continuation.resume(Answer(null))
                }
            }
        }
        continuation.invokeOnCancellation { signals.forEach { it.cancel() } }
    }

    @SuppressLint("MissingPermission")
    private fun recentLastKnown(manager: LocationManager, providers: List<String>): Location? = providers
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= LAST_KNOWN_MAX_AGE_NANOS }
        .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }

    private fun Location.reading() = LocationReading.Fix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        mocked = LocationCompat.isMock(this),
    )

    private companion object {
        val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        const val FIX_TIMEOUT_MILLIS = 20_000L

        /** A fix this recent still says where the phone is now. */
        const val LAST_KNOWN_MAX_AGE_NANOS = 2L * 60 * 1_000_000_000
    }
}
