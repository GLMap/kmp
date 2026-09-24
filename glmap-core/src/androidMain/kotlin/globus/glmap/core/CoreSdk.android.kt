package globus.glmap.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import globus.glmap.*
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

fun createGLMapSdk(context:Context):GLMapSdk=AndroidSdk(context.applicationContext)
