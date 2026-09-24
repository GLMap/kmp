package software.globus.glmap.example
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*


import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.TimeSource

internal interface DemoHost {
    fun current(): MapController
    suspend fun recreate(camera: Camera? = null): MapController
    suspend fun second(): MapController
    suspend fun closeSecond()
}
internal fun lineCoordinates(count: Int) = DoubleArray(count*2) { i ->
    if (i%2==0) 14.0+(i/2)*0.00001 else 50.0+(i/2)*0.000005
}
private fun same(a: MapState,b: MapState) {
    for ((x,y) in listOf(a.latitude to b.latitude,a.longitude to b.longitude,a.zoom to b.zoom,
        a.angle to b.angle,a.pitch to b.pitch,a.originX to b.originX,a.originY to b.originY)) check(abs(x-y)<0.001) { "$a != $b" }
}
internal fun checkGeometry(map: MapController, layer: VectorLayer, values: DoubleArray) {
    val root = Json.parseToJsonElement(checkNotNull(map.geometryJson(layer))).jsonObject
    val geometry = (root["geometry"] ?: root).jsonObject
    val coordinates = geometry.getValue("coordinates").jsonArray
    check(coordinates.size*2==values.size) { "Point count differs: ${coordinates.size}" }
    coordinates.forEachIndexed { i,p ->
        val pair = p.jsonArray
        check(abs(pair[0].jsonPrimitive.double-values[2*i])<0.000001)
        check(abs(pair[1].jsonPrimitive.double-values[2*i+1])<0.000001)
    }
}
internal suspend fun runApiChecks(host: DemoHost, sdk:GLMapSdk): String {
    val tests = mutableListOf<JsonObject>()
    val start = Clock.System.now().toString()
    var error: String? = null
    suspend fun test(name: String, body: suspend () -> JsonElement) {
        val mark = TimeSource.Monotonic.markNow()
        val detail = withTimeout(25_000) { body() }
        tests += buildJsonObject { put("name",name); put("passed",true); put("milliseconds",mark.elapsedNow().inWholeNanoseconds/1e6); put("detail",detail) }
    }
    try {
        test("Fixture and native snapshot") {
            val state=host.current().captureState().await()
            check(abs(state.zoom-stageCamera.zoom)<0.001)
            check(abs(state.latitude-stageCamera.latitude)<0.01 && abs(state.longitude-stageCamera.longitude)<0.01)
            Json.encodeToJsonElement(state)
        }
        test("Camera and 20 concurrent captures") {
            val map=host.current(); map.setCamera(Camera(48.2082,16.3738,12.0,24.0,20.0))
            val snapshots=List(20) { map.captureState() }.awaitAll()
            val first=snapshots.first()
            check(abs(first.latitude-48.2082)<0.001 && abs(first.longitude-16.3738)<0.001)
            check(abs(first.zoom-12)<0.001 && abs(first.angle-24)<0.001 && abs(first.pitch-20)<0.001)
            snapshots.forEach { same(first,it) }
            map.moveCamera(angle = -90.0)
            val rotated = map.captureState().await()
            check(abs((rotated.angle % 360 + 360) % 360 - 270.0) < 0.001)
            check(abs(rotated.zoom - first.zoom) < 0.001 && abs(rotated.pitch - first.pitch) < 0.001)
            map.moveCamera(angle = first.angle)
            Json.encodeToJsonElement(first)
        }
        test("Packed, GeoJSON, restyle, invalid input and clear") {
            val map=host.current(); val layer=map.createVectorLayer()
            val expected=lineCoordinates(10_000); val input=expected.copyOf()
            val pending=map.replaceLine(layer,input); input.fill(0.0)
            check(pending.await()==UpdateResult.Ready); checkGeometry(map,layer,expected)
            check(map.setStyle(layer,blueStyle).await()==UpdateResult.Ready); checkGeometry(map,layer,expected)
            check(map.replaceGeoJson(layer,stageFixture.getValue("track").toString()).await()==UpdateResult.Ready)
            check(runCatching { map.replaceLine(layer,doubleArrayOf(1.0)).await() }.isFailure)
            check(runCatching { map.replaceLine(layer,doubleArrayOf(0.0,Double.NaN,1.0,2.0)).await() }.isFailure)
            check(runCatching { map.replaceGeoJson(layer,"invalid").await() }.isFailure)
            check(map.replaceLine(layer,doubleArrayOf()).await()==UpdateResult.Ready)
            check(map.geometryJson(layer)==null); map.removeLayer(layer)
            check(runCatching { map.setStyle(layer,redStyle).await() }.isFailure)
            buildJsonObject { put("points",10_000); put("inputCopied",true) }
        }
        test("Two maps, scoped handles and 60 overlapping updates") {
            val first=host.current(); val second=host.second()
            val a=first.createVectorLayer(); val b=second.createVectorLayer()
            check(runCatching { second.replaceLine(a,lineCoordinates(2)).await() }.isFailure)
            val values=lineCoordinates(10_000)
            val pending=(0 until 30).flatMap { listOf(first.replaceLine(a,values),second.replaceLine(b,values)) }
            val outcomes=pending.awaitAll(); check(outcomes.all { it==UpdateResult.Ready || it==UpdateResult.Superseded })
            checkGeometry(first,a,values); checkGeometry(second,b,values)
            val removed=first.replaceLine(a,values); first.removeLayer(a)
            val outcome=removed.await(); check(outcome==UpdateResult.Ready || outcome==UpdateResult.Cancelled)
            second.removeLayer(b); host.closeSecond()
            buildJsonObject { put("ready",outcomes.count { it==UpdateResult.Ready }); put("superseded",outcomes.count { it==UpdateResult.Superseded }); put("removal",outcome.name) }
        }
        test("Drawable handles reject mutations after removal and map disposal") {
            val map = host.current()
            val point = GeoPoint(42.4341, 19.26)
            val image = map.addImage(SvgImage("pin.svg"), 3, point)
            val group = map.addImageGroup(listOf(SvgImage("pin.svg")), 3)
            val balloon = map.addBalloon("{font-size:12;}")
            val track = map.addTrack("{width:4pt;}", 3)
            track.append(point, 0xFFFF0000)
            val location = map.addUserLocation(4)
            val handles = listOf(image, group, balloon, track, location)
            handles.forEach { it.remove(); it.remove() }
            val mutations: List<() -> Unit> = listOf(
                { image.position = point }, { image.scale = 2.0 }, { group.setPins(emptyList()) },
                { balloon.show(point, "late") }, { track.append(point, 0xFFFF0000) },
                { track.progress = 1.0 }, { location.update(LocationFix(point)) })
            (mutations + handles.map { handle -> { handle.hidden = true } }).forEach {
                check(runCatching(it).exceptionOrNull()?.message == "object_removed")
            }
            val late = map.addTrack("{width:4pt;}", 3)
            host.recreate()
            check(runCatching { late.append(point, 0xFFFF0000) }.exceptionOrNull()?.message == "map_disposed")
            late.remove()
            buildJsonObject { put("handles", handles.size) }
        }
        test("Services exchange Core geometry and state with Map") {
            sdk.initialize("")
            sdk.addBundledMap("Montenegro.vm")
            val center=GeoPoint(42.4341,19.26)
            val found=sdk.search(SearchQuery("Podgorica",center,offline=true,autocomplete=false))
            check(found.places.isNotEmpty())
            val map=host.current()
            map.moveCamera(center=center,zoom=14.0)
            val markers=map.addMarkers(found,SvgImage("pin.svg"),4)
            found.close() // Map retains its own Core vector wrappers.
            val screen=map.toDisplay(center)
            map.objectAt(MapTap(center,screen.x,screen.y))
            val route=sdk.buildRoute(listOf(RouteStep(doubleArrayOf(19.25,42.43,19.27,42.44),1,"Continue",30.0)))
            val track=map.addTrack("{width:4pt;}",3)
            track.setRoute(route,0xFFFF0000)
            val arrow=map.addLineArrow("{width:4pt;}",SvgImage("route-maneuver-head.svg"),5)
            arrow.setManeuver(route.maneuvers.first())
            arrow.remove();track.remove();route.close();markers.remove()
            buildJsonObject { put("independentModules",true) }
        }
        test("Navigate away and restore captured camera") {
            host.current().setCamera(Camera(48.2082,16.3738,12.0))
            val before=host.current().captureState().await()
            val after=host.recreate(before.camera()).captureState().await()
            same(before,after); Json.encodeToJsonElement(after)
        }
        test("Ten unmount and recreation cycles") {
            repeat(10) {
                val old=host.current(); val next=host.recreate()
                check(old.disposed)
                check(runCatching { old.captureState().await() }.isFailure)
                check(abs(next.captureState().await().zoom-stageCamera.zoom)<0.001)
            }
            buildJsonObject { put("cycles",10) }
        }
        test("Dispose settles pending updates and rejects later calls") {
            val map=host.current(); val layer=map.createVectorLayer()
            val pending=map.replaceLine(layer,lineCoordinates(100_000))
            map.dispose()
            check(runCatching { pending.await() }.exceptionOrNull()?.message=="map_disposed")
            check(runCatching { map.captureState().await() }.isFailure)
            Json.encodeToJsonElement(host.recreate().captureState().await())
        }
    } catch (failure: Exception) { error=failure.toString() }
    val report=buildJsonObject {
        put("schema",1); put("experiment","kmp-stage-a-api"); put("platform",platformName)
        put("startedUtc",start); put("completedUtc",Clock.System.now().toString()); put("passed",error==null)
        put("tests",JsonArray(tests)); error?.let { put("error",it) }
        put("nativeGestures","not exercised by the common API suite")
    }
    saveReport("kmp-results.json",report.toString())
    check(error==null) { error.orEmpty() }
    return "PASS: ${tests.size} scenarios"
}
