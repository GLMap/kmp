package globus.tests.headless
import android.content.Context
import globus.glmap.core.*
import kotlinx.coroutines.*
fun runHeadlessProbe(context:Context,callback:(String)->Unit):Job = MainScope().launch {
 try {
  check(runCatching {Class.forName("globus.glmap.GLMapViewRenderer",false,context.classLoader)}.exceptionOrNull() is ClassNotFoundException)
  callback(exercise(createGLMapSdk(context)))
 } catch(error:Exception) {callback("FAIL $error")}
}
