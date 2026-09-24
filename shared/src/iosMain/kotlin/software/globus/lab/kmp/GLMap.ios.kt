@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class)
package software.globus.lab.kmp

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import glmap.native.*
import kotlinx.cinterop.*
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.*
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.*
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLLocationManager
import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.NSObject

@Composable actual fun GLMap(modifier: Modifier, onReady: (MapController) -> Unit, onTap: () -> Unit, fixture: Boolean) {
    val controller = remember { IosMapController(fixture) }
    val currentReady by rememberUpdatedState(onReady)
    val currentTap by rememberUpdatedState(onTap)
    UIKitView(modifier = modifier, factory = { controller.map },
        update = { controller.onTap = { currentTap() } },
        onRelease = { controller.dispose() },
        properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = true))
    LaunchedEffect(controller) {
        while (controller.map.window == null || controller.map.bounds.useContents { size.width <= 0 || size.height <= 0 }) {
            withFrameNanos { }
        }
        currentReady(controller)
    }
}

internal fun GeoPoint.mapPoint() = GLMapPointMakeFromGeoCoordinates(latitude, longitude)
internal fun CValue<GLMapPoint>.geoPoint() = GLMapGeoPointFromMapPoint(this).useContents { GeoPoint(lat, lon) }
internal fun GeoBounds.bbox() = GLMapBBoxAddPoint(GLMapBBoxAddPoint(GLMapBBoxEmpty.readValue(),
    GLMapPointMakeFromGeoCoordinates(south, west)), GLMapPointMakeFromGeoCoordinates(north, east))
internal fun CValue<GLMapBBox>.geoBounds(): GeoBounds = useContents {
    val a = GLMapGeoPointFromMapPoint(cValue<GLMapPoint> { x = origin.x; y = origin.y }).useContents { GeoPoint(lat, lon) }
    val b = GLMapGeoPointFromMapPoint(cValue<GLMapPoint> { x = origin.x + size.x; y = origin.y + size.y }).useContents { GeoPoint(lat, lon) }
    GeoBounds(minOf(a.latitude, b.latitude), minOf(a.longitude, b.longitude), maxOf(a.latitude, b.latitude), maxOf(a.longitude, b.longitude))
}
internal fun Long.mapColor() = GLMapColorMake(((this shr 16) and 0xFF).toUByte(), ((this shr 8) and 0xFF).toUByte(),
    (this and 0xFF).toUByte(), ((this shr 24) and 0xFF).toUByte())
internal val placeLocale by lazy { GLMapLocaleSettings(localesOrder = listOf("en", "native"), unitSystem = GLUnitSystem.GLUnitSystem_International) }
internal fun GLMapVectorObject.place() = Place(localizedName(placeLocale)?.asString() ?: "",
    valueForKey("search:secondaryText")?.asString() ?: "", point().geoPoint())
internal fun assetPath(name: String): String = NSBundle.mainBundle.pathForResource(name.substringBeforeLast('.'), name.substringAfterLast('.'))
    ?: throw SdkException(SdkError.AssetUnavailable, name)

