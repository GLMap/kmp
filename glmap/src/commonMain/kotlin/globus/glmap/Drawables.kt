package globus.glmap
import globus.glmap.core.*
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
/** Bundled SVG rendered by the SDK; tint is ARGB. */
data class SvgImage(val asset: String, val scale: Double = 1.0, val tint: Long? = null)
data class Pin(val point: GeoPoint, val variant: Int)

/** Foreground fixes; collection requests permission and starts updates, cancellation stops them. */
abstract class LocationSource { abstract fun fixes(): Flow<LocationFix> }
@Composable expect fun rememberLocationSource(): LocationSource
@Composable expect fun SystemBack(enabled: Boolean, onBack: () -> Unit)

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
    abstract fun setRoute(route: TrackSource, color: Long)
    abstract var progressColor: Long
    abstract var progress: Double
}
abstract class MapLineArrow : MapDrawable() { abstract fun setManeuver(maneuver: LineSource) }
abstract class UserLocationMarker : MapDrawable() { abstract fun update(fix: LocationFix) }
abstract class MapAnimation { abstract fun cancel() }
/** unionStyle may run off the UI thread and must not touch UI state. */
class MarkerStyle(val images: List<SvgImage>, val textStyle: String, val nameKey: String? = null,
    val unionStyle: ((count: Int) -> Int)? = null)
