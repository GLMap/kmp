package globus.glmap.core
import globus.glmap.*
actual class MapQueryState(val native:GLMapViewState,val density:Double) {actual fun close()=native.close()}
actual class VectorObjects(val native:Array<GLMapVectorObject>) {actual fun close() {native.forEach {it.close()}}}
actual class TrackData(val native:GLMapTrackData) {actual fun close()=native.close()}
actual class LineData(val native:GLMapVectorObject,val index:Int) {actual fun close()=native.close()}
