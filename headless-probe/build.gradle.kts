plugins { kotlin("multiplatform"); id("com.android.kotlin.multiplatform.library") }
val probe=providers.gradleProperty("probe").orElse("core").get()
require(probe in listOf("core","search","route"))
val sdk=System.getenv("GLMAP_SDK_DIR")?.let {file(it).resolve("ios")} ?: rootProject.file(".local-sdk/ios")
val frameworks=listOf("GLMapCore") + when(probe) {"search"->listOf("GLSearch");"route"->listOf("GLRoute");else->emptyList()}
kotlin {
 android {namespace="globus.tests.headless.library";compileSdk=37;minSdk=24}
 listOf(iosArm64(),iosSimulatorArm64()).forEach {target->
  target.binaries.framework {baseName="GlobusHeadlessProbe";isStatic=true}
  target.binaries.all {
   val slices=if(target.name=="iosArm64") listOf("ios-arm64") else listOf("ios-arm64-simulator","ios-arm64_x86_64-simulator")
   linkerOpts(frameworks.map { name ->
    val candidates = slices.map { sdk.resolve("$name.xcframework/$it") }
    "-F${candidates.firstOrNull { it.isDirectory } ?: candidates.first()}"
   } + frameworks.flatMap {listOf("-framework",it)})
  }
 }
 sourceSets {
  commonMain {
   kotlin.srcDir("src/$probe/kotlin")
   dependencies {api(project(":glmap-core-kmp"));if(probe!="core") implementation(project(":gl$probe-kmp"))}
  }
 }
}
