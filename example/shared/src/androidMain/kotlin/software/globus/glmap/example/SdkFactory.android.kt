package software.globus.glmap.example
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*

import globus.glmap.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
@Composable actual fun rememberSdk():GLMapSdk {val context=LocalContext.current.applicationContext;return remember {createGLMapSdk(context)}}
