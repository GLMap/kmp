package globus.glmap
import globus.glmap.core.*

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import globus.glmap.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Composable actual fun GLMap(modifier: Modifier, onReady: (MapController) -> Unit, onTap: () -> Unit, fixture: Boolean) {
    val context = LocalContext.current
    val currentReady by rememberUpdatedState(onReady)
    val currentTap by rememberUpdatedState(onTap)
    AndroidView(modifier = modifier, factory = {
        val controller = AndroidMapController(context, fixture)
        controller.map.tag = controller
        controller.onTap = { currentTap() }
        controller.map.renderer.doWhenSurfaceCreated {
            Handler(Looper.getMainLooper()).post { if (!controller.disposed) currentReady(controller) }
        }
        controller.map
    }, onRelease = { (it.tag as AndroidMapController).dispose() })
}

private class AndroidMapController(private val context: Context, withFixture: Boolean) : MapController() {
    // Stage A runs without a key; the demo catalog initializes the SDK itself.
    private val initialized = !withFixture || GLMapManager.Initialize(context.applicationContext, "", null)
    val map = GLMapTextureView(context)
    private val renderer get() = map.renderer
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density.toDouble()
    private val marker = if (withFixture) GLMapImage(2) else null
    private val fixtureLayer = if (withFixture) GLMapVectorLayer(1) else null
    private class Layer(val native: GLMapVectorLayer) {
        var objects: GLMapVectorObjectList? = null
        fun close() { objects?.close(); native.close() }
    }
    private val layers = mutableMapOf<Int, Layer>()
    private var nextId = 0
    private val drawables = mutableSetOf<Owned>()
    private var raster: GLMapRasterTileSource? = null
    private var animation: GLMapAnimation? = null
    init {
        map.contentDescription = "GLMap canvas"
        if (fixtureLayer != null && marker != null) {
            setCamera(fixtureCamera)
            GLMapVectorObject.createFromGeoJSONOrThrow(fixture.getValue("track").toString()).use { objects ->
                checkNotNull(GLMapVectorCascadeStyle.createStyle(redStyle)).use { style ->
                    fixtureLayer.setVectorObjects(objects, style, null)
                }
            }
            renderer.add(fixtureLayer)
            val point = fixture.getValue("marker").jsonObject
            val size = (24 * context.resources.displayMetrics.density).toInt()
            val bitmap = Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            Canvas(bitmap).apply {
                paint.color = Color.WHITE; drawCircle(size/2f,size/2f,size/2f,paint)
                paint.color = Color.rgb(38,80,214); drawCircle(size/2f,size/2f,size*.36f,paint)
            }
            marker.setBitmap(bitmap); marker.setOffset(size/2,size/2)
            marker.position = MapPoint.CreateFromGeoCoordinates(point.number("latitude"),point.number("longitude"))
            renderer.add(marker)
        } else {
            val inset = (16 * density).toInt()
            renderer.setVisibleMapInsets(inset, inset, inset, inset)
        }
        val detector = GestureDetector(context,object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!disposed) { onTap?.invoke(); onMapTap?.invoke(tap(e)) }
                return true
            }
            override fun onLongPress(e: MotionEvent) { if (!disposed) onMapLongPress?.invoke(tap(e)) }
        })
        map.setOnTouchListener { _, event -> detector.onTouchEvent(event); false }
    }
    private fun tap(e: MotionEvent) = MapTap(renderer.convertDisplayToInternal(e.x.toDouble(), e.y.toDouble()).geoPoint(), e.x / density, e.y / density)
    private fun internal(tap: MapTap) = renderer.convertDisplayToInternal(tap.x * density, tap.y * density)

    override fun setNativeCamera(camera: Camera) {
        renderer.setMapGeoCenterLatLon(camera.latitude,camera.longitude)
        renderer.mapZoom = camera.zoom
        renderer.mapAngle = camera.angle.toFloat(); renderer.mapPitch = camera.pitch.toFloat()
    }
    override fun captureState(): Deferred<MapState> = request { done ->
        check(map.isAvailable) { "map_unavailable" }
        renderer.captureState { state ->
            val value = state.use {
                val c = it.getGeoCenter(MapGeoPoint()); val o = it.getOrigin(PointF())
                MapState(c.lat,c.lon,it.zoom,it.scale,it.angle.toDouble(),it.pitch.toDouble(),o.x.toDouble(),o.y.toDouble())
            }
            main.post { done(value) }
        }
    }
    override fun createNativeLayer(drawOrder: Int): Int {
        val entry = Layer(GLMapVectorLayer(drawOrder)); val id = ++nextId
        layers[id] = entry; renderer.add(entry.native); return id
    }
    private fun parse(style: String) = GLMapStyleParser().use { parser ->
        require(parser.parseNextString(style)) { "invalid_style" }; checkNotNull(parser.finish())
    }
    private fun submit(layer: Layer, objects: GLMapVectorObjectList, parsed: GLMapVectorCascadeStyle): Deferred<UpdateResult> = request { done ->
        layer.native.setVectorObjects(objects,parsed) { result ->
            val value = when(result) {
                GLMapVectorLayer.UpdateResult.Ready -> UpdateResult.Ready
                GLMapVectorLayer.UpdateResult.Superseded -> UpdateResult.Superseded
                GLMapVectorLayer.UpdateResult.Cancelled -> UpdateResult.Cancelled
                else -> UpdateResult.Failed
            }
            main.post { done(value) }
        }
    }
    override fun updateNative(id: Int, lonLat: DoubleArray?, json: String?, style: String): Deferred<UpdateResult> {
        val layer = checkNotNull(layers[id]) { "layer_removed" }
        parse(style).use { parsed ->
            val replacing = lonLat != null || json != null
            val objects = if (json != null) GLMapVectorObject.createFromGeoJSONOrThrow(json)
                else if (lonLat != null) line(lonLat) else checkNotNull(layer.objects) { "missing_geometry" }
            val reply = submit(layer, objects, parsed)
            if (replacing) { layer.objects?.close(); layer.objects = objects }
            return reply
        }
    }
    private fun line(values: DoubleArray): GLMapVectorObjectList {
        val objects = GLMapVectorObjectList()
        try {
            if (values.isNotEmpty()) GeometryBuilder().use { builder ->
                builder.addLineLonLat(values)
                checkNotNull(builder.build()).use { objects.insertObject(0,it) }
            }
            return objects
        } catch (error: Exception) { objects.close(); throw error }
    }
    override fun updateNativePolygon(id: Int, rings: List<DoubleArray>, style: String): Deferred<UpdateResult> {
        val layer = checkNotNull(layers[id]) { "layer_removed" }
        parse(style).use { parsed ->
            val objects = GLMapVectorObjectList()
            try {
                GeometryBuilder().use { builder ->
                    builder.beginPolygon(); rings.forEach(builder::addLineLonLat)
                    checkNotNull(builder.build()).use { objects.insertObject(0,it) }
                }
            } catch (error: Exception) { objects.close(); throw error }
            val reply = submit(layer, objects, parsed)
            layer.objects?.close(); layer.objects = objects
            return reply
        }
    }
    override fun removeNativeLayer(id: Int) {
        val entry = checkNotNull(layers.remove(id)) { "layer_removed" }
        renderer.remove(entry.native); entry.close()
    }
    override fun nativeGeometryJson(id: Int): String? {
        val objects = checkNotNull(layers[id]) { "layer_removed" }.objects ?: return null
        return if (objects.size() == 0L) null else objects.get(0).use { it.asGeoJSON() }
    }
    override fun nativeLayerBounds(id: Int): GeoBounds? {
        val objects = checkNotNull(layers[id]) { "layer_removed" }.objects ?: return null
        return if (objects.size() == 0L) null else objects.bBox.geoBounds()
    }
    override fun nativePickFeature(id: Int, tap: MapTap, tolerance: Double): String? {
        val objects = checkNotNull(layers[id]) { "layer_removed" }.objects ?: return null
        if (objects.size() == 0L) return null
        // The JNI end index is exclusive.
        return renderer.state?.use { state ->
            val point = state.convertDisplayToInternal(tap.x * density, tap.y * density, MapPoint())
            state.findNearPoint(objects, 0, objects.size(), point, tolerance)?.use { it.asGeoJSON() }
        }
    }

    override fun animate(duration: Double?, fly: Boolean, linear: Boolean, changes: () -> Unit): MapAnimation {
        checkOpen()
        val native = renderer.animate {
            if (duration != null) it.setDuration(duration)
            if (linear) it.setTransition(GLMapAnimation.Linear)
            if (fly) it.flyToMode = GLMapAnimation.FlyToMode.Enabled
            animation = it
            try { changes() } finally { animation = null }
        }
        return object : MapAnimation() { override fun cancel() { native.cancel(false) } }
    }
    override fun moveNativeCamera(center: GeoPoint?, zoom: Double?, angle: Double?, pitch: Double?) {
        center?.let { renderer.mapCenter = it.mapPoint() }
        zoom?.let { renderer.mapZoom = it }
        angle?.let { renderer.mapAngle = it.toFloat() }
        pitch?.let { renderer.mapPitch = it.toFloat() }
    }
    override fun setOrigin(x: Double, y: Double) { checkOpen(); renderer.setMapOrigin(x.toFloat(), y.toFloat()) }
    override fun fitBounds(bounds: GeoBounds, zoomDelta: Double) {
        checkOpen()
        val box = bounds.bbox(); val fitted = renderer.mapZoomForBBox(box)
        renderer.mapZoom = if (fitted.isFinite()) fitted + zoomDelta else 15.0
        renderer.mapCenter = box.center()
    }
    override fun toDisplay(point: GeoPoint): ScreenPoint {
        checkOpen()
        return renderer.convertInternalToDisplay(point.mapPoint()).let { ScreenPoint(it.x / density, it.y / density) }
    }
    override fun setStyleOptions(options: Map<String, String>) {
        checkOpen()
        GLMapStyleParser(context.assets, "DefaultStyle.bundle").use { parser ->
            parser.setOptions(options, true)
            checkNotNull(parser.parseFromResources()) { "invalid_style" }.use { renderer.setStyle(it) }
        }
        renderer.reloadTiles()
    }
    override fun setRasterTiles(urlTemplates: List<String>?, cacheName: String, attribution: String) {
        checkOpen()
        val previous = raster
        if (urlTemplates == null) { renderer.setBase(GLMapVectorTileSource()); raster = null }
        else {
            require(urlTemplates.isNotEmpty() && urlTemplates.all { it.startsWith("https://") }) { SdkError.InvalidArgument }
            val storage = GLMapFileStorage(context.filesDir).findStorage("RasterCache", true)?.findFile(cacheName, true)
            val source = object : GLMapRasterTileSource(storage) {
                override fun urlForTilePos(x: Int, y: Int, z: Int) = urlTemplates[Math.floorMod(x + y, urlTemplates.size)]
                    .replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
            }
            source.setValidZoomMask((1 shl 20) - 1); source.setAttributionText(attribution)
            renderer.setBase(source); raster = source
        }
        previous?.close()
    }
    override fun reloadTiles() { checkOpen(); renderer.reloadTiles() }
    override fun enableClipping(bounds: GeoBounds, minLevel: Double, maxLevel: Double) {
        checkOpen(); renderer.enableClipping(bounds.bbox(), minLevel.toFloat(), maxLevel.toFloat())
    }
    override var altitudeScale: Float
        get() = renderer.altitudeScale
        set(value) { checkOpen(); renderer.altitudeScale = value }
    override var drawHillshades: Boolean
        get() = renderer.drawHillshades
        set(value) { checkOpen(); renderer.drawHillshades = value }
    override var drawElevationLines: Boolean
        get() = renderer.drawElevationLines
        set(value) { checkOpen(); renderer.drawElevationLines = value }
    override var drawSlopes: Boolean
        get() = renderer.drawSlopes
        set(value) { checkOpen(); renderer.drawSlopes = value }
    override fun queryState():MapQueryState? {
        checkOpen();return renderer.state?.let { MapQueryState(it,density) }
    }

    private fun bitmap(image: SvgImage): Bitmap {
        val scale = renderer.screenScale * image.scale
        val transform = image.tint?.let { SVGRender.transform(scale, it.toInt()) } ?: SVGRender.transform(scale)
        return SVGRender.render(context.assets, image.asset, transform) ?: throw SdkException(SdkError.AssetUnavailable, image.asset)
    }
    /** Native objects owned by one common drawable. */
    private abstract inner class Owned(val objects: List<GLMapDrawObject>, val keep: MutableList<GLNativeObject> = mutableListOf()) {
        init { drawables.add(this); objects.forEach { renderer.add(it) } }
        val lifetime = DrawableLifetime { disposed }
        fun checkActive() = lifetime.checkOpen()
        var isHidden = false
            set(value) { checkActive(); field = value; objects.forEach { it.isHidden = value } }
        fun removeOwned() { if (drawables.remove(this)) release() }
        open fun release() { if (!lifetime.release()) return; objects.forEach { renderer.remove(it); it.close() }; keep.forEach { it.close() }; keep.clear() }
    }
    override fun addImage(image: SvgImage, drawOrder: Int, position: GeoPoint, centered: Boolean): MapImage {
        checkOpen()
        val bitmap = bitmap(image)
        val native = GLMapImage(drawOrder).apply {
            setBitmap(bitmap); setOffset(bitmap.width / 2, if (centered) bitmap.height / 2 else 0); this.position = position.mapPoint()
        }
        val owned = object : Owned(listOf(native)) {}
        return object : MapImage() {
            override var hidden by owned::isHidden
            override var position = position
                set(value) { owned.checkActive(); field = value; animation?.setPosition(native, value.mapPoint()) ?: run { native.position = value.mapPoint() } }
            override var scale = 1.0
                set(value) { owned.checkActive(); field = value; animation?.setScale(native, value) ?: run { native.scale = value } }
            override fun remove() = owned.removeOwned()
        }
    }
    override fun addImageGroup(variants: List<SvgImage>, drawOrder: Int): MapImageGroup {
        checkOpen(); require(variants.isNotEmpty()) { SdkError.InvalidArgument }
        val bitmaps = variants.map(::bitmap)
        val lock = java.util.concurrent.locks.ReentrantLock()
        var pins = emptyList<Pair<MapPoint, Int>>()
        val native = GLMapImageGroup(object : GLMapImageGroupCallback {
            override fun getImageVariantsCount() = bitmaps.size
            override fun getImageVariantBitmap(i: Int) = bitmaps[i]
            override fun getImageVariantOffset(i: Int) = MapPoint(bitmaps[i].width / 2.0, 0.0)
            override fun getImagesCount() = pins.size
            override fun getImageIndex(i: Int) = pins[i].second
            override fun getImagePos(i: Int) = pins[i].first
            override fun updateStarted() = lock.lock()
            override fun updateFinished() = lock.unlock()
        }, drawOrder)
        val owned = object : Owned(listOf(native)) {}
        return object : MapImageGroup() {
            override var hidden by owned::isHidden
            override fun setPins(value: List<Pin>) {
                owned.checkActive()
                require(value.all { it.variant in bitmaps.indices }) { SdkError.InvalidArgument }
                val next = value.map { it.point.mapPoint() to it.variant }
                lock.lock(); try { pins = next } finally { lock.unlock() }
                native.setNeedsUpdate(false)
            }
            override fun remove() = owned.removeOwned()
        }
    }
    override fun addBalloon(textStyle: String, drawOrder: Int): MapBalloon {
        checkOpen()
        fun dp(value: Int) = (value * density).toInt()
        val style = GLMapVectorStyle.createStyle(textStyle) ?: throw SdkException(SdkError.InvalidArgument, "text style")
        val background = Bitmap.createBitmap(dp(180), dp(64), Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRoundRect(0f, 0f, it.width.toFloat(), it.height.toFloat(), dp(12).toFloat(), dp(12).toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        val native = GLMapBalloon(drawOrder).apply { setBackgroundBitmap(background, Rect(dp(20), dp(20), dp(20), dp(20))); isHidden = true }
        val owned = object : Owned(listOf(native), mutableListOf(style)) {}
        return object : MapBalloon() {
            override var hidden by owned::isHidden
            override fun show(point: GeoPoint, text: String) {
                owned.checkActive()
                native.setText(text, style, Rect(dp(12), dp(8), dp(12), dp(8)), null)
                native.position = point.mapPoint(); owned.isHidden = false
            }
            override fun remove() = owned.removeOwned()
        }
    }
    private fun markers(items: Array<GLMapVectorObject>, bounds: GeoBounds?, style: MarkerStyle, drawOrder: Int,
                        keep: MutableList<GLNativeObject>): MapMarkers {
        val styles = GLMapMarkerStyleCollection(); keep.add(styles)
        var maxWidth = 0
        style.images.forEachIndexed { index, image -> bitmap(image).let { maxWidth = maxOf(maxWidth, it.width); styles.addStyle(GLMapMarkerImage("style$index", it)) } }
        val text = GLMapVectorStyle.createStyle(style.textStyle) ?: throw SdkException(SdkError.InvalidArgument, "text style")
        keep.add(text)
        styles.setDataCallback(object : GLMapMarkerStyleCollectionDataCallback() {
            override fun getLocation(marker: Any) = (marker as GLMapVectorObject).point()
            override fun fillData(marker: Any, data: Long) {
                GLMapMarkerStyleCollection.setMarkerStyle(data, 0)
                val key = style.nameKey ?: return
                (marker as GLMapVectorObject).valueForKey(key)?.use { value ->
                    value.string?.let { GLMapMarkerStyleCollection.setMarkerText(data, it, GLMapTextAlignment.Undefined, Point(0, 8), text) }
                }
            }
            override fun fillUnionData(count: Int, data: Long) {
                val union = style.unionStyle ?: return
                GLMapMarkerStyleCollection.setMarkerStyle(data, union(count).coerceIn(0, style.images.size - 1))
                GLMapMarkerStyleCollection.setMarkerText(data, count.toString(), GLMapTextAlignment.Undefined, Point(0, 0), text)
            }
        })
        val clustering = if (style.unionStyle == null) 0.0 else maxWidth / renderer.screenScale / 2.0
        val native = GLMapMarkerLayer(items, styles, clustering, drawOrder)
        val owned = object : Owned(listOf(native), keep) {}
        var selected: Int? = null
        return object : MapMarkers() {
            override var hidden by owned::isHidden
            override val bounds = bounds
            override fun pick(tap: MapTap, distance: Double): Int? {
                owned.checkActive()
                val hits = native.objectsNearPoint(renderer, internal(tap), distance) ?: return null
                return items.indexOfFirst(hits::contains).takeIf { it >= 0 }
            }
            override fun select(index: Int?) {
                owned.checkActive()
                if (index == selected) return
                native.modify(selected?.let { arrayOf<Any>(items[it]) }, index?.let { setOf<Any>(items[it]) }, true, null)
                selected = index
            }
            override fun remove() = owned.removeOwned()
        }
    }
    override suspend fun addMarkers(geoJsonAsset: String, style: MarkerStyle, drawOrder: Int): MapMarkers {
        checkOpen()
        val objects = withContext(Dispatchers.IO) {
            try { context.assets.open(geoJsonAsset).use(GLMapVectorObject::createFromGeoJSONStreamOrThrow) }
            catch (error: java.io.IOException) { throw SdkException(SdkError.AssetUnavailable, geoJsonAsset) }
        }
        val items = objects.toArray(); val bounds = objects.bBox.geoBounds(); objects.close()
        val keep = items.toMutableList<GLNativeObject>()
        try { checkOpen(); return markers(items, bounds, style, drawOrder, keep) }
        catch (error: Exception) { keep.forEach { it.close() }; throw error }
    }
    override fun addMarkers(results: FeatureCollection, image: SvgImage, drawOrder: Int): MapMarkers {
        checkOpen()
        val objects = results.retainVectorObjects()
        val items = objects.native
        val bounds = results.features.takeIf { it.isNotEmpty() }?.let { list -> GeoBounds.of(list.map { it.point }) }
        // Each marker layer owns the retained Java wrappers.
        return markers(items, bounds, MarkerStyle(listOf(image), "{font-size:12;}"), drawOrder, items.toMutableList<GLNativeObject>())
    }
    override fun addTrack(style: String, drawOrder: Int): MapTrack {
        checkOpen()
        val parsed = GLMapVectorStyle.createStyle(style) ?: throw SdkException(SdkError.InvalidArgument, "track style")
        val native = GLMapTrack(drawOrder)
        val owned = object : Owned(listOf(native), mutableListOf(parsed)) {}
        var data: GLMapTrackData? = null
        var count = 0
        fun replace(next: GLMapTrackData) { native.setData(next, parsed, null); data?.let { it.close(); owned.keep.remove(it) }; data = next; owned.keep.add(next) }
        return object : MapTrack() {
            override var hidden by owned::isHidden
            override fun append(point: GeoPoint, color: Long) {
                owned.checkActive()
                val argb = color.toInt()
                replace(data?.copyTrackAndAddGeoPoint(point.latitude, point.longitude, argb, count > 0 && count % 100 == 0)
                    ?: GLMapTrackData({ _, native -> GLMapTrackData.setPointDataGeo(native, point.latitude, point.longitude, argb) }, 1))
                count++
            }
            override fun setRoute(route: TrackSource, color: Long) {
                owned.checkActive(); count = 0; replace(route.trackData(color).native) }
            override var progressColor = 0L
                set(value) { owned.checkActive(); field = value; native.setProgressColor(value.toInt()) }
            override var progress = 0.0
                set(value) { owned.checkActive(); field = value; native.setProgressIndex(value) }
            override fun remove() = owned.removeOwned()
        }
    }
    override fun addLineArrow(style: String, head: SvgImage, drawOrder: Int): MapLineArrow {
        checkOpen()
        val parsed = GLMapVectorStyle.createStyle(style) ?: throw SdkException(SdkError.InvalidArgument, "arrow style")
        val native = GLMapLineArrow(drawOrder).apply { setLineStyle(parsed, bitmap(head)); isHidden = true }
        val owned = object : Owned(listOf(native), mutableListOf(parsed)) {}
        return object : MapLineArrow() {
            override var hidden by owned::isHidden
            override fun setManeuver(maneuver: LineSource) {
                owned.checkActive()
                val value = maneuver.lineData()
                try { native.setLine(value.native,value.index);owned.isHidden=false } finally {value.close()}
            }
            override fun remove() = owned.removeOwned()
        }
    }
    override fun addUserLocation(drawOrder: Int): UserLocationMarker {
        checkOpen()
        val bitmap = bitmap(SvgImage("circle_new.svg"))
        val image = GLMapImage(drawOrder).apply { setBitmap(bitmap); setOffset(bitmap.width / 2, bitmap.height / 2); isHidden = true }
        val points = Array(64) { index -> val angle = 2 * Math.PI * index / 64; MapPoint(Math.sin(angle) * 2048, Math.cos(angle) * 2048) }
        val circleStyle = checkNotNull(GLMapVectorCascadeStyle.createStyle("area{width:1pt;fill-color:#3D99FA26;color:#3D99FA66;}"))
        val circle = GLMapVectorLayer(drawOrder - 1).apply {
            setTransformMode(GLMapDrawable.TransformMode.Custom)
            setVectorObject(GLMapVectorObject.createPolygon(arrayOf(points)), circleStyle, null); isHidden = true
        }
        val owned = object : Owned(listOf(circle, image), mutableListOf(circleStyle)) {}
        var first = true
        return object : UserLocationMarker() {
            override var hidden by owned::isHidden
            override fun update(fix: LocationFix) {
                owned.checkActive()
                val position = fix.point.mapPoint()
                val scale = renderer.convertMetersToInternal(fix.accuracy) / 2048.0
                val active = animation
                if (first || active == null) { image.position = position; circle.position = position; circle.scale = scale }
                else { active.setPosition(image, position); active.setPosition(circle, position); active.setScale(circle, scale) }
                if (first) { first = false; owned.isHidden = false }
            }
            override fun remove() = owned.removeOwned()
        }
    }

    override fun releaseNative() {
        map.setOnTouchListener(null)
        drawables.toList().forEach { it.release() }; drawables.clear()
        layers.values.forEach { renderer.remove(it.native); it.close() }; layers.clear()
        marker?.let { renderer.remove(it) }; fixtureLayer?.let { renderer.remove(it) }
        map.dispose(); marker?.close(); fixtureLayer?.close()
        raster?.close(); raster = null
    }
}
