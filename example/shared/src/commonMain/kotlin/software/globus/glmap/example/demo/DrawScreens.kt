package software.globus.glmap.example.demo
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*


import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin
import kotlinx.coroutines.flow.Flow
import software.globus.glmap.example.*

private const val red = 0xFFE63C3C
private val paris = GeoPoint(48.8566, 2.3522)
private fun near(map: MapController, point: GeoPoint, tap: MapTap, radius: Double = 40.0) =
    map.toDisplay(point).let { (it.x - tap.x) * (it.x - tap.x) + (it.y - tap.y) * (it.y - tap.y) < radius * radius }

@Composable fun ImageDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    var status by remember { mutableStateOf("Tap map to move the image") }
    DemoScaffold(sdk, "Image", onBack, status) { controller ->
        try {
            controller.setCamera(Camera(paris.latitude, paris.longitude, 7.0))
            val image = controller.addImage(SvgImage("pin.svg", 1.6, red), 3, paris)
            controller.onMapTap = { tap -> controller.animate(duration = 0.3) { image.position = tap.point } }
        } catch (error: Exception) { status = describe(error) }
    }
}

@Composable fun ImageGroupDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    var status by remember { mutableStateOf("Long press to add, tap to remove") }
    val pins = remember { mutableStateListOf<Pin>() }
    var added by remember { mutableStateOf(0) }
    DemoScaffold(sdk, "Image Group", onBack, "$status · ${pins.size} pins") { controller ->
        try {
            controller.setCamera(Camera(paris.latitude, paris.longitude, 13.0))
            val group = controller.addImageGroup(listOf(red, 0xFF3C78E6, 0xFF28B45A).map { SvgImage("pin.svg", 1.6, it) }, 3)
            listOf(48.8584 to 2.2945, 48.8606 to 2.3376, 48.8530 to 2.3499, 48.8867 to 2.3431, 48.8738 to 2.2950, 48.8462 to 2.3464,
                48.8600 to 2.3266, 48.8619 to 2.2870).forEach { (lat, lon) -> pins.add(Pin(GeoPoint(lat, lon), added++ % 3)) }
            group.setPins(pins)
            controller.onMapLongPress = { tap -> pins.add(Pin(tap.point, added++ % 3)); group.setPins(pins) }
            controller.onMapTap = { tap -> pins.firstOrNull { near(controller, it.point, tap, 20.0) }?.let { pins.remove(it); group.setPins(pins) } }
        } catch (error: Exception) { status = describe(error) }
    }
}

@Composable fun MarkerClusteringDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    val tints = listOf(0xFF2100FF, 0xFF44C3FF, 0xFF3FEDC6, 0xFF0FE424, 0xFFA8EE19, 0xFFD6EA19, 0xFFDFB413, 0xFFFF0000)
    var map by remember { mutableStateOf<MapController?>(null) }
    var status by remember { mutableStateOf("Loading markers…") }
    LaunchedEffect(map) {
        val controller = map ?: return@LaunchedEffect
        try {
            val style = MarkerStyle(tints.mapIndexed { index, tint -> SvgImage("cluster.svg", 0.2 + 0.1 * index, tint) },
                "{text-color:black;font-size:12;font-stroke-width:1pt;font-stroke-color:#FFFFFFEE;}", nameKey = "name",
                unionStyle = { count -> log2(count.toDouble()).toInt() })
            val markers = controller.addMarkers("cluster_data.json", style, 2)
            markers.bounds?.let(controller::fitBounds); status = ""
        } catch (error: IllegalStateException) { status = describe(error) } catch (error: SdkException) { status = describe(error) }
    }
    DemoScaffold(sdk, "Markers & Clustering", onBack, status, online = false) { map = it }
}

@Composable fun BalloonDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    val landmarks = listOf("Eiffel Tower" to GeoPoint(48.8584, 2.2945), "Colosseum" to GeoPoint(41.8902, 12.4922),
        "Big Ben" to GeoPoint(51.5007, -0.1246), "Brandenburg Gate" to GeoPoint(52.5163, 13.3777))
    var status by remember { mutableStateOf("Tap a pin to see balloon") }
    DemoScaffold(sdk, "Balloon", onBack, status) { controller ->
        try {
            controller.setCamera(Camera(48.0, 8.0, 5.0))
            landmarks.forEach { controller.addImage(SvgImage("pin.svg", 1.6, red), 3, it.second) }
            val balloon = controller.addBalloon("{text-color:#2C3E50;font-size:16;font-stroke-width:0;}")
            controller.onMapTap = { tap ->
                val hit = landmarks.firstOrNull { near(controller, it.second, tap) }
                if (hit == null) balloon.hidden = true else { balloon.show(hit.second, hit.first); status = hit.first }
            }
        } catch (error: Exception) { status = describe(error) }
    }
}

