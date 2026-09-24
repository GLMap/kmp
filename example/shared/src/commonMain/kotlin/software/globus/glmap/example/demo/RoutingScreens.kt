package software.globus.glmap.example.demo
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import software.globus.glmap.example.*

private fun distance(meters: Double) = when {
    !meters.isFinite() || meters < 0 -> "-- m"
    meters < 1000 -> "${(meters / 10).roundToInt() * 10} m"
    else -> "${(meters / 100).roundToInt() / 10.0} km"
}
private fun duration(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "-- min"
    val minutes = (seconds / 60).toInt()
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
// GLRouteManeuver type values are shared by both platforms.
private fun glyph(type: Int) = when (type) {
    2, 9, 18, 20, 23 -> "↗"; 10 -> "→"; 11 -> "↘"; 12 -> "↷"
    3, 16, 19, 21, 24 -> "↖"; 15 -> "←"; 14 -> "↙"; 13 -> "↶"
    4, 5, 6 -> "⚑"; 26, 27 -> "↻"
    else -> "↑"
}

@Composable fun RouteBuildingDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    var map by remember { mutableStateOf<MapController?>(null) }
    var start by remember { mutableStateOf(GeoPoint(41.1457, -8.6107)) }
    var end by remember { mutableStateOf(GeoPoint(41.1597, -8.6300)) }
    var mode by remember { mutableStateOf(RouteMode.Auto) }
    var offline by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Tap map to set departure and destination") }
    var track by remember { mutableStateOf<MapTrack?>(null) }
    var route by remember { mutableStateOf<Route?>(null) }
    var picked by remember { mutableStateOf<GeoPoint?>(null) }
    DisposableEffect(Unit) { onDispose { route?.close() } }
    // Any change restarts the effect, which cancels the obsolete native request.
    LaunchedEffect(map, start, end, mode, offline) {
        val controller = map ?: return@LaunchedEffect
        status = "Building ${if (offline) "offline" else "online"} route…"
        try {
            val built = sdk.route(RouteQuery(start, end, mode, offline, locale = "en"))
            if (controller.disposed) { built.close(); return@LaunchedEffect }
            val line = track ?: controller.addTrack("{width:7pt; fill-image:\"track-arrow.svg\";}", 5).also { track = it }
            line.setRoute(built, 0xC832C800); route?.close(); route = built
            status = "${distance(built.length)} · ${duration(built.duration)}"
        } catch (error: SdkException) { status = "Routing error — ${describe(error)}" }
        catch (error: IllegalStateException) { status = describe(error) }
    }
    DemoScaffold(sdk, "Route Building", onBack, status, top = {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(RouteMode.Auto to "Auto", RouteMode.Bicycle to "Bike", RouteMode.Pedestrian to "Walk").forEach { (value, label) ->
                TextButton(onClick = { mode = value }) { Text(label, fontWeight = if (mode == value) FontWeight.Bold else FontWeight.Normal) }
            }
            Spacer(Modifier.weight(1f)); Text("Offline", fontSize = 12.sp); Switch(offline, onCheckedChange = { offline = it }, modifier = Modifier.testTag("route-offline"))
        }
    }) { controller ->
        controller.fitBounds(GeoBounds.of(listOf(start, end)), zoomDelta = -1.0)
        controller.onMapTap = { tap -> picked = tap.point }
        map = controller
    }
    picked?.let { point ->
        AlertDialog(onDismissRequest = { picked = null }, title = { Text("Set Point") }, text = { Text(coordinates(point)) },
            confirmButton = { TextButton(onClick = { end = point; picked = null }) { Text("Destination") } },
            dismissButton = { Row { TextButton(onClick = { picked = null }) { Text("Cancel") }; TextButton(onClick = { start = point; picked = null }) { Text("Departure") } } })
    }
}

// Custom route for checks without network or navigation data; it is not road routing.
private val sampleSteps = listOf(
    RouteStep(doubleArrayOf(19.2460, 42.4280, 19.2490, 42.4320, 19.2540, 42.4330), 8, "Continue on the sample street", 90.0),
    RouteStep(doubleArrayOf(19.2540, 42.4330, 19.2580, 42.4380), 10, "Turn right", 80.0),
    RouteStep(doubleArrayOf(19.2580, 42.4380, 19.2690, 42.4400), 15, "Turn left", 90.0))
/** Evenly walks route geometry so replay follows whatever route is active. */
private fun walk(lonLat: DoubleArray, stops: Int = 40) = DoubleArray(stops * 2).also { out ->
    val last = lonLat.size / 2 - 1
    for (i in 0 until stops) {
        val at = i.toDouble() * last / (stops - 1); val a = at.toInt().coerceAtMost(last - 1).coerceAtLeast(0); val t = at - a
        val b = (a + 1).coerceAtMost(last)
        out[2 * i] = lonLat[2 * a] + (lonLat[2 * b] - lonLat[2 * a]) * t; out[2 * i + 1] = lonLat[2 * a + 1] + (lonLat[2 * b + 1] - lonLat[2 * a + 1]) * t
    }
}

