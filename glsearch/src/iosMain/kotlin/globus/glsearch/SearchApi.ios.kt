@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package globus.glsearch
import globus.glmap.core.*

import globus.native.core.*
import globus.native.search.*
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

internal class IosSearchResults(objects: List<GLMapVectorObject>) : SearchResults() {
    private var retained: List<GLMapVectorObject>? = objects
    override val places = objects.map { it.place() }
    override fun retainVectorObjects(): VectorObjects =
        VectorObjects(retained ?: throw SdkException(SdkError.Closed))
    override fun close() { retained = null }
}
    actual suspend fun GLMapSdk.search(query: SearchQuery): SearchResults = suspendCancellableCoroutine { reply ->
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


internal actual fun pickMapObject(state:MapQueryState,tap:MapTap,maxDistance:Double):Place? =
    state.native.mapObjectAt(platform.CoreGraphics.CGPointMake(tap.x,tap.y),maxDistance)?.place()
