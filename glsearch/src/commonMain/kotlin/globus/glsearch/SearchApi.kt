package globus.glsearch
import globus.glmap.core.*
data class SearchQuery(val text: String, val center: GeoPoint, val offline: Boolean, val autocomplete: Boolean,
    val categories: List<String> = emptyList(), val limit: Int = 50)
abstract class SearchResults:FeatureCollection {
 abstract val places:List<Place>
 final override val features get()=places
 abstract fun close()
}
expect suspend fun GLMapSdk.search(query:SearchQuery):SearchResults
fun MapQueryTarget.objectAt(tap:MapTap,maxDistance:Double=20.0):Place? {
 val state=queryState() ?: return null
 try { return pickMapObject(state,tap,maxDistance) } finally {state.close()}
}
internal expect fun pickMapObject(state:MapQueryState,tap:MapTap,maxDistance:Double):Place?
