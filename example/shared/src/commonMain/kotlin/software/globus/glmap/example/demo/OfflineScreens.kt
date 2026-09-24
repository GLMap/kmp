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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import software.globus.glmap.example.*

private fun megabytes(bytes: Long) = "${(bytes / 10_000.0).roundToInt() / 100.0} MB"

@Composable fun DownloadMapsDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    // Collections push their id; the root list is null.
    val path = remember { mutableStateListOf<Region>() }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var regions by remember { mutableStateOf(emptyList<Region>()) }
    var confirm by remember { mutableStateOf<Region?>(null) }
    fun reload() { try { regions = sdk.regions(path.lastOrNull()?.id) } catch (error: SdkException) { status = describe(error) } }
    fun leave() { if (path.isEmpty()) onBack() else { path.removeAt(path.lastIndex); query = "" } }
    LaunchedEffect(path.size) { reload() }
    LaunchedEffect(Unit) {
        try { sdk.refreshRegions(); reload() } catch (error: SdkException) { status = "Map list error — ${describe(error)}" }
    }
    LaunchedEffect(Unit) { sdk.regionChanges.collect { reload() } }
    SystemBack(path.isNotEmpty()) { leave() }
    Column(Modifier.fillMaxSize().background(Color(0xFFF3F5F7)).safeDrawingPadding().imePadding()) {
        TopAppBar(title = { Text(path.lastOrNull()?.name ?: "Download Maps", maxLines = 1) },
            navigationIcon = { TextButton(onClick = ::leave) { Text("‹ Back", color = Color.White) } })
        TextField(query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search maps") }, modifier = Modifier.fillMaxWidth().testTag("maps-filter"))
        if (status.isNotEmpty()) Text(status, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("demo-status"))
        val filter = query.trim()
        val visible = regions.filter { filter.isEmpty() || it.name.contains(filter, ignoreCase = true) || it.isoCode?.contains(filter, ignoreCase = true) == true }
        LazyColumn(Modifier.weight(1f)) {
            listOf("ON DEVICE" to visible.filter { it.onDevice }, "AVAILABLE" to visible.filterNot { it.onDevice }).forEach { (header, rows) ->
                if (rows.isNotEmpty()) item(key = header) { Text(header, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp)) }
                items(rows, key = { it.id }) { region ->
                    Column(Modifier.fillMaxWidth().background(Color.White).clickable {
                        try {
                            when {
                                region.isCollection -> { path.add(region); query = "" }
                                region.downloading -> sdk.cancelRegionDownload(region.id)
                                region.downloaded -> confirm = region
                                else -> sdk.downloadRegion(region.id)
                            }
                        } catch (error: SdkException) { status = describe(error) }
                    }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(region.name, fontSize = 16.sp)
                        Text(when {
                            region.isCollection -> "Browse regions"
                            region.downloading -> region.progress?.let { "Downloading ${(it * 1000).roundToInt() / 10.0}% — tap to cancel" } ?: "Starting download..."
                            region.downloaded -> "On device · ${megabytes(region.sizeOnDisk)}"
                            else -> megabytes(region.sizeOnServer)
                        }, fontSize = 12.sp, color = Color.Gray)
                    }
                    Divider()
                }
            }
        }
    }
    confirm?.let { region ->
        AlertDialog(onDismissRequest = { confirm = null }, text = { Text("Delete ${region.name} from this device?") },
            confirmButton = { TextButton(onClick = {
                try { sdk.deleteRegion(region.id); reload() } catch (error: SdkException) { status = describe(error) }
                confirm = null
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

@Composable fun DownloadBBoxDemo(sdk: GLMapSdk, onBack: () -> Unit) {
    val bounds = GeoBounds(43.73, 11.20, 43.80, 11.30)
    var map by remember { mutableStateOf<MapController?>(null) }
    var status by remember { mutableStateOf("Downloading map + nav + elevation...") }
    val progress = remember { mutableStateMapOf<DataSet, Pair<Long, Long>>() }
    var attempt by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    // Leaving the screen cancels the transfer and removes partial files.
    LaunchedEffect(map, attempt) {
        val controller = map ?: return@LaunchedEffect
        failed = false; status = "Downloading map + nav + elevation..."
        try {
            sdk.downloadArea(bounds, listOf(AreaFile(DataSet.Map, "bbox_map.vmtar"), AreaFile(DataSet.Navigation, "bbox_nav.navtar"),
                AreaFile(DataSet.Elevation, "bbox_ele.eletar"))) { dataSet, downloaded, total -> progress[dataSet] = downloaded to total }
            controller.drawElevationLines = true; controller.drawHillshades = true; controller.reloadTiles()
            status = "All data downloaded"
        } catch (error: SdkException) { failed = true; status = "Download failed — ${describe(error)}" }
        catch (error: IllegalStateException) { status = describe(error) }
    }
    DemoScaffold(sdk, "Download BBox", onBack, status, online = false, controls = {
        progress.entries.sortedBy { it.key }.forEach { (dataSet, value) ->
            Text("$dataSet: ${megabytes(value.first)}${if (value.second > 0) " of ${megabytes(value.second)}" else ""}", fontSize = 12.sp)
        }
        if (failed) Button(onClick = { attempt++ }) { Text("Retry") }
    }) { controller -> controller.fitBounds(bounds); controller.enableClipping(bounds, 9.0, 16.0); map = controller }
}
