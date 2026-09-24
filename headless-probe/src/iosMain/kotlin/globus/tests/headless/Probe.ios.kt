package globus.tests.headless
import globus.glmap.core.*
import kotlinx.coroutines.*
class HeadlessProbe {
 fun run(callback:(String)->Unit) {MainScope().launch {
  try {callback(exercise(createGLMapSdk()))} catch(error:Exception){callback("FAIL $error")}
 }}
}
