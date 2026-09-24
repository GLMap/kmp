package software.globus.lab.kmp

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import globus.glmap.*
import globus.glroute.*
import globus.glsearch.*
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

@Composable actual fun rememberSdk(): GLMapSdk {
    val context = LocalContext.current.applicationContext
    return remember { AndroidSdk(context) }
}

internal fun GLMapError.exception() = SdkException(if (isCancelled) SdkError.Cancelled else SdkError.Native, toString())

internal class AndroidSearchResults(val objects: Array<GLMapVectorObject>) : SearchResults() {
    override val places = objects.map { it.place() }
    private var closed = false
    override fun close() { if (!closed) { closed = true; objects.forEach { it.close() } } }
}
internal class AndroidManeuver(val native: GLRouteManeuver) : RouteManeuver() {
    override val type get() = native.type
    override val shortInstruction get() = native.shortInstruction ?: ""
    override val start get() = native.startPoint.geoPoint()
}
internal class AndroidRoute(private var native: GLRoute?) : Route() {
    private val trackers = mutableSetOf<AndroidTracker>()
    fun open() = native ?: throw SdkException(SdkError.Closed, "Route has been released")
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

private class AndroidSdk(private val context: Context) : GLMapSdk() {
    private val main = Handler(Looper.getMainLooper())
    private val dataSets = mapOf(DataSet.Map to GLMapInfo.DataSet.MAP, DataSet.Navigation to GLMapInfo.DataSet.NAVIGATION,
        DataSet.Elevation to GLMapInfo.DataSet.ELEVATION)

    override fun initialize(apiKey: String) {
        if (!GLMapManager.Initialize(context, apiKey, null)) throw SdkException(SdkError.Native, "GLMap initialization failed")
    }
    override var tileDownloading = false
        set(value) { field = value; GLMapManager.SetTileDownloadingAllowed(value) }
    override fun addBundledMap(asset: String) {
        // The SDK asset cache does not create nested directories; register a regular file instead.
        val file = File(File(context.filesDir, "glmap-assets").apply { mkdirs() }, File(asset).name)
        if (!file.exists()) {
            val temporary = File.createTempFile("dataset-", ".tmp", file.parentFile)
            try {
                context.assets.open(asset).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.renameTo(file)) { "Cannot install bundled dataset" }
            } catch (error: java.io.IOException) { throw SdkException(SdkError.AssetUnavailable, asset) }
            finally { temporary.delete() }
        }
        register(GLMapInfo.DataSet.MAP, null, file)
    }
    private fun register(dataSet: Int, bounds: GLMapBBox?, file: File) {
        // Re-adding a registered file fails; replace our own registration.
        GLMapManager.RemoveDataSet(dataSet, file.path)
        if (!GLMapManager.AddDataSet(dataSet, bounds, file.path, null, null)) throw SdkException(SdkError.Native, "Cannot open ${file.name}")
    }
    override suspend fun readAsset(name: String): String = withContext(Dispatchers.IO) {
        try { context.assets.open(name).use { it.readBytes().decodeToString() } }
        catch (error: java.io.IOException) { throw SdkException(SdkError.AssetUnavailable, name) }
    }

    override suspend fun search(query: SearchQuery): SearchResults = suspendCancellableCoroutine { reply ->
        val request = GLSearchRequest(if (query.autocomplete) GLSearchRequestType.Autocomplete else GLSearchRequestType.Search,
            query.text, MapGeoPoint(query.center.latitude, query.center.longitude), query.limit, arrayOf("en", "native"),
            query.categories.takeIf { it.isNotEmpty() }?.toTypedArray())
        val callback = object : GLSearchRequest.ResultsCallback {
            override fun onResult(objects: GLMapVectorObjectList) {
                val results = AndroidSearchResults(objects.toArray()); objects.close()
                main.post { if (reply.isActive) reply.resume(results) { _, value, _ -> value.close() } else results.close() }
            }
            override fun onError(error: GLMapError) { main.post { if (reply.isActive) reply.resumeWithException(error.exception()) } }
        }
        val id = if (query.offline) request.startOffline(callback) else request.startOnline(callback)
        reply.invokeOnCancellation { GLSearchRequest.cancel(id) }
    }

