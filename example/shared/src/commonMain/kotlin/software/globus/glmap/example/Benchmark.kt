package software.globus.glmap.example
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*


import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.math.PI
import kotlin.math.sin

internal suspend fun runBenchmark(map: MapController): String = withTimeout(180_000) {
    val started=Clock.System.now().toString()
    val rows=mutableListOf<JsonObject>()
    fun row(operation: String, count: Int, round: Int, samples: List<Double>) {
        rows += buildJsonObject {
            put("operation",operation); put("points",count); put("round",round)
            put("milliseconds",JsonArray(samples.map(::JsonPrimitive)))
        }
    }
    val layer=map.createVectorLayer()
    // Match the coordinate payload used by the Flutter/RN transport controls.
    val inputs=listOf(1_000,10_000,100_000).associateWith { count ->
        DoubleArray(count*2) { index ->
            val t=(index/2).toDouble()/(count-1)
            if(index%2==0) 13+6*t else 49+0.2*sin(8*PI*t)
        }
    }
    val alternateStyle="line{width:4pt;color:#2650D6;}"
    try {
        map.setCamera(stageCamera)
        map.captureState().await()
        repeat(5) { round ->
            for ((count,coordinates) in inputs) {
                repeat(3) { check(map.replaceLine(layer,coordinates).await()==UpdateResult.Ready) }
                val submission=mutableListOf<Double>(); val ready=mutableListOf<Double>()
                repeat(15) {
                    val mark=TimeSource.Monotonic.markNow()
                    val pending=map.replaceLine(layer,coordinates)
                    submission += mark.elapsedNow().inWholeNanoseconds/1e6
                    check(pending.await()==UpdateResult.Ready)
                    ready += mark.elapsedNow().inWholeNanoseconds/1e6
                }
                row("replace_submission",count,round,submission)
                row("replace_ready",count,round,ready)
                checkGeometry(map,layer,coordinates)
            }
            repeat(30) {
                map.setCamera(Camera(49.0,16.0,5.0))
                map.captureState().await()
            }
            val commands=mutableListOf<Double>(); val snapshots=mutableListOf<Double>()
            repeat(50) { index ->
                val mark=TimeSource.Monotonic.markNow()
                map.setCamera(Camera(49.0,16.0,if(index%2==0) 5.0 else 6.0))
                commands += mark.elapsedNow().inWholeNanoseconds/1e6
                val capture=TimeSource.Monotonic.markNow()
                val state=map.captureState().await()
                snapshots += capture.elapsedNow().inWholeNanoseconds/1e6
                check(kotlin.math.abs(state.zoom-(if(index%2==0) 5.0 else 6.0))<0.001)
            }
            row("set_camera_submission",0,round,commands); row("capture_state",0,round,snapshots)
            repeat(3) { check(map.setStyle(layer,redStyle).await()==UpdateResult.Ready) }
            val submission=mutableListOf<Double>(); val ready=mutableListOf<Double>()
            repeat(15) { index ->
                val mark=TimeSource.Monotonic.markNow()
                val result=map.setStyle(layer,if(index%2==0) redStyle else alternateStyle)
                submission += mark.elapsedNow().inWholeNanoseconds/1e6
                check(result.await()==UpdateResult.Ready)
                ready += mark.elapsedNow().inWholeNanoseconds/1e6
            }
            row("restyle_submission",100_000,round,submission); row("restyle_ready",100_000,round,ready)
        }
        val report=buildJsonObject {
            put("schema",2); put("experiment","kmp-transport"); put("platform",platformName); put("passed",true)
            put("startedUtc",started); put("completedUtc",Clock.System.now().toString())
            put("arrayOwnership","Reused DoubleArray; native geometry read back after every size; copying correctness checked by API suite")
            put("submissionBoundary","Common API entry through validation, native geometry construction and native setter return; excludes async Ready and frame presentation")
            put("payload","lon=13+6t; lat=49+0.2*sin(8*pi*t); t=i/(count-1)")
            put("rounds",5); put("geometrySamplesPerRound",15); put("cameraSamplesPerRound",50)
            put("warmup","Before each cell: 3 geometry/style, 30 paired camera/capture calls")
            put("styleParsing","Included in the public API submission timer")
            put("rows",JsonArray(rows))
        }
        saveReport("kmp-benchmark.json",report.toString())
        "BENCH PASS: 800 operations"
    } finally { map.removeLayer(layer); map.setCamera(stageCamera) }
}
