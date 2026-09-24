@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package globus.glmap
import globus.glmap.core.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreLocation.*
import platform.Foundation.NSError
import platform.darwin.NSObject

@Composable actual fun SystemBack(enabled: Boolean, onBack: () -> Unit) {}

@Composable actual fun rememberLocationSource(): LocationSource = remember { IosLocationSource() }

private class LocationDelegate(val onFix: (LocationFix) -> Unit, val onDenied: () -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        didUpdateLocations.forEach { value ->
            val location = value as CLLocation
            onFix(LocationFix(location.coordinate.useContents { GeoPoint(latitude, longitude) },
                location.horizontalAccuracy.takeIf { it >= 0 } ?: 10.0, location.course.takeIf { it >= 0 }))
        }
    }
    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        val status = manager.authorizationStatus
        if (status == kCLAuthorizationStatusDenied || status == kCLAuthorizationStatusRestricted) onDenied()
    }
    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        if (didFailWithError.code == kCLErrorDenied) onDenied()
    }
}

private class IosLocationSource : LocationSource() {
    // CLLocationManager is created and used on the main thread, where Compose collects.
    override fun fixes(): Flow<LocationFix> = callbackFlow {
        val manager = CLLocationManager()
        val delegate = LocationDelegate({ trySend(it) }, { close(SdkException(SdkError.LocationDenied, "Location permission is required")) })
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()
        awaitClose { manager.stopUpdatingLocation(); manager.delegate = null }
    }
}
