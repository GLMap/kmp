package globus.tests.headless
import globus.glmap.core.*
import globus.glsearch.*
internal suspend fun exercise(sdk:GLMapSdk):String {
 sdk.initialize("");sdk.addBundledMap("Montenegro.vm")
 val result=sdk.search(SearchQuery("Podgorica",GeoPoint(42.4341,19.26),offline=true,autocomplete=false))
 check(result.places.isNotEmpty());val data=result.retainVectorObjects();data.close();result.close()
 return "PASS search"
}