@Composable fun TrackArrowsDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    var map by remember { mutableStateOf<MapController?>(null) }
    var title by remember { mutableStateOf("Building route...") }
    var status by remember { mutableStateOf("") }
    var route by remember { mutableStateOf<Route?>(null) }
    DisposableEffect(Unit) { onDispose { route?.close() } }
    LaunchedEffect(map) {
        val controller = map ?: return@LaunchedEffect
        try {
            val arrow = controller.addLineArrow("{casing-width:2pt; casing-color:#4285F4FF; width:14pt; color:white; linecap:round;}",
                SvgImage("route-maneuver-head.svg", 1.0, 0xFF4285F4), 6)
            val built = sdk.route(RouteQuery(GeoPoint(40.633, 14.502), GeoPoint(40.650, 14.720)))
            route = built
            controller.addTrack("{width:14pt; fill-image:\"track-arrow.svg\";}", 5).setRoute(built, 0xDC4285F4)
            val maneuver = built.maneuvers.getOrNull(1)?.takeIf { built.maneuvers.size > 2 } ?: return@LaunchedEffect
            arrow.setManeuver(maneuver); title = "Track Arrows"
            controller.animate(duration = 1.5, fly = true) { controller.moveCamera(center = maneuver.start, zoom = 17.0) }
        } catch (error: IllegalStateException) { status = describe(error) }
        catch (error: SdkException) { title = "Route failed — check network"; status = describe(error) }
    }
    DemoScaffold(sdk, title, onBack, status) { controller -> controller.setCamera(Camera(40.640, 14.610, 12.0)); map = controller }
}

// Short walk through Bergen and San Sebastián for checks without GPS.
internal val bergenWalk = doubleArrayOf(5.3221, 60.3913, 5.3228, 60.3916, 5.3236, 60.3919, 5.3245, 60.3921, 5.3254, 60.3924, 5.3262, 60.3928)
internal val donostiaWalk = doubleArrayOf(-1.9812, 43.3183, -1.9805, 43.3186, -1.9797, 43.3189, -1.9789, 43.3193, -1.9780, 43.3196, -1.9772, 43.3199)

/** GPS starts with the screen as in the native demos; Replay swaps in deterministic sample fixes. */
@Composable internal fun LocationFeed(sample: DoubleArray, onStatus: (String) -> Unit, onFix: (LocationFix) -> Unit): @Composable RowScope.() -> Unit {
    val gps = rememberLocationSource()
    var replay by remember { mutableStateOf(0) }
    val deliver by rememberUpdatedState(onFix)
    val report by rememberUpdatedState(onStatus)
    LaunchedEffect(replay) {
        val source: Flow<LocationFix> = if (replay == 0) gps.fixes() else replayFixes(sample)
        try { source.collect { deliver(it) }; if (replay > 0) report("Replay finished") }
        catch (error: SdkException) { report(describe(error)) }
    }
    return {
        Button(onClick = { replay++; report("Replaying sample coordinates") }) { Text("Replay sample") }
        TextButton(enabled = replay != 0, onClick = { replay = 0; report("Waiting for GPS…") }) { Text("Use GPS") }
    }
}

@Composable fun UserLocationDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    var map by remember { mutableStateOf<MapController?>(null) }
    var marker by remember { mutableStateOf<UserLocationMarker?>(null) }
    var status by remember { mutableStateOf("Waiting for GPS…") }
    var first by remember { mutableStateOf(true) }
    var moving by remember { mutableStateOf<MapAnimation?>(null) }
    val feed = LocationFeed(bergenWalk, { status = it }) { fix ->
        val controller = map?.takeUnless { it.disposed } ?: return@LocationFeed
        val target = marker ?: return@LocationFeed
        if (first) { first = false; target.update(fix); controller.moveCamera(center = fix.point) }
        else { moving?.cancel(); moving = controller.animate(duration = 1.0, linear = true) { target.update(fix) } }
        status = coordinates(fix.point)
    }
    DemoScaffold(sdk, "User Location", onBack, status, controls = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = feed) }) { controller ->
        try { controller.setCamera(Camera(60.3913, 5.3221, 14.0)); marker = controller.addUserLocation(100); map = controller }
        catch (error: Exception) { status = describe(error) }
    }
}

