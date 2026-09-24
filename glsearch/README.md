# glsearch-kmp

Text/category search, autocomplete and map-object queries for Kotlin Multiplatform
on Android and iOS. Search depends only on Core, not on Compose or the map renderer.

## Installation

Follow the [Maven and native host setup](../README.md#add-a-map-to-your-app), then
add to `commonMain`:

```kotlin
implementation("globus:glsearch-kmp:0.1.0-beta.1")
```

Requires Kotlin 2.4.20, Android API 24+ or iOS 16.4+, and native GLSearch 2.2.0.
On iOS, link `GLMapCore` with its resources and the `GLSearch` SwiftPM product.
Do not add the Map module unless you need a map UI.

## Search

Initialize Core first. For offline search, register or download map data covering
the query area; the demo includes a Montenegro map.

```kotlin
import globus.glmap.core.GLMapSdk
import globus.glmap.core.GeoPoint
import globus.glmap.core.Place
import globus.glsearch.SearchQuery
import globus.glsearch.search

suspend fun searchPodgorica(sdk: GLMapSdk): List<Place> {
    val results = sdk.search(SearchQuery(
        text = "Podgorica",
        center = GeoPoint(42.4341, 19.26),
        offline = true,
        autocomplete = false,
    ))
    try {
        return results.places.toList()
    } finally {
        results.close()
    }
}
```

`SearchResults` owns native resources. Close it after use; if you pass results to
Map for marker creation, preserve the required lifetime until that operation
finishes. Run searches in a lifecycle-bound coroutine and cancel superseded work.
Do not swallow `CancellationException` in general error handling.

Set `offline = false` for online search with a suitable API key and network access.
Use `autocomplete`, `categories` and `limit` to control suggestions and filtering.

## Query a displayed map

When your app also uses Map, import `globus.glsearch.objectAt` and call
`controller.objectAt(tap)`. The extension accepts Core's `MapQueryTarget` and
releases its temporary query state. Search still has no dependency on Map.

See the [demo code guide](../example/README.md) for search, result selection and
POI picking. Native SDK and map-data terms apply in addition to
[LICENSE.txt](../LICENSE.txt).
