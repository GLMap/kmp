import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Real files and coroutines; only the SDK, its callback queue and platform file APIs are doubled.
class MainQueue {
    val jobs = ArrayDeque<() -> Unit>()
    fun post(job: () -> Unit) { jobs.addLast(job) }
    fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst()() }
}
val mainQueue = MainQueue()
fun onMain(job: () -> Unit) = job()
class Context(val cacheDir: File)
class GLMapBBox
class GeoBounds { fun bbox() = GLMapBBox() }
enum class DataSet { Map }
class AreaFile(val dataSet: DataSet, val fileName: String)
class DemoBoundsRecord { val isValid = true; val native = GLMapBBox() }
class DemoAreaFileRecord(val dataSet: String, val fileName: String)
fun dataSet(name: String) = 0
class GLMapError { fun exception() = SdkException("sdk_error", "native failure") }
object SdkError { const val Native = "sdk_error"; const val InvalidArgument = "invalid_argument" }
class SdkException(val code: String, message: String) : Exception(message)
class DemoFailure(val code: String) : Exception(code) {
    companion object {
        fun sdk(message: String) = DemoFailure("sdk_error")
        fun invalid(message: String) = DemoFailure("invalid_argument")
        fun cancelled() = DemoFailure("cancelled")
        fun of(error: GLMapError) = sdk("native failure")
    }
}
class Promise {
    var result: String? = null
    var settlements = 0
    fun resolve(value: Any?) { result = "success"; settlements++ }
    fun reject(error: DemoFailure) { result = error.code; settlements++ }
}
object Backend {
    class Task(val file: File, val completion: (GLMapError?) -> Unit)
    val tasks = linkedMapOf<Long, Task>()
    var next = 0L
    var failNextStart = false
    fun start(path: String, completion: (GLMapError?) -> Unit): Long {
        if (failNextStart) {
            failNextStart = false
            mainQueue.post { completion(GLMapError()) }
            return 0
        }
        val file = File(path); file.writeText("incomplete")
        tasks[++next] = Task(file, completion)
        return next
    }
    fun complete(id: Long, success: Boolean) {
        val task = tasks.remove(id)!!
        if (success) task.file.writeText("complete")
        task.completion(if (success) null else GLMapError())
        mainQueue.drain()
    }
    fun cancel(id: Long) { /* Native completion is deliberately delayed until complete(). */ }
}
object GLMapManager {
    interface DownloadCallback {
        fun onProgress(total: Long, downloaded: Long, speed: Double)
        fun onFinished(error: GLMapError?)
    }
    fun DownloadDataSet(kind: Int, path: String, bounds: GLMapBBox, callback: DownloadCallback) = Backend.start(path, callback::onFinished)
    fun CancelDownloadTask(id: Long) = Backend.cancel(id)
}
abstract class GLMapSdk {
    abstract suspend fun downloadArea(bounds: GeoBounds, files: List<AreaFile>, onProgress: (DataSet, Long, Long) -> Unit)
}
class KmpAndroid(val context: Context) : GLMapSdk() {
    val main = mainQueue
    val dataSets = mapOf(DataSet.Map to 0)
    fun register(kind: Int, box: GLMapBBox, file: File) {
        if (file.readText() != "complete") throw SdkException("sdk_error", "invalid file")
    }
    /* KMP_ANDROID */
}
val NSCachesDirectory = 0
val NSUserDomainMask = 0
var iosCaches = ""
fun NSSearchPathForDirectoriesInDomains(a: Int, b: Int, c: Boolean) = listOf(iosCaches)
class NSUUID { val UUIDString = UUID.randomUUID().toString() }
object NSFileManager { val defaultManager = Storage() }
class Storage {
    fun fileExistsAtPath(path: String) = File(path).exists()
    fun removeItemAtPath(path: String, error: Nothing?) = File(path).delete()
    fun moveItemAtPath(from: String, to: String, error: Nothing?) = File(from).renameTo(File(to))
}
class Manager {
    fun cancelDownload(id: Long) = Backend.cancel(id)
    fun downloadDataSet(kind: Int, path: String, box: GLMapBBox, progress: (ULong, ULong, Double) -> Unit,
                        completion: (GLMapError?) -> Unit) = Backend.start(path, completion)
}
class KmpIos : GLMapSdk() {
    val manager = Manager()
    fun native(kind: DataSet) = 0
    fun register(kind: Int, path: String, box: GLMapBBox) {
        if (File(path).readText() != "complete") throw SdkException("sdk_error", "invalid file")
    }
    /* KMP_IOS */
}
fun main() {
    val root = Files.createTempDirectory("glmap-download-regression-").toFile()
    try {
        for (platform in listOf("KMP Android", "KMP iOS")) {
            val directory = File(root, platform).apply { mkdirs() }; iosCaches = directory.path
            val sdk: GLMapSdk = if (platform.endsWith("Android")) KmpAndroid(Context(directory)) else KmpIos()
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            fun start(file: String) = scope.async { sdk.downloadArea(GeoBounds(), listOf(AreaFile(DataSet.Map, file))) { _, _, _ -> } }
            for (oldSuccess in listOf(false, true)) {
                val file = "cancel-$oldSuccess.map"
                val first = start(file); val old = Backend.next
                check(!File(directory, file).exists()) // no final filename before completion
                first.cancel(); mainQueue.drain()
                val second = start(file); val newer = Backend.next; val partial = Backend.tasks[newer]!!.file
                check(Backend.tasks[old]!!.file != partial)
                Backend.complete(old, oldSuccess)
                check(partial.exists() && !File(directory, file).exists() && second.isActive)
                Backend.complete(newer, true)
                check(second.isCompleted && !second.isCancelled && File(directory, file).readText() == "complete")
                val before = Backend.next
                check(start(file).isCompleted && Backend.next == before)
            }
            // Simulate process death: orphan partials are ignored; legacy invalid finals are retried.
            File(directory, "restart.map.orphan.part").writeText("incomplete")
            val restarted = start("restart.map"); Backend.complete(Backend.next, true)
            check(restarted.isCompleted && !restarted.isCancelled)
            File(directory, "legacy.map").writeText("incomplete")
            val legacy = start("legacy.map"); Backend.complete(Backend.next, true)
            check(legacy.isCompleted && !legacy.isCancelled)
            // A start failure can also deliver a later native callback; it must settle only once.
            Backend.failNextStart = true
            val failed = start("unwritable.map"); mainQueue.drain()
            check(failed.isCancelled && !File(directory, "unwritable.map").exists())
            scope.cancel()
            println("PASS $platform: cancellation/error and late success, retry, cache reuse, orphan/legacy files, start failure")
        }
    } finally { root.deleteRecursively() }
}
