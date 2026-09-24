package globus.glmap.core
import kotlinx.coroutines.flow.Flow
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
data class Place(val name: String, val detail: String, val point: GeoPoint)
data class AreaFile(val dataSet: DataSet, val fileName: String)
data class Region(val id: Long, val name: String, val isoCode: String?, val isCollection: Boolean, val onDevice: Boolean,
    val downloaded: Boolean, val downloading: Boolean, val progress: Double?, val sizeOnServer: Long, val sizeOnDisk: Long)
data class LocationFix(val point: GeoPoint, val accuracy: Double = 10.0, val bearing: Double? = null)
abstract class GLMapSdk {
    abstract fun initialize(apiKey: String)
    abstract var tileDownloading: Boolean
    abstract fun addBundledMap(asset: String)
    abstract suspend fun readAsset(name: String): String
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

/** Cross-module capabilities contain only Core resources, never renderer/service classes. */
interface MapQueryTarget { fun queryState():MapQueryState? }
expect class MapQueryState { fun close() }
interface FeatureCollection { val features:List<Place>; fun retainVectorObjects():VectorObjects }
expect class VectorObjects { fun close() }
interface TrackSource { fun trackData(color:Long):TrackData }
expect class TrackData { fun close() }
interface LineSource { fun lineData():LineData }
expect class LineData { fun close() }
