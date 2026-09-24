@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package globus.glmap.core
import globus.native.core.*
actual class MapQueryState(value:GLMapViewState) {
 private var value:GLMapViewState?=value
 val native get()=value ?: throw SdkException(SdkError.Closed)
 actual fun close() {value=null}
}
actual class VectorObjects(value:List<GLMapVectorObject>) {
 private var value:List<GLMapVectorObject>?=value
 val native get()=value ?: throw SdkException(SdkError.Closed)
 actual fun close() {value=null}
}
actual class TrackData(value:GLMapTrackData) {
 private var value:GLMapTrackData?=value
 val native get()=value ?: throw SdkException(SdkError.Closed)
 actual fun close() {value=null}
}
actual class LineData(value:GLMapVectorLine,val index:Int) {
 private var value:GLMapVectorLine?=value
 val native get()=value ?: throw SdkException(SdkError.Closed)
 actual fun close() {value=null}
}
