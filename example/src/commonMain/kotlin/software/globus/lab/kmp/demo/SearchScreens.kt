package software.globus.lab.kmp.demo
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import software.globus.lab.kmp.*

// Podgorica lies inside the bundled Montenegro map, so offline search has data.
private val podgorica = GeoPoint(42.4341, 19.26)

@Composable fun SearchDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    var map by remember { mutableStateOf<MapController?>(null) }
    var text by remember { mutableStateOf("") }
    var offline by remember { mutableStateOf(false) }
    // A new value restarts the search; typing asks for debounced autocomplete.
    var request by remember { mutableStateOf(0 to false) }
    fun submit(autocomplete: Boolean) { request = request.first + 1 to autocomplete }
    var title by remember { mutableStateOf("Search") }
    var status by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var markers by remember { mutableStateOf<MapMarkers?>(null) }
    var pin by remember { mutableStateOf<MapImage?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    fun clear() { pin?.hidden = true; selected = null; markers?.remove(); markers = null; results?.close(); results = null }
    fun select(index: Int) {
        val controller = map?.takeUnless { it.disposed } ?: return
        val place = results?.places?.getOrNull(index) ?: return
        if (selected != index) { markers?.select(index); selected = index; pin?.let { it.position = place.point; it.scale = 0.01; it.hidden = false } }
        controller.animate(duration = 0.3) { pin?.scale = 1.0; controller.moveCamera(center = place.point) }
    }
    DisposableEffect(Unit) { onDispose { results?.close() } }
    // Restarting this effect cancels the obsolete native request.
    LaunchedEffect(map, request) {
        val controller = map ?: return@LaunchedEffect
        val autocomplete = request.second
        if (autocomplete) delay(300)
        val source = if (offline) "Offline" else "Online"
        title = "Searching ${source.lowercase()}..."
        try {
            val found = sdk.search(SearchQuery(text, podgorica, offline, autocomplete, if (text.isBlank()) listOf("restaurant") else emptyList()))
            if (controller.disposed) { found.close(); return@LaunchedEffect }
            clear(); results = found; status = ""
            title = "$source: ${found.places.size} results"
            if (found.places.isNotEmpty()) markers = controller.addMarkers(found, SvgImage("cluster.svg", 0.2, 0xFF0066CC), 3).also { it.bounds?.let(controller::fitBounds) }
        } catch (error: SdkException) { title = "$source Search Failed"; status = describe(error) }
        catch (error: IllegalStateException) { status = describe(error) }
    }
    DemoScaffold(sdk, title, onBack, status, mapWeight = 3f, top = {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(text, onValueChange = { text = it; submit(autocomplete = true) }, singleLine = true, placeholder = { Text("Place or empty for restaurants") },
                modifier = Modifier.weight(1f).testTag("search-field"), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); submit(autocomplete = false) }))
            TextButton(onClick = { focus.clearFocus(); submit(autocomplete = false) }) { Text("Search") }
            Text("Offline", fontSize = 12.sp); Switch(offline, onCheckedChange = { offline = it; submit(autocomplete = false) })
        }
    }, below = {
        LazyColumn(Modifier.weight(2f).fillMaxWidth().background(Color.White), state = list) {
            itemsIndexed(results?.places.orEmpty()) { index, place ->
                Column(Modifier.fillMaxWidth().background(if (index == selected) Color(0xFFE3F0FF) else Color.White).clickable { select(index) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(place.name.ifEmpty { "Unnamed" }, fontSize = 15.sp)
                    if (place.detail.isNotEmpty()) Text(place.detail, fontSize = 12.sp, color = Color.Gray)
                }
                Divider()
            }
        }
    }) { controller ->
        try {
            sdk.addBundledMap("Montenegro.vm")
            controller.setCamera(Camera(podgorica.latitude, podgorica.longitude, 12.0))
            pin = controller.addImage(SvgImage("pin.svg", 1.4, 0xFFE63C3C), 4, podgorica).also { it.hidden = true }
            controller.onMapTap = { tap -> markers?.pick(tap)?.let(::select) }
            map = controller
        } catch (error: Exception) { status = describe(error) }
    }
    LaunchedEffect(selected) { selected?.let { list.animateScrollToItem(it) } }
}

@Composable fun PoiTapDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    var title by remember { mutableStateOf("Tap to find POI") }
    var status by remember { mutableStateOf("") }
    DemoScaffold(sdk, title, onBack, status) { controller ->
        try {
            controller.setCamera(Camera(43.7696, 11.2558, 16.0))
            val balloon = controller.addBalloon("{text-color:black;font-size:14;}")
            controller.onMapTap = { tap ->
                val place = controller.objectAt(tap)
                if (place == null) { balloon.hidden = true; title = "No POI here" }
                else { title = place.name.ifBlank { coordinates(place.point) }; balloon.show(place.point, title) }
            }
        } catch (error: Exception) { status = describe(error) }
    }
}
