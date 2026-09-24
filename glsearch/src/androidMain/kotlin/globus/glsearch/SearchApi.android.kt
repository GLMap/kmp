package globus.glsearch
import globus.glmap.core.*

import android.content.Context
import android.os.Handler
import android.os.Looper
import globus.glmap.*
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

private val main=Handler(Looper.getMainLooper())
internal class AndroidSearchResults(val objects: Array<GLMapVectorObject>) : SearchResults() {
    override val places = objects.map { it.place() }
    private var closed = false
    override fun retainVectorObjects():VectorObjects {
        if(closed) throw SdkException(SdkError.Closed)
        return GLMapVectorObjectList().use { list -> objects.forEachIndexed { i,obj -> list.insertObject(i.toLong(),obj) };VectorObjects(list.toArray()) }
    }
    override fun close() { if (!closed) { closed = true; objects.forEach { it.close() } } }
}
    actual suspend fun GLMapSdk.search(query: SearchQuery): SearchResults = suspendCancellableCoroutine { reply ->
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


internal actual fun pickMapObject(state:MapQueryState,tap:MapTap,maxDistance:Double):Place? =
    GLSearch.MapObjectNearPoint(state.native,(tap.x*state.density).toFloat(),(tap.y*state.density).toFloat(),maxDistance)?.use {it.place()}