@Composable fun TurnByTurnDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    val gps = rememberLocationSource()
    var map by remember { mutableStateOf<MapController?>(null) }
    var title by remember { mutableStateOf("Waiting for location...") }
    var status by remember { mutableStateOf("") }
    var marker by remember { mutableStateOf<UserLocationMarker?>(null) }
    var track by remember { mutableStateOf<MapTrack?>(null) }
    var arrow by remember { mutableStateOf<MapLineArrow?>(null) }
    var route by remember { mutableStateOf<Route?>(null) }
    var tracker by remember { mutableStateOf<RouteTracker?>(null) }
    var last by remember { mutableStateOf<LocationFix?>(null) }
    var destination by remember { mutableStateOf<GeoPoint?>(null) }
    var navigation by remember { mutableStateOf<Navigation?>(null) }
    var replay by remember { mutableStateOf(0) }
    var moving by remember { mutableStateOf<MapAnimation?>(null) }
    var following by remember { mutableStateOf<MapAnimation?>(null) }

    fun navigate(fix: LocationFix) {
        val controller = map?.takeUnless { it.disposed } ?: return
        val state = tracker?.update(fix) ?: return
        navigation = state
        val maneuver = state.maneuver
        if (maneuver == null) arrow?.hidden = true else arrow?.setManeuver(maneuver)
        following?.cancel()
        following = controller.animate(duration = 1.0, linear = true) { track?.progress = state.progress; controller.moveCamera(center = state.position) }
    }
    fun show(built: Route, label: String) {
        val controller = map?.takeUnless { it.disposed } ?: run { built.close(); return }
        tracker?.close(); arrow?.hidden = true
        val line = track ?: controller.addTrack("{width:14pt; fill-image:\"track-arrow.svg\";}", 99).also { it.progressColor = 0xC8808080; track = it }
        line.progress = 0.0; line.setRoute(built, 0xC832C800)
        route?.close(); route = built
        tracker = built.tracker(); controller.fitBounds(built.bounds); title = label
        last?.let(::navigate)
    }
    DisposableEffect(Unit) { onDispose { tracker?.close(); route?.close() } }
    LaunchedEffect(map, replay) {
        if (map == null) return@LaunchedEffect
        val source: Flow<LocationFix> = if (replay == 0) gps.fixes() else replayFixes(walk(route?.lonLat ?: sampleSteps.first().lonLat))
        try {
            source.collect { fix ->
                val controller = map?.takeUnless { it.disposed } ?: return@collect
                val first = last == null
                last = fix
                if (first) { marker?.update(fix); controller.moveCamera(center = fix.point, zoom = 14.0); if (tracker == null) title = "Tap map to choose destination" }
                else { moving?.cancel(); moving = controller.animate(duration = 1.0, linear = true) { marker?.update(fix) } }
                navigate(fix)
            }
            if (replay > 0) status = "Replay finished"
        } catch (error: SdkException) { status = describe(error) }
    }
    // A new destination cancels the obsolete route request.
    LaunchedEffect(destination) {
        val target = destination ?: return@LaunchedEffect
        val from = last ?: return@LaunchedEffect
        tracker?.close(); tracker = null; navigation = null; arrow?.hidden = true; title = "Building route..."
        try { show(sdk.route(RouteQuery(from.point, target)), "Turn-by-Turn Navigation"); status = "" }
        catch (error: SdkException) { title = "Tap map to choose destination"; status = "Route error — ${describe(error)}" }
    }
    DemoScaffold(sdk, title, onBack, status, top = {
        Column(Modifier.fillMaxWidth().background(Color(0xF2262626)).padding(horizontal = 16.dp, vertical = 10.dp)) {
            val state = navigation
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state?.maneuver?.let { glyph(it.type) } ?: " ", color = Color.White, fontSize = 32.sp, modifier = Modifier.width(44.dp))
                Text(state?.maneuver?.let { distance(state.distanceToManeuver) } ?: "--", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("nav-distance"))
            }
            Text(state?.maneuver?.shortInstruction ?: "", color = Color.LightGray, fontSize = 15.sp, modifier = Modifier.testTag("nav-instruction"))
            Text(state?.let { "${distance(it.remainingDistance)} remaining  ·  ${duration(it.remainingDuration)}" } ?: "", color = Color.LightGray,
                fontSize = 13.sp, modifier = Modifier.testTag("nav-remaining"))
        }
    }, controls = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = map != null, onClick = {
                try { destination = null; show(sdk.buildRoute(sampleSteps), "Custom sample route (not road routing)"); status = "" }
                catch (error: SdkException) { status = describe(error) }
            }) { Text("Sample route") }
            Button(enabled = map != null, onClick = { last = null; replay++; status = "Replaying along the route" }) { Text("Replay") }
            TextButton(enabled = replay != 0, onClick = { last = null; replay = 0; status = "Waiting for GPS…" }) { Text("Use GPS") }
        }
    }) { controller ->
        try {
            controller.setOrigin(0.5, 0.25)
            arrow = controller.addLineArrow("{casing-width:2pt; casing-color:#32C800FF; width:14pt; color:white; linecap:round;}",
                SvgImage("route-maneuver-head.svg", 1.0, 0xFF32C800), 100)
            marker = controller.addUserLocation(101)
            controller.onMapTap = { tap -> if (last != null) destination = tap.point }
            map = controller
        } catch (error: Exception) { status = describe(error) }
    }
}
