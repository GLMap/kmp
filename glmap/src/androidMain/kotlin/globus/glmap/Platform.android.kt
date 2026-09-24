package globus.glmap
import globus.glmap.core.*

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@Composable actual fun SystemBack(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable actual fun rememberLocationSource(): LocationSource {
    val context = LocalContext.current.applicationContext
    val source = remember { AndroidLocationSource(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { source.permission?.complete(Unit) }
    source.request = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    return source
}

private class AndroidLocationSource(private val context: Context) : LocationSource() {
    var permission: CompletableDeferred<Unit>? = null
    var request: () -> Unit = {}
    private fun granted() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<LocationFix> = callbackFlow {
        if (!granted()) {
            val answer = CompletableDeferred<Unit>(); permission = answer
            request(); answer.await()
            if (!granted()) throw SdkException(SdkError.LocationDenied, "Location permission is required")
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull(manager::isProviderEnabled)
            ?: throw SdkException(SdkError.LocationUnavailable, "Enable location services")
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(LocationFix(GeoPoint(location.latitude, location.longitude),
                    if (location.hasAccuracy()) location.accuracy.toDouble() else 10.0,
                    if (location.hasBearing()) location.bearing.toDouble() else null))
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Required below API 29") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        manager.requestLocationUpdates(provider, 1000, 0f, listener, Looper.getMainLooper())
        awaitClose { manager.removeUpdates(listener) }
    }
}
