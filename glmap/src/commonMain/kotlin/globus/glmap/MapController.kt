package globus.glmap
import globus.glmap.core.*

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class Camera(val latitude: Double, val longitude: Double, val zoom: Double,
                  val angle: Double = 0.0, val pitch: Double = 0.0)
@Serializable
data class MapState(val latitude: Double, val longitude: Double, val zoom: Double,
    val scale: Double, val angle: Double, val pitch: Double, val originX: Double, val originY: Double) {
    fun camera() = Camera(latitude, longitude, zoom, angle, pitch)
}
/** Terminal native preparation status, not frame presentation or download progress. */
@Serializable enum class UpdateResult {
    /** Prepared batches were installed. */ Ready,
    /** A newer request replaced this request before preparation began. */ Superseded,
    /** Removal, release or loss of the rendering surface cancelled preparation. */ Cancelled,
    /** Preparation failed; previously installed batches are preserved. */ Failed
}
class VectorLayer internal constructor(internal val owner: MapController, internal val id: Int)

/** Map API, called on the UI thread. Native preparation owns update ordering.
 * Cancelling await does not cancel native work; removal/disposal does.
 */
abstract class MapController:MapQueryTarget {
    abstract override fun queryState():MapQueryState?
    var onTap: (() -> Unit)? = null
    var onMapTap: ((MapTap) -> Unit)? = null
    var onMapLongPress: ((MapTap) -> Unit)? = null
    var disposed = false
        private set
    private val pending = mutableSetOf<CompletableDeferred<*>>()
    protected fun checkOpen() = check(!disposed) { "map_disposed" }
    protected fun <T> request(register: ((T) -> Unit) -> Unit): Deferred<T> {
        checkOpen()
        val reply = CompletableDeferred<T>()
        pending.add(reply)
        try { register { value -> pending.remove(reply); reply.complete(value) } }
        catch (error: Exception) { pending.remove(reply); reply.completeExceptionally(error) }
        return reply
    }
    fun setCamera(camera: Camera) {
        checkOpen()
        require(listOf(camera.latitude,camera.longitude,camera.zoom,camera.angle,camera.pitch).all { it.isFinite() }
            && camera.latitude in -90.0..90.0 && camera.longitude in -180.0..180.0
            && camera.pitch in 0.0..45.0 && camera.angle.toFloat().isFinite()) { "invalid_camera" }
        setNativeCamera(camera)
    }
    protected abstract fun setNativeCamera(camera: Camera)
    abstract fun captureState(): Deferred<MapState>
    fun createVectorLayer(drawOrder: Int = 3): VectorLayer { checkOpen(); return VectorLayer(this, createNativeLayer(drawOrder)) }
    protected abstract fun createNativeLayer(drawOrder: Int): Int
    private fun checkLayer(layer: VectorLayer) { checkOpen(); require(layer.owner === this) { "wrong_map" } }
    fun replaceLine(layer: VectorLayer, lonLat: DoubleArray, style: String = redStyle): Deferred<UpdateResult> {
        checkLayer(layer)
        require(lonLat.size % 2 == 0 && lonLat.size != 2) { "invalid_geometry" }
        for (i in lonLat.indices step 2) require(lonLat[i].isFinite() && lonLat[i+1].isFinite()
            && lonLat[i] in -180.0..180.0 && lonLat[i+1] in -90.0..90.0) { "invalid_geometry" }
        return updateNative(layer.id, lonLat, null, style)
    }
    fun replaceGeoJson(layer: VectorLayer, json: String, style: String = redStyle): Deferred<UpdateResult> {
        checkLayer(layer); return updateNative(layer.id, null, json, style)
    }
    fun setStyle(layer: VectorLayer, style: String): Deferred<UpdateResult> {
        checkLayer(layer); return updateNative(layer.id, null, null, style)
    }
    protected abstract fun updateNative(id: Int, lonLat: DoubleArray?, json: String?, style: String): Deferred<UpdateResult>
    fun removeLayer(layer: VectorLayer) { checkLayer(layer); removeNativeLayer(layer.id) }
    protected abstract fun removeNativeLayer(id: Int)
    fun geometryJson(layer: VectorLayer): String? { checkLayer(layer); return nativeGeometryJson(layer.id) }
    protected abstract fun nativeGeometryJson(id: Int): String?
    fun replacePolygon(layer: VectorLayer, rings: List<DoubleArray>, style: String): Deferred<UpdateResult> {
        checkLayer(layer)
        require(rings.isNotEmpty() && rings.all { it.size >= 8 && it.size % 2 == 0 && it.all(Double::isFinite) }) { "invalid_geometry" }
        return updateNativePolygon(layer.id, rings, style)
    }
    protected abstract fun updateNativePolygon(id: Int, rings: List<DoubleArray>, style: String): Deferred<UpdateResult>
    fun layerBounds(layer: VectorLayer): GeoBounds? { checkLayer(layer); return nativeLayerBounds(layer.id) }
    protected abstract fun nativeLayerBounds(id: Int): GeoBounds?
    /** Returns the GeoJSON of the nearest feature within tolerance view units. */
    fun pickFeature(layer: VectorLayer, tap: MapTap, tolerance: Double = 10.0): String? {
        checkLayer(layer); return nativePickFeature(layer.id, tap, tolerance)
    }
    protected abstract fun nativePickFeature(id: Int, tap: MapTap, tolerance: Double): String?