private class RasterSource(private val templates: List<String>, cachePath: String?) : GLMapRasterTileSource(cachePath) {
    override fun urlForTilePos(pos: CValue<GLMapTilePos>): NSURL? = pos.useContents {
        NSURL.URLWithString(templates[(x + y).mod(templates.size)].replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y"))
    }
}
private class PinSource(val images: List<UIImage>) : NSObject(), GLMapImageGroupDataSourceProtocol {
    private val lock = NSRecursiveLock()
    private var pins = emptyList<Pair<CValue<GLMapPoint>, Int>>()
    fun replace(next: List<Pair<CValue<GLMapPoint>, Int>>) { lock.lock(); pins = next; lock.unlock() }
    override fun startUpdate() = lock.lock()
    override fun endUpdate() = lock.unlock()
    override fun getVariantsCount() = images.size.toUInt()
    override fun getVariant(index: UInt, offset: CPointer<CGPoint>?): UIImage {
        val image = images[index.toInt()]
        offset?.pointed?.let { it.x = image.size.useContents { width } / 2 * image.scale; it.y = 0.0 }
        return image
    }
    override fun getImagesCount() = pins.size.toUInt()
    override fun getImageInfo(index: UInt, variant: CPointer<UIntVar>?, position: CPointer<GLMapPoint>?) {
        val pin = pins[index.toInt()]
        variant?.pointed?.value = pin.second.toUInt()
        position?.pointed?.let { out -> pin.first.useContents { out.x = x; out.y = y } }
    }
}

private class IosMapController(withFixture: Boolean) : MapController() {
    val map = GLMapView(frame = CGRectMake(0.0,0.0,0.0,0.0))
    private val marker = if (withFixture) GLMapImage(drawOrder = 2) else null
    private val fixtureLayer = if (withFixture) GLMapVectorLayer(drawOrder = 1) else null
    private val stylePath = checkNotNull(GLMapManager.sharedManager.resourcesBundle.pathForResource("DefaultStyle", "bundle"))
    private val drawables = mutableSetOf<Owned>()
    private class Layer(val native: GLMapVectorLayer) { var objects: GLMapVectorObjectArray? = null }
    private val layers = mutableMapOf<Int,Layer>()
    private var nextId = 0
    init {
        map.setStyle(checkNotNull(GLMapStyleParser(paths = listOf(stylePath)).parseFromResourcesWithError(null)))
        map.reloadTiles()
        if (fixtureLayer != null && marker != null) {
            setCamera(fixtureCamera)
            val objects = checkNotNull(GLMapVectorObject.createVectorObjectsFromGeoJSON(fixture.getValue("track").toString(), null))
            fixtureLayer.setVectorObjects(objects, withStyle = checkNotNull(GLMapVectorCascadeStyle.createStyle(redStyle)), completion = null)
            map.add(fixtureLayer)
            val point = fixture.getValue("marker").jsonObject
            val image = UIGraphicsImageRenderer(size = CGSizeMake(24.0,24.0)).imageWithActions { context ->
                val cg = checkNotNull(context).CGContext
                UIColor.whiteColor.setFill(); CGContextFillEllipseInRect(cg, CGRectMake(0.0,0.0,24.0,24.0))
                UIColor(red=38.0/255,green=80.0/255,blue=214.0/255,alpha=1.0).setFill()
                CGContextFillEllipseInRect(cg, CGRectMake(3.0,3.0,18.0,18.0))
            }
            marker.setImage(image, completion = null)
            marker.position = GLMapPointMakeFromGeoCoordinates(point.number("latitude"),point.number("longitude"))
            marker.offset = CGPointMake(12.0,12.0); map.add(marker)
        } else {
            map.isPitchEnabled = true; map.isRotateEnabled = true
            map.visibleMapInsetsProvider = { UIEdgeInsetsMake(16.0, 16.0, 16.0, 16.0) }
        }
        map.tapGestureBlock = { gesture -> if (!disposed) { onTap?.invoke(); gesture?.let { onMapTap?.invoke(tap(it)) } } }
        map.longPressGestureBlock = { gesture ->
            if (!disposed && gesture != null && gesture.state == UIGestureRecognizerStateBegan) onMapLongPress?.invoke(tap(gesture))
        }
    }
    private fun tap(gesture: UIGestureRecognizer): MapTap {
        val point = gesture.locationInView(map)
        return MapTap(map.makeMapPointFromDisplayPoint(point).geoPoint(), point.useContents { x }, point.useContents { y })
    }
    private fun display(tap: MapTap) = CGPointMake(tap.x, tap.y)
    override fun setNativeCamera(camera: Camera) {
        map.mapGeoCenter = cValue { lat = camera.latitude; lon = camera.longitude }
        map.mapZoomLevel = camera.zoom
        map.mapAngle = camera.angle.toFloat(); map.mapPitch = camera.pitch.toFloat()
    }
    override fun captureState(): Deferred<MapState> = request { done ->
        check(map.window != null) { "map_unavailable" }
        map.captureState { value ->
            val state = checkNotNull(value)
            val c = state.geoCenter; val o = state.origin
            done(MapState(c.useContents { lat },c.useContents { lon },state.zoom,state.scale,
                state.angle.toDouble(),state.pitch.toDouble(),o.useContents { x },o.useContents { y }))
        }
    }
    override fun createNativeLayer(drawOrder: Int): Int {
        val layer = GLMapVectorLayer(drawOrder = drawOrder); val id = ++nextId
        layers[id] = Layer(layer); map.add(layer); return id
    }
    override fun updateNative(id: Int, lonLat: DoubleArray?, json: String?, style: String): Deferred<UpdateResult> {
        val entry = checkNotNull(layers[id]) { "layer_removed" }
        val parsed = parse(style)
        val objects = if (json != null) checkNotNull(GLMapVectorObject.createVectorObjectsFromGeoJSON(json,null)) { "invalid_geometry" }
            else if (lonLat != null) line(lonLat) else checkNotNull(entry.objects) { "missing_geometry" }
        entry.objects = objects
        return submit(entry, objects, parsed)
    }
    private fun line(values: DoubleArray): GLMapVectorObjectArray {
        val objects = GLMapVectorObjectArray()
        if (values.isNotEmpty()) {
            val builder = GeometryBuilder()
            values.usePinned { pinned ->
                GLMapLabAddLineLonLat(builder,pinned.addressOf(0),(values.size/2).toULong())
            }
            objects.addObject(checkNotNull(builder.build()))
        }
        return objects
    }
    override fun removeNativeLayer(id: Int) { map.remove(checkNotNull(layers.remove(id)) { "layer_removed" }.native) }
    override fun nativeGeometryJson(id: Int): String? {
        val objects = checkNotNull(layers[id]) { "layer_removed" }.objects ?: return null
        return if (objects.count == 0uL) null else objects.objectAtIndex(0u).asGeoJSON()
    }
    private fun parse(style: String): GLMapVectorCascadeStyle {
        val parser = GLMapStyleParser()
        require(parser.parseNextString(style,null)) { "invalid_style" }
        return checkNotNull(parser.finishWithError(null)) { "invalid_style" }
    }
    private fun submit(entry: Layer, objects: GLMapVectorObjectArray, parsed: GLMapVectorCascadeStyle): Deferred<UpdateResult> = request { done ->
        entry.native.setVectorObjects(objects,withStyle = parsed,updateCompletion = { outcome ->
            done(when(outcome) {
                GLMapVectorLayerUpdateResultReady -> UpdateResult.Ready
                GLMapVectorLayerUpdateResultSuperseded -> UpdateResult.Superseded
                GLMapVectorLayerUpdateResultCancelled -> UpdateResult.Cancelled
                else -> UpdateResult.Failed
            })
        })
    }
    override fun updateNativePolygon(id: Int, rings: List<DoubleArray>, style: String): Deferred<UpdateResult> {
        val entry = checkNotNull(layers[id]) { "layer_removed" }
        val parsed = parse(style)
        val builder = GeometryBuilder(); builder.beginPolygon()
        rings.forEach { ring -> ring.usePinned { GLMapLabAddLineLonLat(builder,it.addressOf(0),(ring.size/2).toULong()) } }
        val objects = GLMapVectorObjectArray(); objects.addObject(checkNotNull(builder.build()) { "invalid_geometry" })
        entry.objects = objects
        return submit(entry, objects, parsed)
    }
    override fun nativeLayerBounds(id: Int): GeoBounds? {
        val objects = checkNotNull(layers[id]) { "layer_removed" }.objects ?: return null
        return if (objects.count == 0uL) null else objects.bbox().geoBounds()
    }
    override fun nativePickFeature(id: Int, tap: MapTap, tolerance: Double): String? {
        val objects = checkNotNull(layers[id]) { "layer_removed" }.objects ?: return null
        val target = map.makeMapPointFromDisplayPoint(display(tap))
        val distance = map.makeMapPointFromDisplayDelta(CGPointMake(0.0, tolerance)).useContents { kotlin.math.hypot(x, y) }
        return memScoped {
            val nearest = alloc<GLMapPoint>()
            (0uL until objects.count).firstNotNullOfOrNull { index ->
                objects.objectAtIndex(index).takeIf { it.findNearestPoint(nearest.ptr, target, distance) }?.asGeoJSON()
            }
        }
    }

