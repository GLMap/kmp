package globus.glmap.core
import globus.glmap.*
fun GeoPoint.mapPoint(): MapPoint = MapPoint.CreateFromGeoCoordinates(latitude, longitude)
fun MapPoint.geoPoint() = MapGeoPoint(this).let { GeoPoint(it.lat, it.lon) }
fun GeoBounds.bbox() = GLMapBBox().apply {
    addPoint(MapPoint.CreateFromGeoCoordinates(south, west)); addPoint(MapPoint.CreateFromGeoCoordinates(north, east))
}
fun GLMapBBox.geoBounds(): GeoBounds {
    val a = MapGeoPoint(MapPoint(origin_x, origin_y)); val b = MapGeoPoint(MapPoint(origin_x + size_x, origin_y + size_y))
    return GeoBounds(minOf(a.lat, b.lat), minOf(a.lon, b.lon), maxOf(a.lat, b.lat), maxOf(a.lon, b.lon))
}
val placeLocale by lazy { GLMapLocaleSettings(arrayOf("en", "native"), GLMapLocaleSettings.UnitSystem.International) }
fun GLMapVectorObject.place(): Place {
    val name = localizedName(placeLocale)?.use { it.string } ?: ""
    val detail = valueForKey("search:secondaryText")?.use { it.string } ?: ""
    return Place(name, detail, point().geoPoint())
}

fun GLMapError.exception() = SdkException(if (isCancelled) SdkError.Cancelled else SdkError.Native,toString())
