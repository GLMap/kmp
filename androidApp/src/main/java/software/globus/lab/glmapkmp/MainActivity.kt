package software.globus.lab.glmapkmp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import software.globus.lab.kmp.LabApp
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { LabApp(BuildConfig.GLMAP_API_KEY) } }
}
