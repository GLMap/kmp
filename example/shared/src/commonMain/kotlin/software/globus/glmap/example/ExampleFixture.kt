package software.globus.glmap.example
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*

import kotlinx.serialization.json.*

// Camera and geometry used by the lifecycle sample and API checks.
internal const val demoFixtureJson = "{\n  \"id\": \"stage-a-v1\",\n  \"camera\": {\"latitude\": 49.0, \"longitude\": 16.0, \"zoom\": 5.0},\n  \"marker\": {\"latitude\": 50.0755, \"longitude\": 14.4378},\n  \"track\": {\n    \"type\": \"FeatureCollection\",\n    \"features\": [{\n      \"type\": \"Feature\",\n      \"properties\": {\"name\": \"Prague — Vienna — Budapest\"},\n      \"geometry\": {\n        \"type\": \"LineString\",\n        \"coordinates\": [[14.4378, 50.0755], [16.3738, 48.2082], [19.0402, 47.4979]]\n      }\n    }]\n  }\n}\n"

internal val stageFixture = kotlinx.serialization.json.Json.parseToJsonElement(demoFixtureJson).jsonObject
internal fun kotlinx.serialization.json.JsonObject.stageNumber(key: String) = getValue(key).jsonPrimitive.double
internal val stageCamera get() = stageFixture.getValue("camera").jsonObject.let {
    Camera(it.stageNumber("latitude"), it.stageNumber("longitude"), it.stageNumber("zoom"))
}
