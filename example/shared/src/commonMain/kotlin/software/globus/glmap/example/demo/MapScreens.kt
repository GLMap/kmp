package software.globus.glmap.example.demo
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*


import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import software.globus.glmap.example.*

private val osmMirrors = listOf("a", "b", "c").map { "https://$it.tile.openstreetmap.org/{z}/{x}/{y}.png" }
internal fun coordinates(point: GeoPoint) = "${(point.latitude * 10000).roundToInt() / 10000.0}, ${(point.longitude * 10000).roundToInt() / 10000.0}"

@Composable fun OnlineMapDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    var map by remember { mutableStateOf<MapController?>(null) }
    var raster by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Tap the map to read coordinates") }
    DemoScaffold(sdk, "Online Map", onBack, status, controls = {
        Button(enabled = map != null, onClick = {
            val controller = map ?: return@Button
            raster = !raster
            controller.setRasterTiles(if (raster) osmMirrors else null, attribution = "© OpenStreetMap contributors")
            controller.drawElevationLines = !raster; controller.drawHillshades = !raster
        }) { Text(if (raster) "GLMap Vector" else "OSM Raster") }
    }) { controller ->
        map = controller
        try {
            controller.setCamera(Camera(46.5369, 12.1356, 13.0))
            controller.drawElevationLines = true; controller.drawHillshades = true
            controller.setStyleOptions(mapOf("Style" to "Outdoor", "SubStyle" to "Ski"))
            val balloon = controller.addBalloon("{text-color:#2C3E50;font-size:14;font-stroke-width:0;}")
            controller.onMapTap = { tap -> coordinates(tap.point).let { balloon.show(tap.point, it); status = it } }
        } catch (error: Exception) { status = describe(error) }
    }
}

@Composable fun DarkThemeDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    var map by remember { mutableStateOf<MapController?>(null) }
    var dark by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    fun apply(controller: MapController) {
        try { controller.setStyleOptions(if (dark) mapOf("Theme" to "Dark") else emptyMap()) } catch (error: Exception) { status = describe(error) }
    }
    DemoScaffold(sdk, "Dark Theme", onBack, status, controls = {
        Button(enabled = map != null, onClick = { dark = !dark; map?.let(::apply) }) { Text(if (dark) "Light" else "Dark") }
    }) { controller -> map = controller; controller.setCamera(Camera(45.4371, 12.3326, 14.0)); apply(controller) }
}

@Composable fun TerrainDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    val bounds = GeoBounds(45.85, 6.75, 46.05, 7.05)
    var map by remember { mutableStateOf<MapController?>(null) }
    var altitude by remember { mutableStateOf(1f) }
    var hillshades by remember { mutableStateOf(true) }
    var lines by remember { mutableStateOf(true) }
    var slopes by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Downloading map + elevation…") }
    LaunchedEffect(map) {
        val controller = map ?: return@LaunchedEffect
        try {
            sdk.downloadArea(bounds, listOf(AreaFile(DataSet.Map, "terrain_map.vmtar"), AreaFile(DataSet.Elevation, "terrain_ele.eletar")))
            controller.reloadTiles(); status = "Terrain data ready"
        } catch (error: SdkException) { status = "Download error — ${describe(error)}" }
    }
    DemoScaffold(sdk, "3D Terrain", onBack, status, controls = {
        Text("Altitude Scale: ${(altitude * 10).roundToInt() / 10.0}")
        Slider(altitude, onValueChange = { altitude = it; map?.altitudeScale = it }, valueRange = 0f..3f, enabled = map != null)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { hillshades = !hillshades; map?.drawHillshades = hillshades }) { Text("Hillshades: ${if (hillshades) "ON" else "OFF"}") }
            TextButton(onClick = { lines = !lines; map?.drawElevationLines = lines }) { Text("Elevation Lines: ${if (lines) "ON" else "OFF"}") }
            TextButton(onClick = { slopes = !slopes; map?.drawSlopes = slopes }) { Text("Slopes: ${if (slopes) "ON" else "OFF"}") }
        }
    }) { controller ->
        controller.fitBounds(bounds, zoomDelta = 1.0)
        controller.moveCamera(pitch = 45.0)
        controller.altitudeScale = 1f; controller.drawHillshades = true; controller.drawElevationLines = true
        map = controller
    }
}

private val destinations = listOf("Porto" to GeoPoint(41.1579, -8.6291), "San Sebastián" to GeoPoint(43.3183, -1.9812),
    "Lucerne" to GeoPoint(47.0502, 8.3093), "Bruges" to GeoPoint(51.2093, 3.2247), "Dubrovnik" to GeoPoint(42.6507, 18.0944),
    "Tallinn" to GeoPoint(59.4370, 24.7536))

@Composable fun FlyToDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    var map by remember { mutableStateOf<MapController?>(null) }
    var index by remember { mutableStateOf(0) }
    fun fly(controller: MapController) = controller.animate(fly = true) { controller.moveCamera(center = destinations[index].second, zoom = 14.0) }
    DemoScaffold(sdk, destinations[index].first, onBack, controls = {
        Button(enabled = map != null, onClick = { index = (index + 1) % destinations.size; map?.let(::fly) }) { Text("Fly") }
    }) { controller -> map = controller; fly(controller) }
}

private val cities = listOf(GeoPoint(52.5037, 13.4102), GeoPoint(48.8505, 2.3343), GeoPoint(51.5072, -0.1275), GeoPoint(41.8933, 12.4829),
    GeoPoint(40.4168, -3.7038), GeoPoint(52.2251, 21.0103), GeoPoint(48.2082, 16.3738), GeoPoint(50.0755, 14.4378))

@Composable fun ZoomToBBoxDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    var map by remember { mutableStateOf<MapController?>(null) }
    val bounds = remember { GeoBounds.of(cities) }
    DemoScaffold(sdk, "Zoom to BBox", onBack, controls = {
        Button(enabled = map != null, onClick = { map?.let { controller -> controller.animate(duration = 2.0, fly = true) { controller.fitBounds(bounds) } } }) { Text("Zoom to Fit") }
    }) { controller ->
        map = controller
        controller.replaceLine(controller.createVectorLayer(5), cities.flatMap { listOf(it.longitude, it.latitude) }.toDoubleArray(), "line{width:4pt; color:#E74C3C;}")
        controller.fitBounds(bounds)
    }
}
