package software.globus.lab.kmp
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*

import androidx.compose.ui.window.ComposeUIViewController
fun MainViewController(apiKey: String) = ComposeUIViewController { LabApp(apiKey) }
