package globus.glroute
import globus.glmap.core.*

import android.content.Context
import android.os.Handler
import android.os.Looper
import globus.glmap.*
import globus.glroute.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val main=Handler(Looper.getMainLooper())
internal class AndroidManeuver(val native: GLRouteManeuver) : RouteManeuver() {
    override fun lineData()=LineData(native.line,native.lineStartIndex)
    override val type get() = native.type
    override val shortInstruction get() = native.shortInstruction ?: ""
    override val start get() = native.startPoint.geoPoint()
}
internal class AndroidRoute(private var native: GLRoute?) : Route() {
    private val trackers = mutableSetOf<AndroidTracker>()
    fun open() = native ?: throw SdkException(SdkError.Closed, "Route has been released")
    override fun trackData(color:Long)=TrackData(open().getTrackData(color.toInt()))
    override val length get() = open().length
    override val duration get() = open().duration
    override val bounds get() = open().getTrackData(0).use { it.bBox.geoBounds() }
    override val maneuvers get() = open().maneuvers.map(::AndroidManeuver)
    override val lonLat: DoubleArray get() {
        val raw = open().trackCoordinates ?: return DoubleArray(0)
        return DoubleArray(raw.size).also { out ->
            for (i in raw.indices step 2) MapGeoPoint(MapPoint(raw[i].toDouble(), raw[i + 1].toDouble())).let { out[i] = it.lon; out[i + 1] = it.lat }
        }
    }
    override fun tracker(): RouteTracker = AndroidTracker(this, GLRouteTracker(open()).apply { currentTargetPointIndex = 1 }).also(trackers::add)
    fun forget(tracker: AndroidTracker) { trackers.remove(tracker) }
    override fun close() {
        trackers.toList().forEach { it.close() }
        native?.close(); native = null
    }
}
internal class AndroidTracker(private val route: AndroidRoute, private var native: GLRouteTracker?) : RouteTracker() {
    override fun update(fix: LocationFix): Navigation {
        val tracker = native ?: throw SdkException(SdkError.Closed, "Tracker has been released")
        val maneuver = tracker.updateLocation(fix.point.latitude, fix.point.longitude, fix.bearing?.toFloat() ?: Float.NaN)
        return Navigation(maneuver?.let(::AndroidManeuver), tracker.distanceToNextManeuver, tracker.remainingDistance,
            tracker.remainingDuration, tracker.progressIndex, tracker.isOnRoute,
            if (tracker.isOnRoute) tracker.locationOnRoute.geoPoint() else fix.point)
    }
    override fun close() { native?.close(); native = null; route.forget(this) }
}

    actual suspend fun GLMapSdk.route(query: RouteQuery): Route {
        val config = if (query.offline) readAsset("valhalla.json") else null
        return suspendCancellableCoroutine { reply ->
            val request = GLRouteRequest().apply {
                when (query.mode) {
                    RouteMode.Auto -> setAutoWithOptions(CostingOptions.Auto())
                    RouteMode.Bicycle -> setBicycleWithOptions(CostingOptions.Bicycle())
                    RouteMode.Pedestrian -> setPedestrianWithOptions(CostingOptions.Pedestrian())
                }
                locale = query.locale
                unitSystem = GLMapLocaleSettings.UnitSystem.International
                addPoint(GLRoutePoint(MapGeoPoint(query.start.latitude, query.start.longitude), Double.NaN, GLRoutePoint.Type.BREAK))
                addPoint(GLRoutePoint(MapGeoPoint(query.end.latitude, query.end.longitude), Double.NaN, GLRoutePoint.Type.BREAK))
            }
            val callback = object : GLRouteRequest.ResultsCallback {
                override fun onResult(route: GLRoute) { main.post {
                    request.close()
                    val value = AndroidRoute(route)
                    if (reply.isActive) reply.resume(value) { _, late, _ -> late.close() } else value.close()
                } }
                override fun onError(error: GLMapError) { main.post {
                    request.close()
                    if (reply.isActive) reply.resumeWithException(error.exception())
                } }
            }
            val id = if (config != null) request.startOffline(config, callback) else request.startOnline(callback)
            reply.invokeOnCancellation { GLRouteRequest.cancel(id) }
        }
    }
    actual fun GLMapSdk.buildRoute(steps: List<RouteStep>): Route {
        if (steps.isEmpty() || steps.any { it.lonLat.size < 4 || it.lonLat.size % 2 != 0 || !it.lonLat.all(Double::isFinite) || !(it.duration >= 0) })
            throw SdkException(SdkError.InvalidArgument, "route steps")
        return GLRouteBuilder().use { builder ->
            builder.setLanguage("en")
            val first = steps.first().lonLat; val last = steps.last().lonLat
            val finish = MapGeoPoint(last[last.size - 1], last[last.size - 2])
            builder.addTargetPoint(GLRoutePoint(MapGeoPoint(first[1], first[0]), Double.NaN, GLRoutePoint.Type.BREAK))
            builder.addTargetPoint(GLRoutePoint(finish, Double.NaN, GLRoutePoint.Type.BREAK))
            steps.forEach { step ->
                builder.addManeuver(step.turn, Array(step.lonLat.size / 2) { MapPoint.CreateFromGeoCoordinates(step.lonLat[2 * it + 1], step.lonLat[2 * it]) }, null)
                builder.setManeuverShortInstruction(step.instruction); builder.setManeuverTime(step.duration)
            }
            builder.addManeuver(GLRouteManeuver.Type.Destination, arrayOf(MapPoint(finish)), null)
            builder.setManeuverShortInstruction("Arrive at destination")
            AndroidRoute(builder.build() ?: throw SdkException(SdkError.Native, "Cannot build route"))
        }
    }