@Composable fun GpsTrackDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    var map by remember { mutableStateOf<MapController?>(null) }
    var marker by remember { mutableStateOf<UserLocationMarker?>(null) }
    var track by remember { mutableStateOf<MapTrack?>(null) }
    var status by remember { mutableStateOf("Waiting for GPS…") }
    var points by remember { mutableStateOf(0) }
    var moving by remember { mutableStateOf<MapAnimation?>(null) }
    val feed = LocationFeed(donostiaWalk, { status = it }) { fix ->
        val controller = map?.takeUnless { it.disposed } ?: return@LocationFeed
        track?.append(fix.point, 0xFFFFFF00)
        fun follow() { marker?.update(fix); controller.moveCamera(center = fix.point, angle = fix.bearing?.let { -it }) }
        if (points++ == 0) { follow(); controller.moveCamera(zoom = 15.0) }
        else { moving?.cancel(); moving = controller.animate(duration = 1.0, linear = true, changes = ::follow) }
        status = "$points points · ${coordinates(fix.point)}"
    }
    DemoScaffold(sdk, "GPS Track", onBack, status, controls = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = feed) }) { controller ->
        try {
            controller.setCamera(Camera(43.3183, -1.9812, 15.0))
            track = controller.addTrack("{width:5pt;}", 2); marker = controller.addUserLocation(100); map = controller
        } catch (error: Exception) { status = describe(error) }
    }
}

@Composable fun LinesPolygonsDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    fun ring(center: GeoPoint, count: Int, radius: (Int) -> Double, step: Double, phase: Double) = DoubleArray((count + 1) * 2).also { out ->
        for (i in 0..count) {
            val angle = i * step + phase
            out[2 * i] = center.longitude + radius(i) * cos(angle) / cos(center.latitude * PI / 180)
            out[2 * i + 1] = center.latitude + radius(i) * sin(angle)
        }
    }
    DemoScaffold(sdk, "Lines & Polygons", onBack) { controller ->
        controller.setCamera(Camera(paris.latitude, paris.longitude, 5.0))
        listOf(doubleArrayOf(-0.1275, 51.5072, 2.3522, 48.8566, 6.1432, 46.2044, 12.4829, 41.8933) to "line{width: 4pt; color:#E74C3C;}",
            doubleArrayOf(13.4102, 52.5037, 14.4378, 50.0755, 16.3738, 48.2082, 19.0402, 47.4979) to "line{width: 4pt; color:#3498DB;}",
            doubleArrayOf(4.9021, 52.3690, 4.3458, 50.8263, 6.1296, 49.6072, 2.3522, 48.8566) to "line{width: 3pt; color:#2ECC71; linecap:round;}")
            .forEach { (line, style) -> controller.replaceLine(controller.createVectorLayer(3), line, style) }
        controller.replacePolygon(controller.createVectorLayer(2), listOf(ring(paris, 10, { if (it % 2 == 0) 3.0 else 1.2 }, PI / 5, -PI / 2)),
            "area{fill-color:#F39C1230; width:2pt; color:#F39C12;}")
        val berlin = GeoPoint(52.5037, 13.4102)
        controller.replacePolygon(controller.createVectorLayer(2), listOf(ring(berlin, 6, { 1.5 }, PI / 3, 0.0), ring(berlin, 6, { 0.6 }, PI / 3, 0.0)),
            "area{fill-color:#9B59B630; width:2pt; color:#9B59B6;}")
    }
}

@Composable fun GeoJsonDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    var map by remember { mutableStateOf<MapController?>(null) }
    var status by remember { mutableStateOf("Loading uk_postcodes.geojson…") }
    var tapped by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(map) {
        val controller = map ?: return@LaunchedEffect
        try {
            val layer = controller.createVectorLayer()
            controller.replaceGeoJson(layer, sdk.readAsset("uk_postcodes.geojson"), "area{fill-color:#3498DB40; width:1.5pt; color:#2C3E50;}")
            controller.layerBounds(layer)?.let(controller::fitBounds)
            status = "Tap on any UK region"
            controller.onMapTap = { tap -> controller.pickFeature(layer, tap)?.let { tapped = it.take(400) } }
        } catch (error: IllegalStateException) { status = describe(error) } catch (error: IllegalArgumentException) { status = describe(error) }
        catch (error: SdkException) { status = describe(error) }
    }
    DemoScaffold(sdk, "GeoJSON", onBack, status, online = false) { map = it }
    tapped?.let { text ->
        AlertDialog(onDismissRequest = { tapped = null }, text = { Text("Tapped: $text") }, confirmButton = { TextButton(onClick = { tapped = null }) { Text("OK") } })
    }
}
