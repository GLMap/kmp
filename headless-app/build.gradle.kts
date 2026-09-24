plugins {id("com.android.application")}
val probe=providers.gradleProperty("probe").orElse("core").get()
android {
 namespace="globus.tests.headless.app";compileSdk=37
 defaultConfig {applicationId="software.globus.modules.kmp$probe";minSdk=24;targetSdk=37;versionCode=1;versionName="0.0.1";ndk {abiFilters += "arm64-v8a"}}
 sourceSets.getByName("main").assets.srcDirs("../example/assets")
 androidResources {noCompress+=listOf("vm","ttf","otf")}
 buildTypes {release {isMinifyEnabled=true;proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"));signingConfig=signingConfigs.getByName("debug")}}
}
dependencies {implementation(project(":headless-probe"))}
