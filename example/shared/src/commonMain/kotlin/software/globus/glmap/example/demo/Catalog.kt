package software.globus.glmap.example.demo
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import software.globus.glmap.example.*

class Demo(val group: String, val title: String, val subtitle: String, val screen: @Composable (GLMapSdk, onBack: () -> Unit) -> Unit)

val demos = listOf(
    Demo("Map Display", "Online Map", "Vector tiles, custom raster source, tap interaction") { sdk, back -> OnlineMapDemo(sdk, back) },
    Demo("Map Display", "Dark Theme", "GLMapStyleParser with theme options") { sdk, back -> DarkThemeDemo(sdk, back) },
    Demo("Map Display", "3D Terrain", "Altitude scale, pitch, hillshades, elevation lines") { sdk, back -> TerrainDemo(sdk, back) },
    Demo("Camera", "Fly To", "GLMapAnimation.flyToMode") { sdk, back -> FlyToDemo(sdk, back) },
    Demo("Camera", "Zoom to BBox", "mapScaleForBBox, animate to fit") { sdk, back -> ZoomToBBoxDemo(sdk, back) },
    Demo("Draw Objects", "Image", "GLMapImage — tap to place and move a pin") { sdk, back -> ImageDemo(sdk, back) },
    Demo("Draw Objects", "Image Group", "GLMapImageGroup — many pins, shared images") { sdk, back -> ImageGroupDemo(sdk, back) },
    Demo("Draw Objects", "Markers & Clustering", "GLMapMarkerLayer with clustering") { sdk, back -> MarkerClusteringDemo(sdk, back) },
    Demo("Draw Objects", "Balloon", "GLMapBalloon — text callout on tap") { sdk, back -> BalloonDemo(sdk, back) },
    Demo("Draw Objects", "Track Arrows", "GLMapTrack fill image and GLMapLineArrow") { sdk, back -> TrackArrowsDemo(sdk, back) },
    Demo("Draw Objects", "User Location", "User location marker and accuracy circle") { sdk, back -> UserLocationDemo(sdk, back) },
    Demo("Vector Data", "Lines & Polygons", "GLMapVectorLayer with line and polygon") { sdk, back -> LinesPolygonsDemo(sdk, back) },
    Demo("Vector Data", "GeoJSON", "Load file, display, tap to identify") { sdk, back -> GeoJsonDemo(sdk, back) },
    Demo("Vector Data", "GPS Track", "GLMapTrack recording live GPS data") { sdk, back -> GpsTrackDemo(sdk, back) },
    Demo("Search", "Search", "Online and Offline requests") { sdk, back -> SearchDemo(sdk, back) },
    Demo("Search", "POI Tap", "Tap map labels to identify objects") { sdk, back -> PoiTapDemo(sdk, back) },
    Demo("Routing", "Route Building", "GLRouteRequest online/offline") { sdk, back -> RouteBuildingDemo(sdk, back) },
    Demo("Routing", "Turn-by-Turn Navigation", "Live location, GLRouteTracker, maneuvers") { sdk, back -> TurnByTurnDemo(sdk, back) },
    Demo("Offline Data", "Download Maps", "Browse, search, and manage offline maps") { sdk, back -> DownloadMapsDemo(sdk, back) },
    Demo("Offline Data", "Download BBox", "Download map + nav + elevation for area") { sdk, back -> DownloadBBoxDemo(sdk, back) },
)

fun describe(error: Throwable) = if (error is SdkException) "${error.code}: ${error.message}" else error.message ?: error.toString()

/** Runs one screen operation; failures land in onError, cancellation stays silent. */
fun CoroutineScope.attempt(onError: (String) -> Unit, body: suspend () -> Unit) = launch {
    try { body() } catch (error: CancellationException) { throw error } catch (error: Exception) { onError(describe(error)) }
}

@Composable fun DemoCatalog(apiKey: String, onExit: () -> Unit) {
    val sdk = rememberSdk()
    var sessionKey by remember { mutableStateOf(apiKey) }
    var status by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<Demo?>(null) }
    var askKey by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(sessionKey) {
        try {
            sdk.initialize(sessionKey)
            status = if (sessionKey.isEmpty()) "No API key: online services are unavailable." else "API key set for this session."
            ready = true
        } catch (error: Exception) { status = describe(error) }
    }
    DisposableEffect(Unit) { onDispose { sdk.tileDownloading = false } }
    MaterialTheme {
        val demo = current
        if (demo != null && ready) {
            SystemBack(true) { current = null }
            demo.screen(sdk) { current = null }
        } else Column(Modifier.fillMaxSize().background(Color(0xFFF3F5F7)).safeDrawingPadding()) {
            SystemBack(true, onExit)
            TopAppBar(title = { Text("GLMap API demos") },
                navigationIcon = { TextButton(onClick = onExit) { Text("‹ Checks", color = Color.White) } },
                actions = { TextButton(onClick = { askKey = true }, modifier = Modifier.testTag("demo-key")) { Text("API key", color = Color.White) } })
            if (status.isNotEmpty()) Text(status, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("demo-status"))
            LazyColumn(Modifier.weight(1f)) {
                demos.groupBy { it.group }.forEach { (group, entries) ->
                    item(key = group) { Text(group.uppercase(), fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
                    items(entries, key = { it.title }) { entry ->
                        Column(Modifier.fillMaxWidth().background(Color.White).clickable(enabled = ready) { current = entry }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(entry.title, fontSize = 16.sp)
                            Text(entry.subtitle, fontSize = 12.sp, color = Color.Gray)
                        }
                        Divider()
                    }
                }
            }
        }
        if (askKey) {
            var draft by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { askKey = false }, title = { Text("SDK API key") },
                text = { Column {
                    Text("Used for this session only and never stored.", fontSize = 12.sp)
                    TextField(draft, onValueChange = { draft = it.trim() }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                } },
                confirmButton = { TextButton(onClick = { sessionKey = draft; askKey = false }) { Text("Use") } },
                dismissButton = { TextButton(onClick = { askKey = false }) { Text("Cancel") } })
        }
    }
}

/** Shared screen layout: bar, status line, one demo map and the screen's controls. */
@Composable fun DemoScaffold(sdk: GLMapSdk, title: String, onBack: () -> Unit, status: String = "", online: Boolean = true,
    mapWeight: Float = 1f, top: @Composable ColumnScope.() -> Unit = {}, controls: @Composable ColumnScope.() -> Unit = {},
    below: @Composable ColumnScope.() -> Unit = {}, onReady: (MapController) -> Unit) {
    val ready by rememberUpdatedState(onReady)
    Column(Modifier.fillMaxSize().background(Color(0xFFF3F5F7)).safeDrawingPadding().imePadding()) {
        TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back", color = Color.White) } })
        top()
        if (status.isNotEmpty()) Text(status, fontSize = 13.sp, maxLines = 3, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("demo-status"))
        Box(Modifier.weight(mapWeight).fillMaxWidth()) {
            GLMap(Modifier.fillMaxSize(), onReady = { sdk.tileDownloading = online; ready(it) }, onTap = {}, fixture = false)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) { controls() }
        below()
    }
}
