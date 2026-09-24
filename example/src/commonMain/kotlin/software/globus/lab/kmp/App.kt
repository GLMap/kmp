package software.globus.lab.kmp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stage A stays the launch screen; the demo catalog replaces it while open. */
@Composable fun LabApp(apiKey: String = "") {
    InitializeReports()
    var demos by remember { mutableStateOf(false) }
    if (demos) software.globus.lab.kmp.demo.DemoCatalog(apiKey, onExit = { demos = false }) else App(onOpenDemos = { demos = true })
}

@Composable fun App(onOpenDemos: (() -> Unit)? = null) {
    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    var status by remember { mutableStateOf("Starting map…") }
    var visible by remember { mutableStateOf(true) }
    var overlay by remember { mutableStateOf(true) }
    var secondary by remember { mutableStateOf(false) }
    var map by remember { mutableStateOf<MapController?>(null) }
    var secondMap by remember { mutableStateOf<MapController?>(null) }
    var saved by remember { mutableStateOf<Camera?>(null) }
    var ready by remember { mutableStateOf(CompletableDeferred<MapController>()) }
    var secondReady by remember { mutableStateOf(CompletableDeferred<MapController>()) }
    var running by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf("") }
    var taps by remember { mutableStateOf(0) }
    val host=object: LabHost {
        override fun current()=checkNotNull(map)
        override suspend fun recreate(camera: Camera?): MapController {
            map=null; saved=camera; visible=false
            delay(100)
            ready=CompletableDeferred(); visible=true
            return withTimeout(10_000) { ready.await() }
        }
        override suspend fun second(): MapController {
            secondReady=CompletableDeferred(); secondary=true
            return withTimeout(10_000) { secondReady.await() }
        }
        override suspend fun closeSecond() { secondary=false; delay(100); check(secondMap?.disposed==true); secondMap=null }
    }
    fun runTests() {
        if (running) return
        running=true; status="Running API suite…"
        scope.launch {
            try { status=runApiChecks(host) }
            catch (error: Exception) { status="FAIL: $error" }
            finally { running=false }
        }
    }
    fun action(body: suspend (MapController) -> Unit) {
        scope.launch { try { body(checkNotNull(map)) } catch (error: Exception) { status="FAIL: $error" } }
    }
    MaterialTheme {
        Column(Modifier.fillMaxSize().background(Color(0xFFF3F5F7)).safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth()) {
                Text(status, fontSize=12.sp, maxLines=5, modifier=Modifier.weight(1f).padding(8.dp).testTag("lab-status"))
                if (onOpenDemos != null) TextButton(onClick=onOpenDemos, modifier=Modifier.testTag("open-demos")) { Text("Demos") }
            }
            if (visible) {
                Row(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        GLMap(Modifier.fillMaxSize(),onReady={ controller ->
                            saved?.let { controller.setCamera(it) }
                            map=controller; ready.complete(controller)
                            if (!started) { started=true; runTests() }
                        },onTap={ taps++ })
                        if (overlay) {
                            Column(Modifier.padding(12.dp).background(Color.White.copy(alpha=.90f)).padding(12.dp)) {
                                Text("KMP · Compose · GLMap · taps $taps",fontSize=14.sp)
                                TextField(field,onValueChange={field=it},singleLine=true,
                                    placeholder={Text("Type over the map")},
                                    modifier=Modifier.fillMaxWidth().semantics { contentDescription="Overlay field" },
                                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),
                                    keyboardActions=KeyboardActions(onDone={focus.clearFocus()}))
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                                    TextButton(onClick={action { it.setCamera(Camera(48.2082,16.3738,6.0)) }},enabled=!running) { Text("Vienna") }
                                    TextButton(onClick={action { it.setCamera(stageCamera) }},enabled=!running) { Text("Reset") }
                                    TextButton(onClick={action { status=Json.encodeToString(it.captureState().await()) }},enabled=!running) { Text("State") }
                                }
                            }
                        }
                    }
                    if (secondary) GLMap(Modifier.weight(1f).fillMaxHeight(),onReady={ secondMap=it; secondReady.complete(it) },onTap={})
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp)) {
                    Text("Map removed")
                    TextButton(onClick={ready=CompletableDeferred();visible=true},enabled=!running) { Text("Return") }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                TextButton(onClick={action { saved=it.captureState().await().camera();visible=false;map=null }},enabled=visible&&!running) { Text("Leave map") }
                TextButton(onClick={focus.clearFocus();overlay=!overlay},enabled=!running) { Text("Overlay") }
                TextButton(onClick={runTests()},enabled=visible&&!running) { Text("Run tests") }
                TextButton(onClick={
                    running=true; status="Benchmark running…"
                    scope.launch {
                        try { status=runBenchmark(checkNotNull(map)) }
                        catch (error: Exception) { status="BENCH FAIL: $error" }
                        finally { running=false }
                    }
                },enabled=visible&&!running) { Text("Bench") }
            }
        }
    }
}
