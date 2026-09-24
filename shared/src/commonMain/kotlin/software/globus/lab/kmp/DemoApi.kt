package software.globus.lab.kmp

import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class GeoPoint(val latitude: Double, val longitude: Double)
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    companion object {
        fun of(points: List<GeoPoint>) = GeoBounds(points.minOf { it.latitude }, points.minOf { it.longitude },
            points.maxOf { it.latitude }, points.maxOf { it.longitude })
    }
}
/** View coordinates are dp on Android and points on iOS. */
data class MapTap(val point: GeoPoint, val x: Double, val y: Double)
data class ScreenPoint(val x: Double, val y: Double)
/** Bundled SVG rendered by the SDK; tint is ARGB. */
data class SvgImage(val asset: String, val scale: Double = 1.0, val tint: Long? = null)
data class Pin(val point: GeoPoint, val variant: Int)

object SdkError {
    const val Cancelled = "cancelled"
    const val Closed = "closed"
    const val InvalidArgument = "invalid_argument"
    const val AssetUnavailable = "asset_unavailable"
    const val LocationDenied = "location_denied"
    const val LocationUnavailable = "location_unavailable"
    const val Native = "sdk_error"
}
class SdkException(val code: String, message: String? = null) : Exception(message ?: code)

enum class DataSet { Map, Navigation, Elevation }
enum class RouteMode { Auto, Bicycle, Pedestrian }
data class SearchQuery(val text: String, val center: GeoPoint, val offline: Boolean, val autocomplete: Boolean,
    val categories: List<String> = emptyList(), val limit: Int = 50)
data class Place(val name: String, val detail: String, val point: GeoPoint)
data class RouteQuery(val start: GeoPoint, val end: GeoPoint, val mode: RouteMode = RouteMode.Auto,
    val offline: Boolean = false, val locale: String = "en-US")
/** One leg of a locally built route; turn is a GLRouteManeuver type value. */
class RouteStep(val lonLat: DoubleArray, val turn: Int, val instruction: String, val duration: Double)
data class AreaFile(val dataSet: DataSet, val fileName: String)
data class Region(val id: Long, val name: String, val isoCode: String?, val isCollection: Boolean, val onDevice: Boolean,
    val downloaded: Boolean, val downloading: Boolean, val progress: Double?, val sizeOnServer: Long, val sizeOnDisk: Long)
data class LocationFix(val point: GeoPoint, val accuracy: Double = 10.0, val bearing: Double? = null)
data class Navigation(val maneuver: RouteManeuver?, val distanceToManeuver: Double, val remainingDistance: Double,
    val remainingDuration: Double, val progress: Double, val onRoute: Boolean, val position: GeoPoint)

/** Owns native search objects until closed. */
abstract class SearchResults {
    abstract val places: List<Place>
    abstract fun close()
}
abstract class RouteManeuver {
    abstract val type: Int
    abstract val shortInstruction: String
    abstract val start: GeoPoint
}
/** Owns the native route; trackers and drawables created from it must not outlive it. */
abstract class Route {
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

/** Map-independent services. Suspend calls cancel their native request with the coroutine. */
abstract class GLMapSdk {
    abstract fun initialize(apiKey: String)
    abstract var tileDownloading: Boolean
    abstract fun addBundledMap(asset: String)
    abstract suspend fun readAsset(name: String): String
    abstract suspend fun search(query: SearchQuery): SearchResults
    abstract suspend fun route(query: RouteQuery): Route
    abstract fun buildRoute(steps: List<RouteStep>): Route
    abstract suspend fun downloadArea(bounds: GeoBounds, files: List<AreaFile>,
        onProgress: (DataSet, downloaded: Long, total: Long) -> Unit = { _, _, _ -> })
    abstract fun regions(parent: Long?): List<Region>
    abstract suspend fun refreshRegions()
    /** Emits on region state or download progress changes. */
    abstract val regionChanges: Flow<Unit>
    abstract fun downloadRegion(id: Long)
    abstract fun cancelRegionDownload(id: Long)
    abstract fun deleteRegion(id: Long)
}
@Composable expect fun rememberSdk(): GLMapSdk

/** Foreground fixes; collection requests permission and starts updates, cancellation stops them. */
abstract class LocationSource { abstract fun fixes(): Flow<LocationFix> }
@Composable expect fun rememberLocationSource(): LocationSource
@Composable expect fun SystemBack(enabled: Boolean, onBack: () -> Unit)

fun replayFixes(lonLat: DoubleArray, intervalMs: Long = 1000): Flow<LocationFix> = flow {
    for (i in lonLat.indices step 2) {
        emit(LocationFix(GeoPoint(lonLat[i + 1], lonLat[i])))
        delay(intervalMs)
    }
}

abstract class MapDrawable {
    abstract var hidden: Boolean
    abstract fun remove()
}
/** Position and scale changes inside MapController.animate are animated. */
abstract class MapImage : MapDrawable() {
    abstract var position: GeoPoint
    abstract var scale: Double
}
abstract class MapImageGroup : MapDrawable() { abstract fun setPins(pins: List<Pin>) }
abstract class MapBalloon : MapDrawable() { abstract fun show(point: GeoPoint, text: String) }
abstract class MapMarkers : MapDrawable() {
    abstract val bounds: GeoBounds?
    abstract fun pick(tap: MapTap, distance: Double = 24.0): Int?
    /** Hides the selected marker from the layer so a separate image can represent it. */
    abstract fun select(index: Int?)
}
abstract class MapTrack : MapDrawable() {
    abstract fun append(point: GeoPoint, color: Long)
    abstract fun setRoute(route: Route, color: Long)
    abstract var progressColor: Long
    abstract var progress: Double
}
abstract class MapLineArrow : MapDrawable() { abstract fun setManeuver(maneuver: RouteManeuver) }
abstract class UserLocationMarker : MapDrawable() { abstract fun update(fix: LocationFix) }
abstract class MapAnimation { abstract fun cancel() }
/** unionStyle may run off the UI thread and must not touch UI state. */
class MarkerStyle(val images: List<SvgImage>, val textStyle: String, val nameKey: String? = null,
    val unionStyle: ((count: Int) -> Int)? = null)