    override fun animate(duration: Double?, fly: Boolean, linear: Boolean, changes: () -> Unit): MapAnimation {
        checkOpen()
        val native = map.animate { animation ->
            if (duration != null) animation?.setDuration(duration)
            if (linear) animation?.setTransition(GLMapTransitionLinear)
            if (fly) animation?.setFlyToMode(GLFlyToMode.GLFlyToMode_Enabled)
            changes()
        }
        return object : MapAnimation() { override fun cancel() { native.cancel(false) } }
    }
    override fun moveNativeCamera(center: GeoPoint?, zoom: Double?, angle: Double?, pitch: Double?) {
        center?.let { map.setMapCenter(it.mapPoint()) }
        zoom?.let { map.setMapZoomLevel(it) }
        angle?.let { map.setMapAngle(it.toFloat()) }
        pitch?.let { map.setMapPitch(it.toFloat()) }
    }
    override fun setOrigin(x: Double, y: Double) { checkOpen(); map.setMapOrigin(CGPointMake(x, y)) }
    override fun fitBounds(bounds: GeoBounds, zoomDelta: Double) {
        checkOpen()
        val box = bounds.bbox(); val scale = map.mapScaleForBBox(box)
        map.setMapCenter(GLMapBBoxCenter(box))
        if (scale.isFinite() && scale > 0) map.setMapScale(scale * 2.0.pow(zoomDelta)) else map.setMapZoomLevel(15.0)
    }
    override fun toDisplay(point: GeoPoint): ScreenPoint {
        checkOpen()
        return map.makeDisplayPointFromMapPoint(point.mapPoint()).useContents { ScreenPoint(x, y) }
    }
    override fun setStyleOptions(options: Map<String, String>) {
        checkOpen()
        val parser = GLMapStyleParser(paths = listOf(stylePath, NSBundle.mainBundle.bundlePath))
        parser.setOptions(options as Map<Any?, *>, defaultValue = true)
        map.setStyle(checkNotNull(parser.parseFromResourcesWithError(null)) { "invalid_style" }); map.reloadTiles()
    }
    override fun setRasterTiles(urlTemplates: List<String>?, cacheName: String, attribution: String) {
        checkOpen()
        if (urlTemplates == null) { map.setBase(GLMapVectorTileSource()); return }
        require(urlTemplates.isNotEmpty() && urlTemplates.all { it.startsWith("https://") }) { SdkError.InvalidArgument }
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val source = RasterSource(urlTemplates, "$documents/$cacheName")
        source.setValidZoomMask(((1 shl 20) - 1).toUInt()); source.setAttributionText(attribution)
        map.setBase(source)
    }
    override fun reloadTiles() { checkOpen(); map.reloadTiles() }
    override fun enableClipping(bounds: GeoBounds, minLevel: Double, maxLevel: Double) {
        checkOpen(); map.enableClipping(bounds.bbox(), minLevel.toFloat(), maxLevel.toFloat())
    }
    override var altitudeScale: Float
        get() = map.altitudeScale
        set(value) { checkOpen(); map.altitudeScale = value }
    override var drawHillshades: Boolean
        get() = map.drawHillshades
        set(value) { checkOpen(); map.drawHillshades = value }
    override var drawElevationLines: Boolean
        get() = map.drawElevationLines
        set(value) { checkOpen(); map.drawElevationLines = value }
    override var drawSlopes: Boolean
        get() = map.drawSlopes
        set(value) { checkOpen(); map.drawSlopes = value }
    override fun objectAt(tap: MapTap, maxDistance: Double): Place? {
        checkOpen(); return map.state().mapObjectAt(display(tap), maxDistance)?.place()
    }

