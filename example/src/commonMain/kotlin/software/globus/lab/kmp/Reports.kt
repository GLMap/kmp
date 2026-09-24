package software.globus.lab.kmp
import androidx.compose.runtime.Composable
@Composable expect fun InitializeReports()
expect fun saveReport(name: String, contents: String)
expect val platformName: String
