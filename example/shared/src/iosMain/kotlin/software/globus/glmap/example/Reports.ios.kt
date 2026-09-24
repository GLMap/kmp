@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package software.globus.glmap.example
import globus.glmap.core.*
import globus.glmap.*
import globus.glsearch.*
import globus.glroute.*

import androidx.compose.runtime.Composable
import platform.Foundation.*
@Composable actual fun InitializeReports() {}
actual val platformName = "ios"
actual fun saveReport(name: String, contents: String) {
 val path=NSSearchPathForDirectoriesInDomains(NSDocumentDirectory,NSUserDomainMask,true).first() as String
 check(NSString.create(string=contents).writeToFile("$path/$name",true,NSUTF8StringEncoding,null))
}
