package software.globus.glmap.example
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*

import androidx.compose.runtime.Composable
@Composable expect fun InitializeReports()
expect fun saveReport(name: String, contents: String)
expect val platformName: String