    override suspend fun route(query: RouteQuery): Route {
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
    override fun buildRoute(steps: List<RouteStep>): Route {
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

    override suspend fun downloadArea(bounds: GeoBounds, files: List<AreaFile>, onProgress: (DataSet, Long, Long) -> Unit) {
        if (files.isEmpty()) throw SdkException(SdkError.InvalidArgument, "Choose a dataset")
        val box = bounds.bbox()
        suspendCancellableCoroutine { reply ->
            val tasks = mutableSetOf<Long>()
            var remaining = files.size
            var failure: SdkException? = null
            fun finished(error: SdkException?) {
                if (failure == null) failure = error
                if (--remaining > 0 || !reply.isActive) return
                failure?.let(reply::resumeWithException) ?: reply.resume(Unit)
            }
            reply.invokeOnCancellation { main.post {
                tasks.toList().forEach(GLMapManager::CancelDownloadTask)
            } }
            files.forEach { area ->
                val kind = dataSets.getValue(area.dataSet); val file = File(context.cacheDir, File(area.fileName).name)
                fun install() = try { register(kind, box, file); null } catch (error: SdkException) { file.delete(); error }
                // Retry incomplete files left by older builds instead of treating them as a cache hit.
                if (file.exists() && install() == null) { finished(null); return@forEach }
                val partial = File(context.cacheDir, "${file.name}.${UUID.randomUUID()}.part")
                var id = 0L
                id = GLMapManager.DownloadDataSet(kind, partial.path, box, object : GLMapManager.DownloadCallback {
                    override fun onProgress(total: Long, downloaded: Long, speed: Double) { main.post { if (reply.isActive) onProgress(area.dataSet, downloaded, total) } }
                    override fun onFinished(error: GLMapError?) { main.post {
                        if (!tasks.remove(id)) return@post
                        try {
                            if (!reply.isActive) return@post
                            val result = when {
                                error != null -> error.exception()
                                !file.exists() && !partial.renameTo(file) -> SdkException(SdkError.Native, "Cannot install ${file.name}")
                                else -> install()
                            }
                            finished(result)
                        } finally { partial.delete() }
                    } }
                })
                if (id == 0L) { partial.delete(); finished(SdkException(SdkError.Native, "Cannot start download for ${file.name}")) }
                else tasks.add(id)
            }
        }
    }

    private fun info(id: Long) = GLMapManager.GetMapWithID(id) ?: throw SdkException(SdkError.InvalidArgument, "Unknown region $id")
    private fun onDevice(map: GLMapInfo): Boolean =
        map.dataSetsWithState(GLMapInfo.State.NOT_DOWNLOADED) != GLMapInfo.DataSetMask.ALL || map.maps?.any(::onDevice) == true
    override fun regions(parent: Long?): List<Region> = (if (parent == null) GLMapManager.GetMaps() else info(parent).maps).orEmpty().map { map ->
        val task = GLMapManager.getDownloadTasks(map.mapID, GLMapInfo.DataSetMask.ALL)?.firstOrNull()
        Region(map.mapID, map.getLocalizedName(placeLocale) ?: "Region ${map.mapID}", map.isoCode, map.isCollection, onDevice(map),
            map.dataSetsWithState(GLMapInfo.State.DOWNLOADED) != 0, task != null,
            task?.takeIf { it.total > 0 }?.let { it.downloaded.toDouble() / it.total }, map.getSizeOnServer(GLMapInfo.DataSetMask.ALL),
            map.getSizeOnDisk(GLMapInfo.DataSetMask.ALL))
    }.sortedBy { it.name }
    override suspend fun refreshRegions(): Unit = suspendCancellableCoroutine { reply ->
        GLMapManager.UpdateMapList { _, error -> main.post {
            if (reply.isActive) error?.let { reply.resumeWithException(it.exception()) } ?: reply.resume(Unit)
        } }
    }
    override val regionChanges: Flow<Unit> = callbackFlow {
        val listener = object : GLMapManager.StateListener {
            override fun onStartDownloading(task: GLMapDownloadTask) { trySend(Unit) }
            override fun onDownloadProgress(task: GLMapDownloadTask) { trySend(Unit) }
            override fun onFinishDownloading(task: GLMapDownloadTask) { trySend(Unit) }
            override fun onStateChanged(map: GLMapInfo?, dataSet: Int) { trySend(Unit) }
        }
        GLMapManager.addStateListener(listener)
        awaitClose { GLMapManager.removeStateListener(listener) }
    }
    override fun downloadRegion(id: Long) { GLMapManager.DownloadDataSets(info(id), GLMapInfo.DataSetMask.ALL) }
    override fun cancelRegionDownload(id: Long) { GLMapManager.getDownloadTasks(id, GLMapInfo.DataSetMask.ALL)?.forEach { it.cancel() } }
    override fun deleteRegion(id: Long) { GLMapManager.DeleteDataSets(info(id), GLMapInfo.DataSetMask.ALL) }
}
