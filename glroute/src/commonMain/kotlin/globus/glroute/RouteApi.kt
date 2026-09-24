package globus.glroute
import globus.glmap.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
enum class RouteMode { Auto, Bicycle, Pedestrian }
data class RouteQuery(val start: GeoPoint, val end: GeoPoint, val mode: RouteMode = RouteMode.Auto,
    val offline: Boolean = false, val locale: String = "en-US")
/** One leg of a locally built route; turn is a GLRouteManeuver type value. */
class RouteStep(val lonLat: DoubleArray, val turn: Int, val instruction: String, val duration: Double)
data class Navigation(val maneuver: RouteManeuver?, val distanceToManeuver: Double, val remainingDistance: Double,
    val remainingDuration: Double, val progress: Double, val onRoute: Boolean, val position: GeoPoint)

abstract class RouteManeuver:LineSource {
    abstract val type: Int
    abstract val shortInstruction: String
    abstract val start: GeoPoint
}
/** Owns the native route; trackers and drawables created from it must not outlive it. */
abstract class Route:TrackSource {
    abstract val length: Double
    abstract val duration: Double
    abstract val bounds: GeoBounds
    abstract val maneuvers: List<RouteManeuver>
    abstract val lonLat: DoubleArray
    abstract fun tracker(): RouteTracker
    abstract fun close()
}
abstract class RouteTracker {
    abstract fun update(fix: LocationFix): Navigation
    abstract fun close()
}


expect suspend fun GLMapSdk.route(query:RouteQuery):Route
expect fun GLMapSdk.buildRoute(steps:List<RouteStep>):Route
fun replayFixes(lonLat: DoubleArray, intervalMs: Long = 1000): Flow<LocationFix> = flow {
    for (i in lonLat.indices step 2) {
        emit(LocationFix(GeoPoint(lonLat[i + 1], lonLat[i])))
        delay(intervalMs)
    }
}
