package globus.tests.headless
import globus.glmap.core.*
internal suspend fun exercise(sdk:GLMapSdk):String {sdk.initialize("");sdk.regions(null);return "PASS core"}
