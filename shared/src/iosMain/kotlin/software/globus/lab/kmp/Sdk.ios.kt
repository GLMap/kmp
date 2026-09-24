@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package software.globus.lab.kmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import glmap.native.*
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

@Composable actual fun rememberSdk(): GLMapSdk = remember { IosSdk() }

internal fun NSError.exception() = SdkException(
    if (domain == NSPOSIXErrorDomain && code == ECANCELED.toLong()) SdkError.Cancelled else SdkError.Native, localizedDescription)
/** SDK completion blocks may arrive off the main thread. */
internal fun onMain(block: () -> Unit) { if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() } }

internal class IosSearchResults(val objects: List<GLMapVectorObject>) : SearchResults() {
    override val places = objects.map { it.place() }
    override fun close() {}
}
internal class IosManeuver(val native: GLRouteManeuver) : RouteManeuver() {
    override val type get() = native.type().toInt()
    override val shortInstruction get() = native.shortInstruction() ?: ""
    override val start get() = native.startPoint().geoPoint()
}
internal class IosRoute(private var native: GLRoute?) : Route() {
    private val trackers = mutableSetOf<IosTracker>()
    fun open() = native ?: throw SdkException(SdkError.Closed, "Route has been released")
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

private class IosSdk : GLMapSdk() {
    private val manager get() = GLMapManager.sharedManager
    private fun native(dataSet: DataSet) = when (dataSet) {
        DataSet.Map -> GLMapInfoDataSet.GLMapInfoDataSet_Map
        DataSet.Navigation -> GLMapInfoDataSet.GLMapInfoDataSet_Navigation
        DataSet.Elevation -> GLMapInfoDataSet.GLMapInfoDataSet_Elevation
    }
    override fun initialize(apiKey: String) {
        // The host activated the SDK with its resource bundle; keep it when the key changes.
        if (!GLMapManager.activateWithApiKey(apiKey, resourcesBundle = manager.resourcesBundle, andStoragePath = null))
            throw SdkException(SdkError.Native, "GLMap initialization failed")
    }
    override var tileDownloading = false
        set(value) { field = value; manager.setTileDownloadingAllowed(value) }
    private fun register(dataSet: GLMapInfoDataSet, path: String, bounds: CValue<GLMapBBox>) {
        // A duplicate registration fails; replace our own.
        manager.removeDataSet(dataSet, path)
        val error = manager.addDataSet(dataSet, path, bounds)
        if (error.toInt() != 0) throw SdkException(SdkError.Native, "Cannot open ${path.substringAfterLast('/')} ($error)")
    }
    override fun addBundledMap(asset: String) = register(GLMapInfoDataSet.GLMapInfoDataSet_Map, assetPath(asset), GLMapBBoxEmpty.readValue())
    override suspend fun readAsset(name: String): String {
        val path = assetPath(name)
        return withContext(Dispatchers.Default) { NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) }
            ?: throw SdkException(SdkError.AssetUnavailable, name)
    }

    override suspend fun search(query: SearchQuery): SearchResults = suspendCancellableCoroutine { reply ->
        val request = GLSearchRequest(if (query.autocomplete) GLSearchRequestTypeAutocomplete else GLSearchRequestTypeSearch, query.text,
            GLMapGeoPointMake(query.center.latitude, query.center.longitude), query.limit.toLong(), listOf("en", "native"),
            query.categories.takeIf { it.isNotEmpty() })
        val completion = { objects: GLMapVectorObjectArray?, error: NSError? -> onMain {
            if (reply.isActive) {
                @Suppress("UNCHECKED_CAST")
                if (error != null) reply.resumeWithException(error.exception())
                else reply.resume(IosSearchResults((objects?.array() ?: emptyList<Any?>()) as List<GLMapVectorObject>))
            }
        } }
        val id = if (query.offline) request.startOfflineWithCompletion(completion) else request.startOnlineWithCompletion(completion)
        reply.invokeOnCancellation { if (id != 0L) GLSearchRequest.cancel(id) }
    }

