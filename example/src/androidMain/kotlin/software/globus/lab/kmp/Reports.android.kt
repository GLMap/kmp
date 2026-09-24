package software.globus.lab.kmp
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
private lateinit var reportContext: Context
@Composable actual fun InitializeReports() { reportContext = LocalContext.current.applicationContext }
actual val platformName = "android"
actual fun saveReport(name: String, contents: String) { java.io.File(reportContext.getExternalFilesDir(null),name).writeText(contents) }
