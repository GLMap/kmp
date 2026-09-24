@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package globus.glmap.core
import globus.native.core.*
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.CoreGraphics.*
import platform.posix.ECANCELED
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
fun GeoPoint.mapPoint() = GLMapPointMakeFromGeoCoordinates(latitude, longitude)
fun CValue<GLMapPoint>.geoPoint() = GLMapGeoPointFromMapPoint(this).useContents { GeoPoint(lat, lon) }
fun GeoBounds.bbox() = GLMapBBoxAddPoint(GLMapBBoxAddPoint(GLMapBBoxEmpty.readValue(),
    GLMapPointMakeFromGeoCoordinates(south, west)), GLMapPointMakeFromGeoCoordinates(north, east))
fun CValue<GLMapBBox>.geoBounds(): GeoBounds = useContents {
    val a = GLMapGeoPointFromMapPoint(cValue<GLMapPoint> { x = origin.x; y = origin.y }).useContents { GeoPoint(lat, lon) }
    val b = GLMapGeoPointFromMapPoint(cValue<GLMapPoint> { x = origin.x + size.x; y = origin.y + size.y }).useContents { GeoPoint(lat, lon) }
    GeoBounds(minOf(a.latitude, b.latitude), minOf(a.longitude, b.longitude), maxOf(a.latitude, b.latitude), maxOf(a.longitude, b.longitude))
}
fun Long.mapColor() = GLMapColorMake(((this shr 16) and 0xFF).toUByte(), ((this shr 8) and 0xFF).toUByte(),
    (this and 0xFF).toUByte(), ((this shr 24) and 0xFF).toUByte())
val placeLocale by lazy { GLMapLocaleSettings(localesOrder = listOf("en", "native"), unitSystem = GLUnitSystem.GLUnitSystem_International) }
fun GLMapVectorObject.place() = Place(localizedName(placeLocale)?.asString() ?: "",
    valueForKey("search:secondaryText")?.asString() ?: "", point().geoPoint())
fun assetPath(name: String): String = NSBundle.mainBundle.pathForResource(name.substringBeforeLast('.'), name.substringAfterLast('.'))
    ?: throw SdkException(SdkError.AssetUnavailable, name)

fun NSError.exception() = SdkException(
    if (domain == NSPOSIXErrorDomain && code == ECANCELED.toLong()) SdkError.Cancelled else SdkError.Native, localizedDescription)
/** SDK completion blocks may arrive off the main thread. */
fun onMain(block: () -> Unit) { if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() } }
