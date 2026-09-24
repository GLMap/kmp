@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package globus.glmap.core

import globus.native.core.*
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

private class IosSdk : GLMapSdk() {
    private val manager get() = GLMapManager.sharedManager
    private fun native(dataSet: DataSet) = when (dataSet) {
        DataSet.Map -> GLMapInfoDataSet.GLMapInfoDataSet_Map
        DataSet.Navigation -> GLMapInfoDataSet.GLMapInfoDataSet_Navigation
        DataSet.Elevation -> GLMapInfoDataSet.GLMapInfoDataSet_Elevation
    }
    override fun initialize(apiKey: String) {
        // Core owns activation; the host supplies its SwiftPM resource product, not a renderer.
        if (!GLMapManager.activateWithApiKey(apiKey, resourcesBundle = coreResources(), andStoragePath = null))
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

private fun coreResources():NSBundle {
 val path=NSBundle.mainBundle.pathForResource("GLMap_GLMapCoreSwift","bundle")
     ?: throw SdkException(SdkError.AssetUnavailable,"Link the GLMapCore SwiftPM product with its resources")
 return NSBundle.bundleWithPath(path) ?: throw SdkException(SdkError.AssetUnavailable,"Core resources")
}
fun createGLMapSdk():GLMapSdk=IosSdk()
