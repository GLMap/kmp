package software.globus.glmap.example
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*

import globus.glmap.core.*
import androidx.compose.runtime.*
@Composable actual fun rememberSdk():GLMapSdk=remember {createGLMapSdk()}