    /** Camera and drawable changes made inside the block are animated by the SDK. */
    abstract fun animate(duration: Double? = null, fly: Boolean = false, linear: Boolean = false, changes: () -> Unit): MapAnimation
    /** Changes only supplied fields. Angles are degrees; read the current camera with captureState(). */
    fun moveCamera(center: GeoPoint? = null, zoom: Double? = null, angle: Double? = null, pitch: Double? = null) {
        checkOpen()
        require(center == null || (center.latitude in -90.0..90.0 && center.longitude in -180.0..180.0)) { "invalid_camera" }
        require(listOfNotNull(zoom, angle, pitch).all(Double::isFinite)
            && (angle == null || angle.toFloat().isFinite()) && (pitch == null || pitch in 0.0..45.0)) { "invalid_camera" }
        moveNativeCamera(center, zoom, angle, pitch)
    }
    protected abstract fun moveNativeCamera(center: GeoPoint?, zoom: Double?, angle: Double?, pitch: Double?)
    abstract fun setOrigin(x: Double, y: Double)
    abstract fun fitBounds(bounds: GeoBounds, zoomDelta: Double = 0.0)
    abstract fun toDisplay(point: GeoPoint): ScreenPoint
    abstract fun setStyleOptions(options: Map<String, String>)
    /** Null restores the vector base; templates use {z} {x} {y}. */
    abstract fun setRasterTiles(urlTemplates: List<String>?, cacheName: String = "osm_tiles.db", attribution: String = "")
    abstract fun reloadTiles()
    abstract fun enableClipping(bounds: GeoBounds, minLevel: Double, maxLevel: Double)
    abstract var altitudeScale: Float
    abstract var drawHillshades: Boolean
    abstract var drawElevationLines: Boolean
    abstract var drawSlopes: Boolean

    abstract fun addImage(image: SvgImage, drawOrder: Int, position: GeoPoint, centered: Boolean = false): MapImage
    abstract fun addImageGroup(variants: List<SvgImage>, drawOrder: Int): MapImageGroup
    abstract fun addBalloon(textStyle: String, drawOrder: Int = 10): MapBalloon
    abstract suspend fun addMarkers(geoJsonAsset: String, style: MarkerStyle, drawOrder: Int): MapMarkers
    abstract fun addMarkers(results: FeatureCollection, image: SvgImage, drawOrder: Int): MapMarkers
    abstract fun addTrack(style: String, drawOrder: Int): MapTrack
    abstract fun addLineArrow(style: String, head: SvgImage, drawOrder: Int): MapLineArrow
    abstract fun addUserLocation(drawOrder: Int): UserLocationMarker

    fun dispose() {
        if (disposed) return
        disposed = true
        pending.toList().forEach { it.completeExceptionally(IllegalStateException("map_disposed")) }
        pending.clear(); onTap = null; onMapTap = null; onMapLongPress = null
        releaseNative()
    }
    protected abstract fun releaseNative()
}

const val redStyle = "line{width:4pt;color:#E74C3C;}"
const val blueStyle = "line{width:5pt;color:#2650D6;}"
internal val fixture = Json.parseToJsonElement(fixtureJson).jsonObject
internal fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double
internal val fixtureCamera get() = fixture.getValue("camera").jsonObject.let {
    Camera(it.number("latitude"), it.number("longitude"), it.number("zoom"))
}

/** fixture adds the Stage A track and marker; demo screens pass false. */
@Composable expect fun GLMap(modifier: Modifier, onReady: (MapController) -> Unit, onTap: () -> Unit, fixture: Boolean = true)
