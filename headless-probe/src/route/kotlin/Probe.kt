package globus.tests.headless
import globus.glmap.core.*
import globus.glroute.*
internal suspend fun exercise(sdk:GLMapSdk):String {
 sdk.initialize("")
 val route=sdk.buildRoute(listOf(RouteStep(doubleArrayOf(19.25,42.43,19.27,42.44),1,"Continue",30.0)))
 check(route.length>0);val data=route.trackData(0xFFFF0000);data.close()
 val tracker=route.tracker();tracker.update(LocationFix(GeoPoint(42.43,19.25)));tracker.close();route.close()
 return "PASS route"
}
