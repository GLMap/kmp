plugins { id("com.android.application"); kotlin("plugin.compose") }
// Ignored config/local.json ({"GLMAP_API_KEY": "..."}) or the environment supply the demo key; it is never logged.
val glmapApiKey: String = rootProject.file("config/local.json").takeIf { it.isFile }
    ?.let { (groovy.json.JsonSlurper().parse(it) as? Map<*, *>)?.get("GLMAP_API_KEY") as? String }
    ?: System.getenv("GLMAP_API_KEY") ?: ""
android {
    namespace = "software.globus.lab.glmapkmp"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "software.globus.lab.glmapkmp"
        minSdk = 24; targetSdk = 37
        versionCode = 1; versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GLMAP_API_KEY", "\"" + glmapApiKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    // Large demo data is shared by the wrappers from the lab's fixtures.
    sourceSets.getByName("main").assets.srcDirs("../assets")
    androidResources { noCompress += listOf("vm", "ttf", "otf") }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")); signingConfig = signingConfigs.getByName("debug") } }
}
dependencies {
    implementation(project(":example"))
    implementation("androidx.activity:activity-compose:1.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
