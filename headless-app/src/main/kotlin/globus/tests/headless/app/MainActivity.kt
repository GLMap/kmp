package globus.tests.headless.app
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import globus.tests.headless.runHeadlessProbe
import kotlinx.coroutines.Job
class MainActivity:Activity() {
 private var job:Job?=null
 override fun onCreate(savedInstanceState:Bundle?) {super.onCreate(savedInstanceState)
  val label=TextView(this);label.textSize=24f;label.gravity=17;label.text="Running";setContentView(label)
  job=runHeadlessProbe(this) {result->label.text=result;getExternalFilesDir(null)!!.resolve("result.txt").writeText(result)}
 }
 override fun onDestroy(){job?.cancel();super.onDestroy()}
}
