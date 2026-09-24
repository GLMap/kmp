@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package globus.glroute
import globus.glmap.core.*

import globus.native.core.*
import globus.native.route.*
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.ECANCELED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class IosManeuver(val native: GLRouteManeuver) : RouteManeuver() {
    override fun lineData()=LineData(native.line(),native.lineStartIndex().toInt())
    override val type get() = native.type().toInt()
    override val shortInstruction get() = native.shortInstruction() ?: ""
    override val start get() = native.startPoint().geoPoint()
}
internal class IosRoute(private var native: GLRoute?) : Route() {
    private val trackers = mutableSetOf<IosTracker>()
    fun open() = native ?: throw SdkException(SdkError.Closed, "Route has been released")
    override fun trackData(color:Long)=TrackData(open().trackDataWithColor(color.mapColor()) ?: throw SdkException(SdkError.Native,"No track geometry"))
    override val length get() = open().length
    override val duration get() = open().duration
    override val bounds get() = open().bbox.geoBounds()
    override val maneuvers get() = open().allManeuvers.map { IosManeuver(it as GLRouteManeuver) }
    override val lonLat: DoubleArray get() {
        val values = ArrayList<Double>()
        open().enumPointsFrom(0u) { point, _ -> point.geoPoint().let { values.add(it.longitude); values.add(it.latitude) } }
        return values.toDoubleArray()
    }
    override fun tracker(): RouteTracker {
        val tracker = GLRouteTracker(data = open()); tracker.currentTargetPointIndex = 1u
        return IosTracker(this, tracker).also(trackers::add)
    }
    fun forget(tracker: IosTracker) { trackers.remove(tracker) }
    override fun close() { trackers.toList().forEach { it.close() }; native = null }
}
internal class IosTracker(private val route: IosRoute, private var native: GLRouteTracker?) : RouteTracker() {
    override fun update(fix: LocationFix): Navigation {
        val tracker = native ?: throw SdkException(SdkError.Closed, "Tracker has been released")
        val maneuver = tracker.updateLocation(GLMapGeoPointMake(fix.point.latitude, fix.point.longitude), fix.bearing?.toFloat() ?: Float.NaN)
        return Navigation(maneuver?.let(::IosManeuver), tracker.distanceToNextManeuver, tracker.remainingDistance, tracker.remainingDuration,
            tracker.progressIndex, tracker.onRoute, if (tracker.onRoute) tracker.locationOnRoute.geoPoint() else fix.point)
    }
    override fun close() { native = null; route.forget(this) }
}

    actual suspend fun GLMapSdk.route(query: RouteQuery): Route {
        val config = if (query.offline) readAsset("valhalla.json") else null
        return suspendCancellableCoroutine { reply ->
            val request = GLRouteRequest()
            when (query.mode) {
                RouteMode.Auto -> request.setAutoWithOptions(CostingOptionsAutoDefault.readValue())
                RouteMode.Bicycle -> request.setBicycleWithOptions(CostingOptionsBicycleDefault.readValue())
                RouteMode.Pedestrian -> request.setPedestrianWithOptions(CostingOptionsPedestrianDefault.readValue())
            }
            request.locale = query.locale; request.unitSystem = GLUnitSystem.GLUnitSystem_International
            request.addPoint(GLRoutePointMake(GLMapGeoPointMake(query.start.latitude, query.start.longitude), Double.NaN, GLRoutePointType_Break))
            request.addPoint(GLRoutePointMake(GLMapGeoPointMake(query.end.latitude, query.end.longitude), Double.NaN, GLRoutePointType_Break))
            val completion = { route: GLRoute?, error: NSError? -> onMain {
                if (reply.isActive) {
                    if (route != null) reply.resume(IosRoute(route))
                    else reply.resumeWithException(error?.exception() ?: SdkException(SdkError.Native, "No route returned"))
                }
            } }
            val id = if (config != null) request.startOfflineWithConfig(config, completion) else request.startOnlineWithCompletion(completion)
            reply.invokeOnCancellation { if (id != 0L) GLRouteRequest.cancel(id) }
        }
    }
    actual fun GLMapSdk.buildRoute(steps: List<RouteStep>): Route {
        if (steps.isEmpty() || steps.any { it.lonLat.size < 4 || it.lonLat.size % 2 != 0 || !it.lonLat.all(Double::isFinite) || !(it.duration >= 0) })
            throw SdkException(SdkError.InvalidArgument, "route steps")
        val builder = GLRouteBuilder(); builder.setLanguage("en")
        val first = steps.first().lonLat; val last = steps.last().lonLat
        val finish = GeoPoint(last[last.size - 1], last[last.size - 2])
        builder.addTargetPoint(GLRoutePointMake(GLMapGeoPointMake(first[1], first[0]), Double.NaN, GLRoutePointType_Break))
        builder.addTargetPoint(GLRoutePointMake(GLMapGeoPointMake(finish.latitude, finish.longitude), Double.NaN, GLRoutePointType_Break))
        fun add(type: UByte, points: List<GeoPoint>) = memScoped {
            val array = allocArray<GLMapPoint>(points.size)
            points.forEachIndexed { index, point -> point.mapPoint().useContents { array[index].x = x; array[index].y = y } }
            builder.addManeuver(type, array, null, points.size.toUInt())
        }
        steps.forEach { step ->
            add(step.turn.toUByte(), List(step.lonLat.size / 2) { GeoPoint(step.lonLat[2 * it + 1], step.lonLat[2 * it]) })
            builder.setManeuverShortInstruction(step.instruction); builder.setManeuverTime(step.duration)
        }
        add(GLManeuverType_Destination, listOf(finish)); builder.setManeuverShortInstruction("Arrive at destination")
        return IosRoute(builder.build() ?: throw SdkException(SdkError.Native, "Cannot build route"))
    }