    override suspend fun route(query: RouteQuery): Route {
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
    override fun buildRoute(steps: List<RouteStep>): Route {
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

    override suspend fun downloadArea(bounds: GeoBounds, files: List<AreaFile>, onProgress: (DataSet, Long, Long) -> Unit) {
        if (files.isEmpty()) throw SdkException(SdkError.InvalidArgument, "Choose a dataset")
        val box = bounds.bbox()
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        suspendCancellableCoroutine { reply ->
            val tasks = mutableSetOf<Long>()
            var remaining = files.size
            var failure: SdkException? = null
            fun finished(error: SdkException?) {
                if (failure == null) failure = error
                if (--remaining > 0 || !reply.isActive) return
                failure?.let(reply::resumeWithException) ?: reply.resume(Unit)
            }
            reply.invokeOnCancellation { onMain {
                tasks.toList().forEach { manager.cancelDownload(it) }
            } }
            files.forEach { area ->
                val kind = native(area.dataSet); val path = "$caches/${area.fileName.substringAfterLast('/')}"
                fun install() = try { register(kind, path, box); null }
                    catch (error: SdkException) { NSFileManager.defaultManager.removeItemAtPath(path, null); error }
                if (NSFileManager.defaultManager.fileExistsAtPath(path) && install() == null) { finished(null); return@forEach }
                val partial = "$path.${NSUUID().UUIDString}.part"
                var id = 0L
                id = manager.downloadDataSet(kind, partial, box, progress = { total, downloaded, _ ->
                    onMain { if (reply.isActive) onProgress(area.dataSet, downloaded.toLong(), total.toLong()) }
                }, completion = { error -> onMain {
                    if (tasks.remove(id)) try {
                        if (reply.isActive) {
                            val result = when {
                                error != null -> error.exception()
                                !NSFileManager.defaultManager.fileExistsAtPath(path) &&
                                    !NSFileManager.defaultManager.moveItemAtPath(partial, path, null) -> SdkException(SdkError.Native, "Cannot install ${area.fileName}")
                                else -> install()
                            }
                            finished(result)
                        }
                    } finally { NSFileManager.defaultManager.removeItemAtPath(partial, null) }
                } })
                if (id == 0L) {
                    NSFileManager.defaultManager.removeItemAtPath(partial, null)
                    finished(SdkException(SdkError.Native, "Cannot start download for ${area.fileName}"))
                } else tasks.add(id)
            }
        }
    }

    private fun info(id: Long) = manager.cachedMaps()?.get(NSNumber(longLong = id)) as? GLMapInfo
        ?: throw SdkException(SdkError.InvalidArgument, "Unknown region $id")
    private fun stored(map: GLMapInfo) = map.stateForDataSet(GLMapInfoDataSet.GLMapInfoDataSet_Map) != GLMapInfoState.GLMapInfoState_NotDownloaded ||
        map.stateForDataSet(GLMapInfoDataSet.GLMapInfoDataSet_Navigation) != GLMapInfoState.GLMapInfoState_NotDownloaded
    @Suppress("UNCHECKED_CAST")
    private fun children(map: GLMapInfo) = map.subMaps as List<GLMapInfo>
    override fun regions(parent: Long?): List<Region> {
        @Suppress("UNCHECKED_CAST")
        val maps = if (parent == null) (manager.cachedMapList() ?: emptyList<Any?>()) as List<GLMapInfo> else children(info(parent))
        return maps.map { map ->
            val task = manager.downloadTasksForMap(map, GLMapInfoDataSetMask_All)?.firstOrNull() as? GLMapDownloadTask
            Region(map.mapID, map.nameInLanguage("en") ?: map.name(), map.isoCode, children(map).isNotEmpty(),
                stored(map) || children(map).any(::stored), map.dataSetsWithState(GLMapInfoState.GLMapInfoState_Downloaded).toInt() != 0, task != null,
                task?.takeIf { it.total > 0u }?.let { it.downloaded.toDouble() / it.total.toDouble() },
                map.sizeOnServerForDataSets(GLMapInfoDataSetMask_All).toLong(), map.sizeOnDiskForDataSets(GLMapInfoDataSetMask_All).toLong())
        }.sortedBy { it.name }
    }
    override suspend fun refreshRegions(): Unit = suspendCancellableCoroutine { reply ->
        manager.updateMapListWithCompletionBlock { _, _, error -> onMain {
            if (reply.isActive) error?.let { reply.resumeWithException(it.exception()) } ?: reply.resume(Unit)
        } }
    }
    override val regionChanges: Flow<Unit> = callbackFlow {
        val center = NSNotificationCenter.defaultCenter
        val observers = listOf(kGLMapInfoStateChanged, kGLMapDownloadTaskProgress, kGLMapDownloadTaskFinished).map { name ->
            center.addObserverForName(name, `object` = null, queue = NSOperationQueue.mainQueue) { trySend(Unit) }
        }
        awaitClose { observers.forEach(center::removeObserver) }
    }
    override fun downloadRegion(id: Long) { manager.downloadDataSets(GLMapInfoDataSetMask_All, forMap = info(id), withCompletionBlock = null) }
    override fun cancelRegionDownload(id: Long) {
        manager.downloadTasksForMap(info(id), GLMapInfoDataSetMask_All)?.forEach { (it as GLMapDownloadTask).cancel() }
    }
    override fun deleteRegion(id: Long) { manager.deleteDataSets(GLMapInfoDataSetMask_All, forMap = info(id)) }
}