    private fun image(image: SvgImage): UIImage {
        val factory = GLMapVectorImageFactory.sharedFactory; val path = assetPath(image.asset)
        return (image.tint?.let { factory.imageFromSvg(path, withScale = image.scale, andTintColor = it.mapColor()) }
            ?: factory.imageFromSvg(path, withScale = image.scale)) ?: throw SdkException(SdkError.AssetUnavailable, image.asset)
    }
    private fun textStyle(style: String) = GLMapVectorStyle.createStyle(style) ?: throw SdkException(SdkError.InvalidArgument, "style")
    /** Native objects owned by one common drawable. */
    private open inner class Owned(val objects: List<GLMapDrawObject>) {
        init { drawables.add(this); objects.forEach { map.add(it) } }
        val lifetime = DrawableLifetime { disposed }
        fun checkActive() = lifetime.checkOpen()
        var isHidden = false
            set(value) { checkActive(); field = value; objects.forEach { it.hidden = value } }
        fun removeOwned() { if (drawables.remove(this)) release() }
        open fun release() { if (!lifetime.release()) return; objects.forEach { map.remove(it) } }
    }
    override fun addImage(image: SvgImage, drawOrder: Int, position: GeoPoint, centered: Boolean): MapImage {
        checkOpen()
        val picture = image(image); val native = GLMapImage(drawOrder = drawOrder)
        native.setImage(picture, completion = null)
        native.offset = picture.size.useContents { CGPointMake(width / 2, if (centered) height / 2 else 0.0) }
        native.position = position.mapPoint()
        val owned = Owned(listOf(native))
        return object : MapImage() {
            override var hidden by owned::isHidden
            override var position = position
                set(value) { owned.checkActive(); field = value; native.position = value.mapPoint() }
            override var scale = 1.0
                set(value) { owned.checkActive(); field = value; native.scale = value }
            override fun remove() = owned.removeOwned()
        }
    }
    override fun addImageGroup(variants: List<SvgImage>, drawOrder: Int): MapImageGroup {
        checkOpen(); require(variants.isNotEmpty()) { SdkError.InvalidArgument }
        val source = PinSource(variants.map(::image))
        val native = GLMapImageGroup(callback = source, andDrawOrder = drawOrder)
        val owned = Owned(listOf(native))
        return object : MapImageGroup() {
            override var hidden by owned::isHidden
            override fun setPins(pins: List<Pin>) {
                owned.checkActive()
                require(pins.all { it.variant in source.images.indices }) { SdkError.InvalidArgument }
                source.replace(pins.map { it.point.mapPoint() to it.variant }); native.setNeedsUpdate(false)
            }
            override fun remove() = owned.removeOwned()
        }
    }
    override fun addBalloon(textStyle: String, drawOrder: Int): MapBalloon {
        checkOpen()
        val style = textStyle(textStyle)
        val background = UIImage.imageNamed("balloon") ?: throw SdkException(SdkError.AssetUnavailable, "balloon.png")
        val native = GLMapBalloon(drawOrder = drawOrder)
        val inset = background.size.useContents { UIEdgeInsetsMake(kotlin.math.floor(height / 2), kotlin.math.floor(width / 2), kotlin.math.floor(height / 2), kotlin.math.floor(width / 2)) }
        native.setBackgroundImage(background, insets = inset); native.hidden = true
        val owned = Owned(listOf(native))
        return object : MapBalloon() {
            override var hidden by owned::isHidden
            override fun show(point: GeoPoint, text: String) {
                owned.checkActive()
                native.setText(text, withStyle = style, insets = UIEdgeInsetsMake(8.0, 12.0, 8.0, 12.0), completion = null)
                native.position = point.mapPoint(); owned.isHidden = false
            }
            override fun remove() = owned.removeOwned()
        }
    }
    private fun markers(items: List<GLMapVectorObject>, bounds: GeoBounds?, style: MarkerStyle, drawOrder: Int): MapMarkers {
        val styles = GLMapMarkerStyleCollection(); var maxWidth = 0.0
        style.images.forEach { spec -> image(spec).let { maxWidth = maxOf(maxWidth, it.size.useContents { width }); styles.addStyleWithImage(it) } }
        val text = textStyle(style.textStyle); val key = style.nameKey; val union = style.unionStyle; val last = style.images.size - 1
        styles.setMarkerLocationBlock { marker -> (marker as GLMapVectorObject).point() }
        styles.setMarkerDataFillBlock { marker, data ->
            GLMapMarkerSetStyle(data, 0u)
            if (key != null) (marker as? GLMapVectorObject)?.valueForKey(key)?.asString()?.let {
                GLMapMarkerSetText(data, GLMapTextAlignment.GLMapTextAlignment_Undefined, it, CGPointMake(0.0, 8.0), text)
            }
        }
        if (union != null) styles.setMarkerUnionFillBlock { count, data ->
            GLMapMarkerSetStyle(data, union(count.toInt()).coerceIn(0, last).toUInt())
            GLMapMarkerSetText(data, GLMapTextAlignment.GLMapTextAlignment_Undefined, count.toString(), CGPointMake(0.0, 0.0), text)
        }
        val native = GLMapMarkerLayer(markers = items, andStyles = styles, clusteringRadius = if (union == null) 0.0 else maxWidth / 2, drawOrder = drawOrder)
        val owned = Owned(listOf(native))
        var selected: Int? = null
        return object : MapMarkers() {
            override var hidden by owned::isHidden
            override val bounds = bounds
            override fun pick(tap: MapTap, distance: Double): Int? = memScoped {
                owned.checkActive()
                val point = alloc<GLMapPoint>()
                map.makeMapPointFromDisplayPoint(display(tap)).useContents { point.x = x; point.y = y }
                val hit = native.objectsAtMapView(map, nearPoint = point.ptr, distance = distance)?.firstOrNull() ?: return null
                // The layer may return a new wrapper for the same native object.
                items.indexOfFirst { it.isEqual(hit) }.takeIf { it >= 0 }
            }
            override fun select(index: Int?) {
                owned.checkActive()
                if (index == selected) return
                native.add(selected?.let { listOf(items[it]) }, remove = index?.let { listOf(items[it]) }, animated = true, completion = null)
                selected = index
            }
            override fun remove() = owned.removeOwned()
        }
    }
    override suspend fun addMarkers(geoJsonAsset: String, style: MarkerStyle, drawOrder: Int): MapMarkers {
        checkOpen()
        val path = assetPath(geoJsonAsset)
        val objects = withContext(Dispatchers.Default) { GLMapVectorObject.createVectorObjectsFromFile(path, null) }
            ?: throw SdkException(SdkError.Native, "Cannot parse $geoJsonAsset")
        checkOpen()
        @Suppress("UNCHECKED_CAST")
        return markers(objects.array() as List<GLMapVectorObject>, objects.bbox().geoBounds(), style, drawOrder)
    }
    override fun addMarkers(results: SearchResults, image: SvgImage, drawOrder: Int): MapMarkers {
        checkOpen()
        val bounds = results.places.takeIf { it.isNotEmpty() }?.let { list -> GeoBounds.of(list.map { it.point }) }
        return markers((results as IosSearchResults).objects, bounds, MarkerStyle(listOf(image), "{font-size:12;}"), drawOrder)
    }
    override fun addTrack(style: String, drawOrder: Int): MapTrack {
        checkOpen()
        val parsed = textStyle(style); val native = GLMapTrack(drawOrder = drawOrder)
        val owned = Owned(listOf(native))
        var data: GLMapTrackData? = null
        return object : MapTrack() {
            override var hidden by owned::isHidden
            override fun append(point: GeoPoint, color: Long) {
                owned.checkActive()
                val next = cValue<GLTrackPoint> { point.mapPoint().useContents { pt.x = x; pt.y = y }; color.mapColor().useContents { this@cValue.color.color = this.color } }
                data = data?.trackDataByAppendingPoint(next, startingNewSegment = false)
                    ?: memScoped { GLMapTrackData(points = next.getPointer(this), count = 1u) }
                native.setData(data, style = parsed, completion = null)
            }
            override fun setRoute(route: Route, color: Long) {
                owned.checkActive()
                data = (route as IosRoute).open().trackDataWithColor(color.mapColor()) ?: throw SdkException(SdkError.Native, "Route has no geometry")
                native.setData(data, style = parsed, completion = null)
            }
            override var progressColor = 0L
                set(value) { owned.checkActive(); field = value; native.setProgressColor(value.mapColor()) }
            override var progress = 0.0
                set(value) { owned.checkActive(); field = value; native.setProgressIndex(value) }
            override fun remove() = owned.removeOwned()
        }
    }
    override fun addLineArrow(style: String, head: SvgImage, drawOrder: Int): MapLineArrow {
        checkOpen()
        val native = GLMapLineArrow(drawOrder = drawOrder)
        native.setLineStyle(textStyle(style), headImage = image(head)); native.hidden = true
        val owned = Owned(listOf(native))
        return object : MapLineArrow() {
            override var hidden by owned::isHidden
            override fun setManeuver(maneuver: RouteManeuver) {
                owned.checkActive()
                val value = (maneuver as IosManeuver).native
                native.setLine(value.line(), index = value.lineStartIndex()); owned.isHidden = false
            }
            override fun remove() = owned.removeOwned()
        }
    }
    override fun addUserLocation(drawOrder: Int): UserLocationMarker {
        checkOpen()
        val native = GLMapUserLocation(drawOrder = drawOrder)
        // The helper consumes delegate calls; this manager is never started.
        val manager = CLLocationManager()
        native.addToMap(map)
        val owned = object : Owned(emptyList()) { override fun release() { if (lifetime.release()) native.removeFromMap(map) } }
        return object : UserLocationMarker() {
            override var hidden = false
                set(value) { owned.checkActive(); field = value; native.locationImage.hidden = value; native.movementImage.hidden = value; native.accuracyCircle.hidden = value }
            override fun update(fix: LocationFix) {
                owned.checkActive()
                val location = CLLocation(coordinate = CLLocationCoordinate2DMake(fix.point.latitude, fix.point.longitude), altitude = 0.0,
                    horizontalAccuracy = fix.accuracy, verticalAccuracy = -1.0, course = fix.bearing ?: -1.0, speed = -1.0, timestamp = NSDate())
                native.locationManager(manager, didUpdateLocations = listOf(location))
            }
            override fun remove() = owned.removeOwned()
        }
    }

    override fun releaseNative() {
        map.tapGestureBlock = null; map.longPressGestureBlock = null; map.visibleMapInsetsProvider = null
        drawables.toList().forEach { it.release() }; drawables.clear()
        layers.values.forEach { map.remove(it.native) }; layers.clear()
        marker?.let { map.remove(it) }; fixtureLayer?.let { map.remove(it) }; map.removeFromSuperview()
    }
}
